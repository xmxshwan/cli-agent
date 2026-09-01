# Main.java 执行流程逐段解析

> 这是一个 "如果你面试被问到 PaiCLI 启动流程" 能脱口而出的程度。
>
> 读完本文，你应该能在不看代码的情况下画出完整的启动 -> 主循环 -> 退出的流程图。

---

## 总览：6 个阶段

Main.java 的 `main()` 方法（~2500 行）可以分为 **6 个清晰的阶段**：

```text
阶段 0: 启动前置         → 解析 args，判断是否是 HTTP serve 模式
阶段 1: 基础设施初始化     → 配置、日志、LLM 客户端、终端
阶段 2: 子系统初始化       → HITL、MCP、Browser、Skill、Agent
阶段 3: 启动画面           → 打印 banner，展示模型/MCP/Skill 状态
阶段 4: 主循环             → while(true) 读取输入 -> 执行 -> 循环
阶段 5: 退出清理           → JVM shutdown hook 关闭 MCP/Task
```

下面逐段展开。

---

## 阶段 0：启动前置（第 192-198 行）

```java
public static void main(String[] args) {
    configureAwtForCli();          // macOS 设 headless=true
    if (isRuntimeServeCommand(args)) {
        startRuntimeApiAndBlock(args);  // HTTP API 模式，不进入 CLI
        return;
    }
    configureLogging();            // 配置日志目录(~/.paicli/logs/)
```

**有两种启动方式：**

| 方式 | 命令 | 行为 |
|------|------|------|
| 交互式 CLI | `java -jar paicli.jar` | 进入终端交互循环 |
| HTTP API | `java -jar paicli.jar serve --http --port 8080` | 启动 REST API 服务，不进入 CLI |

这里 `isRuntimeServeCommand()` 判断第一个参数是不是 `serve` 且含 `--http`。
如果是，调用 `startRuntimeApiAndBlock()` 启动 HTTP Server 并阻塞——**再也不回来**。

---

## 阶段 1：基础设施初始化（第 200-250 行）

这个阶段创建了应用层面所有"一次性"的基础设施对象。

### 1.1 配置和 LLM 客户端

```java
PaiCliConfig config = PaiCliConfig.load();          // 读取 ~/.paicli/config.json
LlmClient llmClient = LlmClientFactory.createFromConfig(config);
// 如果没有任何 API Key 配置，直接退出
if (llmClient == null) {
    System.err.println("❌ 错误: 未找到可用的 API Key");
    System.exit(1);
}
```

`LlmClientFactory.createFromConfig()` 的搜索顺序：

1. 读 `config.json` 的 `defaultProvider` 字段
2. 遍历 5 个 Provider（GLM/DeepSeek/Step/Kimi/FreeLLMAPI），谁有 API Key 就用谁
3. 如果都没有 -> 返回 null，程序退出

### 1.2 终端初始化

```java
try (Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {
    // JLine Terminal + LineReader
    LineReader lineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .history(new PaiCliHistory())          // 自定义历史管理
        .completer(new PaiCliCompleter(...))    // Tab 补全（MCP 资源 + Skill）
        .highlighter(new PaiCliHighlighter())  // 语法高亮
        .build();
```

### 1.3 基础设施对象一览

这一阶段创建了后续所有阶段需要的对象：

| 变量 | 类 | 用途 |
|------|-----|------|
| `config` | `PaiCliConfig` | 持久化配置（Provider/模型/模式） |
| `llmClient` | `LlmClient` 实现类 | 与 LLM 通信的客户端 |
| `terminal` | `Terminal` | JLine 终端抽象 |
| `lineReader` | `LineReader` | JLine 行读取器（输入编辑/历史/补全） |
| `hitlHandler` | `SwitchableHitlHandler` | 可开关的 HITL 审批链 |
| `hitlToolRegistry` | `HitlToolRegistry` | 带 HITL 控制的工具注册表 |
| `browserSession` | `BrowserSession` | 浏览器会话管理 |

---

## 阶段 2：子系统初始化（第 256-314 行）

这个阶段创建了所有"运行时子系统"，是初始化最密集的部分。

### 2.1 渲染器（第 256-264 行）

```java
Renderer renderer = RendererFactory.create(RendererFactory.resolveMode(), terminal);
// inline 模式需要绑定 JLine 行读取器
if (renderer instanceof InlineRenderer inline) {
    inline.bindLineReader(lineReader);
}
renderer.start();
```

三种渲染器选型逻辑：

```text
-Dpaicli.renderer=lanterna     → Lanterna 全屏 TUI
PAICLI_RENDERER=plain          → 纯文本输出
PAICLI_TUI=true                → 兼容旧配置，等价 lanterna
默认                            → InlineRenderer（Claude Code 风格）
```

### 2.2 HITL 审批链（第 213-215 行）

```java
TerminalHitlHandler terminalHitlHandler = new TerminalHitlHandler(false); // 默认关闭
SwitchableHitlHandler hitlHandler = new SwitchableHitlHandler(terminalHitlHandler);
HitlToolRegistry hitlToolRegistry = new HitlToolRegistry(hitlHandler);
```

设计模式：**装饰器模式** 和 **策略模式** 的组合：

```text
HitlHandler (接口)
  ├── TerminalHitlHandler     — 终端弹确认框
  ├── RendererHitlHandler     — inline 渲染器内嵌确认
  └── SwitchableHitlHandler   — 可开关的装饰器，委托给上面两者之一
```

### 2.3 MCP 系统（第 268-278 行）

```java
// 确保 ~/.paicli/mcp.json 存在（默认配 chrome-devtools）
McpConfigBootstrapResult bootstrapResult = ensureDefaultMcpConfig(home);
mcpServerManager.loadConfiguredServers();             // 读取 mcp.json
mcpServerManager.startAll(ui, mcpStartupWait());     // 启动所有 MCP Server
Runtime.getRuntime().addShutdownHook(new Thread(mcpServerManager::close));
```

启动 MCP Server 的实际工作流：

```text
McpServerManager.startAll()
  ├── 遍历 mcp.json 中的每个 server 配置
  ├── 根据 transport 类型创建客户端:
  │   ├── "command" 字段 → StdioTransport（子进程 stdin/stdout）
  │   └── "url" 字段     → StreamableHttpTransport（HTTP + SSE）
  ├── 发送 initialize 请求，获取 tools/resources 列表
  └── 状态变为 READY / FAILED
```

### 2.4 Skill 系统（第 282-299 行）

```java
// 三层 Skill 加载路径
Path skillsCacheDir = home.resolve(".paicli/skills-cache");   // 内置 skill（缓存）
Path userSkillsDir = home.resolve(".paicli/skills");          // 用户自定义
Path projectSkillsDir = Path.of(".paicli/skills");            // 项目级

SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(skillsCacheDir);
extractor.extractAll();  // 解压内置 skill 到缓存目录

SkillRegistry skillRegistry = new SkillRegistry(
    skillsCacheDir, userSkillsDir, projectSkillsDir, skillStateStore);
skillRegistry.reload();  // 扫描三个目录，加载所有 SKILL.md
```

### 2.5 Agent 和 Task Manager（第 301-307 行）

```java
// 创建默认的 ReAct Agent，注入 LLM 客户端和 HITL 控制
Agent reactAgent = new Agent(llmClient, hitlToolRegistry);
reactAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
reactAgent.setSkillRegistry(skillRegistry);

// 后台任务管理器
DurableTaskManager taskManager = openTaskManager(llmClientRef);
taskManager.start();
```

### 阶段 2 完成后，所有子系统的关系图

```text
Main.java (全局所有权)
  │
  ├── LlmClient ← 5 种 Provider 实现之一
  ├── HitlToolRegistry ← SwitchableHitlHandler ← TerminalHitlHandler / RendererHitlHandler
  ├── McpServerManager → StdioTransport / StreamableHttpTransport → MCP Server 们
  ├── SkillRegistry → 3 层 Skill 目录加载
  ├── Agent (ReAct) ← ToolRegistry + MemoryManager
  ├── DurableTaskManager
  └── Renderer → InlineRenderer / PlainRenderer
```

---

## 阶段 3：启动画面（第 308-315 行）

```java
renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
// inline 模式：在首屏显示启动信息块
if (renderer instanceof InlineRenderer inline) {
    inline.installStartupScreen(startupScreenLines(startupScreenInfo));
} else {
    printStartupScreen(ui, startupScreenInfo);
}
```

启动画面包含的信息：

```text
██████████    PaiCLI π  v16.1.0
  ██  ██     Model glm-5.1 (GLM)
  ██  ██     MCP 1/1 · 15 tools · 3/5 skills · ReAct
  ██  ██     ReAct · Plan · MCP · Browser · Image · Tools · Memory · RAG

Tips for getting started:
1. Type / for commands and Tab completion
2. Ask coding questions, edit code or run commands
3. Attach context with @path or @image:
```

---

## 阶段 4：主循环（第 318-765 行）

这是整个程序的核心，也是一个**状态机**。

### 4.0 快速判断：是否进入 TUI（第 320-330 行）

```java
if (com.paicli.tui.TuiBootstrap.shouldUseTui(terminal)) {
    TuiBootstrap.launch(config, llmClient, reactAgent, hitlHandler);
    return;  // TUI 模式不进入 CLI 循环
}
```

如果检测到 `shouldUseTui()` 为 true（PAICLI_TUI=true 或终端能力足够），则启动全屏 Lanterna TUI，跳过 CLI 循环。

### 4.1 主循环流程图

```text
┌───────────────────────────────────────────────────────────┐
│                     while(true) 主循环                     │
└───────────────────────────────────────────────────────────┘
                              │
                              ▼
                ┌─────────────────────────┐
                │  读取用户输入             │
                │  readPromptInput()       │
                │  (JLine LineReader)      │
                └────────────┬────────────┘
                             │
                    ┌────────┴────────┐
                    ▼                 ▼
              Ctrl+C?           Ctrl+D?
              ────────           ────────
              continue           break → 退出
                    │                 │
                    ▼                 ▼
              重新循环              退出
                    │
                    ▼
          ┌─────────────────┐
          │  trim() 输入为空? │──yes──→ continue
          └────────┬────────┘
                   │ no
                   ▼
          ┌──────────────────────────────────┐
          │  CliCommandParser.parse(input)    │
          │  返回 ParsedCommand(type, payload)│
          └──────────────┬───────────────────┘
                        │
              ┌─────────┴──────────┐
              ▼                    ▼
        type != NONE           type == NONE
        (斜杠命令)              (普通文本)
              │                    │
              ▼                    ▼
    ┌─────────────────┐    ┌─────────────────┐
    │ switch(type)     │    │ 进入 Agent 执行  │
    │ {EXIT, CLEAR,   │    │                 │
    │  SWITCH_MODEL,  │    │ 1. @引用展开     │
    │  MEMORY_LIST,   │    │ 2. 选执行模式:   │
    │  MCP_LIST, ...} │    │    React(默认)   │
    │                 │    │    Plan(/plan)   │
    │ 处理后 continue  │    │    Team(/team)   │
    └─────────────────┘    │ 3. runTask.call()│
                           │ 4. 渲染输出       │
                           └─────────────────┘
                                    │
                                    ▼
                              继续循环
```

### 4.2 命令分发 —— 核心代码

#### 斜杠命令处理（第 386-712 行）

38 种命令类型，每种的处理逻辑：

```java
switch (command.type()) {
    case EXIT       -> { ui.println("👋 再见!"); renderer.close(); return; }
    case CLEAR      -> { reactAgent.clearHistory(); hitlHandler.clearApprovedAll(); }
    case SWITCH_PLAN -> { nextTaskUsePlanMode = true; }  // 下一条任务用 Plan
    case SWITCH_TEAM -> { nextTaskUseTeamMode = true; }  // 下一条任务用 Team
    case SWITCH_MODEL -> {
        // 动态切换 LLM Provider
        LlmClient newClient = LlmClientFactory.create(target.provider(), config);
        reactAgent.setLlmClient(newClient);
    }
    case MCP_LIST   -> { ui.println(mcpServerManager.formatStatus()); }
    case MEMORY_SAVE -> { reactAgent.getMemoryManager().storeFact(fact, scope); }
    // ... 还有 26 种
}
```

**设计要点**：
- 所有斜杠命令都在 CLI 层直接处理，**不让 LLM 知道**
- `/plan` 和 `/team` 设置的是"下一条任务模式标志"，执行完后自动重置回 ReAct

#### 普通文本处理 —— 通往 Agent 的路径（第 714-764 行）

```java
// 1. @引用展开（MCP 资源 / 本地文件）
input = mentionExpander.expand(input);
input = localPathMentionExpander.expand(input);

// 2. 选择执行模式并创建可执行任务
Callable<String> runTask;
if (nextTaskUsePlanMode) {
    PlanExecuteAgent planAgent = createPlanAgent(activeClient, reactAgent, ...);
    runTask = () -> planAgent.run(taskInput);
} else if (nextTaskUseTeamMode) {
    AgentOrchestrator orchestrator = createTeamAgent(activeClient, reactAgent, ...);
    runTask = () -> orchestrator.run(taskInput);
} else {
    runTask = () -> reactAgent.run(taskInput);  // 默认 ReAct
}

// 3. 执行（支持 ESC 取消）
String response = runWithCancelSupport(terminal, ui,
    () -> snapshotService.runTurn(snapshotMode, taskInput, runTask::call));

// 4. 输出结果
if (response != null && !response.isBlank()) {
    ui.println(response);
}

// 5. 重置模式标志
nextTaskUsePlanMode = false;
nextTaskUseTeamMode = false;
```

### 4.3 runWithCancelSupport —— 支持 ESC 取消的执行器（第 872-925 行）

这是一个**带取消功能的异步执行包装器**：

```java
private static String runWithCancelSupport(Terminal terminal, PrintStream out, Callable<String> task) {
    CancellationToken token = CancellationContext.startRun();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<String> future = executor.submit(task);

    // 进入 raw mode 监听 ESC
    while (!future.isDone()) {
        if (readEscCancel(terminal)) {   // 检测到孤立的 ESC
            token.cancel();
            future.cancel(true);         // 中断 Agent 线程
            return "⏹️ 已取消当前任务。";
        }
        try {
            return future.get(150, TimeUnit.MILLISECONDS);  // 每 150ms 轮询一次 ESC
        } catch (TimeoutException ignored) {
            // 继续监听
        }
    }
}
```

**关键细节**：方向键也是以 ESC 开头（`ESC[A`, `ESC[B`）
`readEscCancel()` 通过 `classifyEscapeSequence()` 区分：
- **STADALONE_ESC**（孤立 ESC）→ 真正取消
- **CONTROL_SEQUENCE**（ESC + `[` + 字符）→ 方向键/Home/End，不取消

---

## 阶段 5：退出清理（第 766-773 行）

```java
while (true) {
    // ... 主循环 ...
    case EXIT -> {
        ui.println("\n👋 再见!");
        renderer.close();
        return;  // 从 main() 返回
    }
}
// JVM shutdown hook 会自动触发：
// - mcpServerManager.close()  — 关闭所有 MCP Server
// - taskManager.close()       — 持久化后台任务状态
```

JVM 退出时，在 `main()` 之前注册的两个 shutdown hook 会自动执行：

| Hook | 注册位置 | 功能 |
|------|---------|------|
| `mcpServerManager::close` | 第 275 行 | 向所有 MCP Server 发送关闭通知 |
| `taskManager::close` | 第 307 行 | 保存正在运行的后台任务状态 |

---

## 完整执行时序图

```text
时间 →
─────

main() 启动
  │
  ├─ [阶段 0] CLI 模式判断 ─── serve --http? ──→ HTTP API 模式（永不返回）
  │
  ├─ [阶段 1] 配置加载 ────── .env → ~/.paicli/config.json → LLM 客户端
  │          终端初始化 ────── JLine Terminal + LineReader
  │
  ├─ [阶段 2] 渲染器创建 ──── RendererFactory → InlineRenderer（默认）
  │          HITL 链初始化 ── SwitchableHitlHandler ← TerminalHitlHandler
  │          MCP 启动 ─────── McpServerManager.startAll() → Stdio/HTTP
  │          Skill 加载 ───── 3 层目录扫描 → SkillRegistry
  │          Agent 创建 ───── Agent(llmClient, hitlToolRegistry)
  │          后台任务 ─────── DurableTaskManager.start()
  │
  ├─ [阶段 3] 启动画面 ────── 模型/MCP/Skill 状态展示
  │
  ├─ [阶段 4] TUI 分支 ────── PAICLI_TUI=true? → TuiBootstrap 接管
  │
  │          ╔══════════════════════════════════════════════╗
  │          ║       主循环 while(true)                     ║
  │          ║                                              ║
  │          ║   ┌─── 用户输入 ────┐                        ║
  │          ║   │   readPrompt()  │                        ║
  │          ║   └───────┬────────┘                        ║
  │          ║           ▼                                  ║
  │          ║   ┌───────────────┐                          ║
  │          ║   │ parse(input)  │                          ║
  │          ║   └───────┬───────┘                          ║
  │          ║           ▼                                  ║
  │          ║   ┌────────────────┐   ┌───────────────────┐ ║
  │          ║   │ 斜杠命令 → CLI 处理│   │ 普通文本 → Agent 执行│ ║
  │          ║   │ 处理完 continue  │   │ 输出完 continue   │ ║
  │          ║   └────────────────┘   └───────────────────┘ ║
  │          ╚══════════════════════════════════════════════╝
  │                                    │
  │                                    ▼
  └─ [阶段 5] /exit 退出 ───── renderer.close() → shutdown hooks
```

---

## 面试自测题

读完本文后，你应该能秒答以下问题：

| 问题 | 答案 |
|------|------|
| Main 方法执行完后会不会再回来？ | 不会。启动时如果检测到 `serve --http`，直接 `startRuntimeApiAndBlock()`，该方法内部用 `CountDownLatch.await()` 永久阻塞 |
| 渲染器什么时候创建的？ | 阶段 2，在 `while(true)` 主循环之前。一旦创建，主循环期间不可切换 |
| 三种 Agent 模式在哪里选择？ | 主循环内，第 728-750 行的 if-else 分支。通过 `nextTaskUsePlanMode`/`nextTaskUseTeamMode` 标志 |
| 主循环怎么退出？ | 两种途径：`/exit` 命令触发 `return`，或 Ctrl+D 触发 `EndOfFileException` 跳出循环后执行 `renderer.close()` 再 `return` |
| 快捷键 ESC 取消任务怎么实现的？ | 通过 `runWithCancelSupport()` 在新线程中执行 Agent，主线程每 150ms 检查一次终端输入。读到孤立的 ESC 字节（非方向键序列）就调用 `future.cancel(true)` |
| MCP Server 在什么时候关闭？ | JVM shutdown hook（`mcpServerManager::close`），在 `main()` 返回后自动执行。不是由主循环直接触发的 |

---

## 附：Main 中使用的设计模式速查

| 模式 | 位置 | 说明 |
|------|------|------|
| **命令模式** | `CliCommandParser` + `CommandType` | 用户输入解析为命令对象 |
| **策略模式** | `Agent` / `PlanExecuteAgent` / `AgentOrchestrator` | 三种执行模式可切换 |
| **装饰器模式** | `SwitchableHitlHandler` → `TerminalHitlHandler` / `RendererHitlHandler` | 给 HITL 增加开关功能 |
| **工厂方法** | `LlmClientFactory`, `RendererFactory` | 根据环境变量/配置创建不同实现 |
| **单例（共享实例）** | `ToolRegistry`, `MemoryManager` | 三种 Agent 模式共享同一套工具和记忆 |
| **模板方法** | `AbstractOpenAiCompatibleClient` | 5 个 LLM Provider 共享通用的 OpenAI API 调用逻辑 |
| **观察者** | `LlmClient.StreamListener` | Agent 流式接收 LLM 输出的回调 |
| **适配器** | `McpTransport` → `StdioTransport` / `StreamableHttpTransport` | 统一本地和远程 MCP Server 的传输接口 |

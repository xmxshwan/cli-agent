# 第 1 步：俯瞰全局 —— 结合项目代码的逐层解析

> 配合 `learningPlan.md` 第 1 步食用，每一节都标注了"你要读的源码"和"读完之后能回答的问题"。

---

## 写在前面：阅读源码的方法

这个项目 **28 个包、182 个源文件、约 29,000 行 Java 代码**，一口气读不完。正确策略是：

1. **先读接口再读实现** —— 比如先看 `LlmClient` 接口（10 个方法），再看 `GLMClient` 实现
2. **追踪 IDE 调用链** —— 看到 `memoryManager.storeFact(...)`，右键 Find Usages 看谁调了它
3. **每读完一个模块，闭卷画流程图** —— 画不出来说明漏了关键路径
4. **带着问题读** —— 下面每节我都给了"核心问题"，读完你能回答就算通关

---

## 1.1 从入口到命令分发：Main.java → CliCommandParser

### 你要读的文件

| 阅读顺序 | 文件 | 行数 | 核心问题 |
|----------|------|------|---------|
| 1 | `cli/Main.java` | ~2500 | 启动流程？3 种模式怎么选择？ |
| 2 | `cli/CliCommandParser.java` | ~290 | 斜杠命令如何分发？ |
| 3 | `prompt/PromptAssembler.java` | ~100 | 提示词怎么逐层装配？ |

### Main.java 启动流程

```text
main() 启动

  1. 读取 .env 和 ~/.paicli/config.json 配置
  2. 通过 LlmClientFactory.createFromConfig() 创建 LLM 客户端
  3. 初始化终端 (JLine Terminal + LineReader)
  4. 创建 HITL 审批链：TerminalHitlHandler -> SwitchableHitlHandler -> HitlToolRegistry
  5. 启动 MCP Server 管理器 (McpServerManager)
  6. 初始化 Skill 系统（3 层加载：内置 / 用户 / 项目）
  7. 创建默认的 ReAct Agent
  8. 检测是否进入 TUI 模式 (PAICLI_TUI=true)
  9. 进入 CLI 主循环 while(true)
```

**代码验证** —— Main.java 第 192-260 行的关键初始化：

```java
// Main.java:203-208 — 读配置，创建 LLM 客户端
PaiCliConfig config = PaiCliConfig.load();
LlmClient llmClient = LlmClientFactory.createFromConfig(config);

// Main.java:301-304 — 创建默认 ReAct Agent，注入依赖
Agent reactAgent = new Agent(llmClient, hitlToolRegistry);
reactAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
reactAgent.setSkillRegistry(skillRegistry);
```

### CliCommandParser —— 命令分发

这是一个纯静态工具类，只有一个 `parse(String input)` 方法，返回 `ParsedCommand(CommandType, String payload)`。

**核心设计**：if-else 链，按优先级从高到低匹配。

```java
// CliCommandParser.java:56-290
static ParsedCommand parse(String input) {
    // 1. 精确匹配：/exit, /clear, /model 等
    if (trimmed.equalsIgnoreCase("/exit")) return new ParsedCommand(EXIT, null);
    if (trimmed.equalsIgnoreCase("/clear")) return new ParsedCommand(CLEAR, null);

    // 2. 前缀匹配：/model glm-5.1, /plan 写登录功能 等
    if (trimmed.regionMatches(true, 0, "/model ", 0, 7))
        return new ParsedCommand(SWITCH_MODEL, trimmed.substring(7).trim());

    // 3. 兜底：以 / 开头但未匹配任何已知命令
    if (trimmed.startsWith("/")) return new ParsedCommand(UNKNOWN_COMMAND, trimmed);

    // 4. NONE —— 普通文本，交给 LLM
    return ParsedCommand.none();
}
```

**关键洞察**：`CommandType.NONE` 是**唯一通往 LLM 的路径**。所有斜杠命令都在 CLI 层消耗，不会传到 LLM。

### 从解析到执行 —— Main.java 中的模式选择逻辑

`Main.java:379-750` 是整个项目的决策中枢：

```java
// Main.java:379 — 解析用户输入
CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);

// Main.java:386 — 匹配命令类型（38 种之一）
switch (command.type()) {
    case SWITCH_PLAN -> {
        // 设置标志：下一条任务走 Plan-and-Execute
        nextTaskUsePlanMode = true;
    }
    case SWITCH_TEAM -> {
        // 设置标志：下一条任务走 Multi-Agent
        nextTaskUseTeamMode = true;
    }
    case SWITCH_MODEL -> {
        // 动态切换 LLM Provider，新 Agent 立即生效
        LlmClient newClient = LlmClientFactory.create(target.provider(), config);
        reactAgent.setLlmClient(newClient);
    }
    case NONE -> {
        // ★ 普通文本：进入 Agent 执行
    }
}

// Main.java:728-750 — 三大模式的选择分支
Callable<String> runTask;
if (nextTaskUsePlanMode) {
    // 创建 PlanExecuteAgent，注入 ReAct Agent 的 ToolRegistry + MemoryManager
    runTask = () -> {
        PlanExecuteAgent planAgent = createPlanAgent(activeClient, reactAgent, ...);
        return planAgent.run(taskInput);
    };
} else if (nextTaskUseTeamMode) {
    // 创建 AgentOrchestrator，共享同一套 ToolRegistry
    runTask = () -> {
        AgentOrchestrator orchestrator = createTeamAgent(activeClient, reactAgent, ...);
        return orchestrator.run(taskInput);
    };
} else {
    // 默认 ReAct 模式
    runTask = () -> reactAgent.run(taskInput);
}
```

### 这是什么架构模式？

这是**命令模式 (Command Pattern) + 策略模式 (Strategy Pattern)** 的组合：

- `CliCommandParser` 将用户输入解析为 `ParsedCommand` —— 这是命令模式
- 三种 Agent 通过 `Callable<String>` 统一接口 —— 策略可以热插拔
- `ToolRegistry` 和 `MemoryManager` 通过**依赖注入**实现跨模式共享

### PromptAssembler —— 提示词装配

```java
// PromptAssembler.java:20-41
public String assemble(PromptMode mode, PromptContext context) {
    // 按固定顺序拼接各层提示词：
    append(prompt, base.md);                         // 基础系统提示
    append(prompt, personalities/calm.md);            // 人格设定
    append(prompt, mode.md);                          // 执行模式特有提示 (react/plan/team)
    append(prompt, approvals/suggest.md);              // 审批策略
    append(prompt, runtime context);                   // 当前日期+时区
    append(prompt, memory context);                    // 检索到的长期记忆
    append(prompt, skill index);                       // 启用的技能
    append(prompt, context/context-management.md);     // 上下文管理
    append(prompt, handoff.md);                        // 交接指示
}
```

**设计要点**：
- 每一层提示词是一个独立的 `.md` 文件，存在 `resources/prompt/` 下
- `PromptContext` 携带了 `memoryContext`（长期记忆）和 `externalContext`（MCP 资源索引）
- 强制要求每个 `PromptMode` 的提示词文件必须包含 `## Language` 段落（`validateLanguageSection` 在 `PromptAssembler.java:98` 做校验）

---

## 1.2 三大执行模式入口

### 你要读的文件

| 模式 | 类 | 触发方式 | 一句话理解 |
|------|-----|---------|-----------|
| ReAct | `agent/Agent.java` | 默认模式 | LLM 一步一决策的 think-act-observe 循环 |
| Plan-and-Execute | `agent/PlanExecuteAgent.java` | `/plan` 命令 | 先规划再执行，DAG 拓扑排序 |
| Multi-Agent | `agent/AgentOrchestrator.java` | `/team` 命令 | Planner + Worker + Reviewer 多角色协作 |

### ReAct 模式 —— Agent.java（项目核心！）

`Agent.java` 是整个项目中**最重要的一个文件**（约 600 行）。它的 `run()` 方法就是标准的 ReAct 循环：

```java
// Agent.java:121-240 — ReAct 核心循环
public String run(String userInput) {
    memoryManager.addUserMessage(userInput);               // 1. 存入短期记忆
    String memoryContext = memoryManager.buildContextForQuery(userInput);
    updateSystemPromptWithMemory(memoryContext);            // 2. 注入长期记忆

    while (true) {
        if (budget.check() != WITHIN_BUDGET)              // 3. budget 检查（Token 上限/死循环/轮数）
            return "❌ " + description;

        LlmClient.ChatResponse response = llmClient.chat( // 4. 调用 LLM
            conversationHistory,
            toolDefinitions,
            streamRenderer
        );

        if (response.hasToolCalls()) {
            conversationHistory.add(Message.assistant(...)); // 5. 记录 LLM 的 tool_calls
            List<ToolExecutionResult> results = executeToolCalls( // 6. 执行工具
                response.toolCalls(), iteration
            );
            for (var toolResult : results) {
                conversationHistory.add(Message.tool(...));  // 7. 结果写回历史
            }
            continue; // 回到步骤 4，让 LLM 根据工具结果继续决策
        }

        // 8. 没有 tool_calls -> 输出最终回复，结束循环
        conversationHistory.add(Message.assistant(response.content()));
        memoryManager.addAssistantMessage(response.content());
        return response.content();
    }
}
```

**面试亮点** 💡

> ReAct 循环通过 `ExecutorService` 实现了同轮多个 `tool_calls` 的并行执行（`Agent.java:executeToolCalls()` 方法），结果按原始 `tool_call` 的 `index` 顺序组装返回。这既保证了效率，又保证了 LLM 协议兼容——LLM 依赖 `index` 来关联结果。

### Plan-and-Execute —— PlanExecuteAgent.java

```java
// PlanExecuteAgent.java — 先规划后执行
// run() 方法流程：
public String run(String userInput) {
    // 1. Planner 调用 LLM 拆解任务为 DAG
    ExecutionPlan plan = planner.createPlan(userInput);

    // 2. 人机交互审核计划（按 Enter 执行、按 ESC 取消、按 I 补充）
    PlanReviewDecision decision = reviewHandler.review(goal, plan);

    // 3. 拓扑排序 -> 按依赖批次依次执行
    // 同批次无依赖的任务并行（线程池大小 = max_parallel）
    for (Batch batch : plan.topologicalBatches()) {
        for (Task task : batch.tasks()) {
            if (task.status() != DEPENDS_FAILED) {
                executorService.submit(() -> executeTask(task));
            } else {
                task.markSkipped();  // 依赖失败 -> 自动 SKIP
            }
        }
    }
}
```

### Multi-Agent —— AgentOrchestrator.java

```java
// AgentOrchestrator.java:42 — Multi-Agent 协作架构
public class AgentOrchestrator {
    private final SubAgent planner;   // 任务规划者
    private final List<SubAgent> workers; // 执行者（池化，最大并发 2）
    private final SubAgent reviewer;  // 质量审核者

    public String run(String userInput) {
        // 1. Planner 将任务拆成 ExecutionStep 列表（带依赖）
        List<ExecutionStep> steps = planner.plan(userInput);

        // 2. 按依赖批次执行，同批次并行
        for (Batch batch : topologicalSort(steps)) {
            // 并行执行同批次步骤
            List<Future<StepResult>> futures = new ArrayList<>();
            for (ExecutionStep step : batch.steps()) {
                Worker worker = borrowWorker();
                futures.add(executor.submit(() -> worker.execute(step)));
            }

            // 3. 每个步骤执行完 -> Reviewer 审核
            for (Future<StepResult> future : futures) {
                StepResult result = future.get();
                ReviewVerdict verdict = reviewer.review(result);
                if (verdict == FAILED && retries < MAX_RETRIES_PER_STEP) {
                    // 重试：带上 Reviewer 反馈再执行一次
                }
            }
        }
    }
}
```

### 三种模式的核心对比

| 维度 | ReAct | Plan-and-Execute | Multi-Agent |
|------|-------|-----------------|-------------|
| 决策方式 | LLM 每步自决策 | LLM 先规划再执行 | 多 LLM 角色协作 |
| 并行能力 | 同轮 tool_calls 并行 | 同批次子任务并行 | Worker 池化并行 |
| 容错机制 | 循环内重试 | 下游任务自动 SKIP | Reviewer 驱动重试 |
| 适用场景 | 简单/开放任务 | 复杂多步骤任务 | 需要质量审查的任务 |

**最关键的理解：三种模式共享 `ToolRegistry` 和 `MemoryManager`，区别只在"决策方式"。**

代码验证 —— 从 Main.java 看共享关系：

```java
// Main.java:844-870
static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Agent reactAgent, ...) {
    return new PlanExecuteAgent(
        llmClient,
        reactAgent.getToolRegistry(),   // ★ 共享同一个 ToolRegistry
        reactAgent.getMemoryManager(),  // ★ 共享同一个 MemoryManager
        ...
    );
}

static AgentOrchestrator createTeamAgent(LlmClient llmClient, Agent reactAgent, ...) {
    return new AgentOrchestrator(
        llmClient,
        reactAgent.getToolRegistry(),   // ★ 共享
        reactAgent.getMemoryManager(),  // ★ 共享
        ...
    );
}
```

---

## 1.3 Tool 与 LLM 的桥梁

### 你要读的文件

| 阅读顺序 | 文件 | 行数 | 核心问题 |
|----------|------|------|---------|
| 1 | `tool/ToolRegistry.java` | ~900 | 工具怎么注册、怎么被 LLM 调用？ |
| 2 | `llm/LlmClient.java` (接口) | ~210 | 多模型适配层怎么抽象的？ |
| 3 | `llm/LlmClientFactory.java` | 去看 | 如何根据配置切换 Provider？ |

### LlmClient 接口 —— 多模型统一抽象

```java
// LlmClient.java:9-15 — 只有 2 个核心方法
public interface LlmClient {
    // 非流式调用
    ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException;
    // 流式调用（带 StreamListener 回调）
    ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException;
```

这个接口有 5 个实现：
- `GLMClient` —— 智谱 GLM 系列
- `DeepSeekClient` —— DeepSeek 系列
- `StepClient` —— 阶跃星辰
- `KimiClient` —— Moonshot AI
- `FreeLlmApiClient` —— 兼容 OpenAI API 的本地服务

所有实现都继承 `AbstractOpenAiCompatibleClient`（因为 5 家都兼容 OpenAI API 格式）。

**内部数据结构定义**（都在 `LlmClient.java` 内部）：

```java
record Message(String role, String content, ...);       // system/user/assistant/tool
record ToolCall(String id, Function function);           // LLM 返回的工具调用
record Tool(String name, String description, JsonNode parameters);  // 发给 LLM 的工具定义
record ChatResponse(String role, String content, List<ToolCall> toolCalls, ...);
record ContentPart(String type, String text, String imageBase64, ...);
```

### ToolRegistry —— 工具注册表

`ToolRegistry.java:59` 是整个工具系统的核心。它负责三件事：

**1. 注册工具** —— 内置 9 个工具在构造时注册，MCP 工具在运行时动态注入

```java
// ToolRegistry 初始化时注册 9 个内置工具（搜索 registerBuiltin 或类似方法）
1. read_file      — 读取文件内容
2. write_file     — 写入文件
3. list_dir       — 列出目录
4. glob_files     — 模式匹配文件
5. grep_code      — 正则搜索代码
6. execute_command — 执行命令
7. create_project — 创建项目
8. search_code    — RAG 语义搜索
9. revert_turn    — 回退上一步
```

**2. 提供给 LLM** —— `getToolDefinitions()` 将所有工具转为 LLM 可识别的 `Tool` 列表（含 JSON Schema）

**3. 执行工具** —— `executeToolCalls()` 反序列化 tool_calls 并调用对应实现

MCP 工具的命名规则是 `mcp__{serverName}__{toolName}`，在 `getToolDefinitions()` 中与内置工具一起返回给 LLM。

---

## 1.4 输出渲染

### 渲染器架构

```
Renderer (接口)
  ├── InlineRenderer  (默认) — 行内流式，Claude Code 风格
  ├── PlainRenderer   — 纯 println 兜底
  └── LanternaRenderer — 全屏 TUI（向后兼容通过 PAICLI_TUI=true 启用）
```

选型逻辑（`RendererFactory.java:32-48`）：

```java
// 优先级：系统属性 > 环境变量 > 默认 inline
System.getProperty("paicli.renderer") -> inline / lanterna / plain
System.getenv("PAICLI_RENDERER")      -> 同上
System.getenv("PAICLI_TUI")           -> 兼容旧配置：true → lanterna
默认                                   -> inline
```

### Main.java:256-263

```java
Renderer renderer = RendererFactory.create(RendererFactory.resolveMode(), terminal);
if (renderer instanceof InlineRenderer inline) {
    inline.bindLineReader(lineReader);  // inline 模式需要绑定 JLine 行读取器
}
renderer.start();
renderer.updateStatus(statusInfo(llmClient, hitlHandler, "idle", mcpServerManager, null));
```

---

## 1.5 你学完这里后应该能画出的架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        Main.java (主循环)                       │
│  while(true) {                                                  │
│    1. 终端输入 -> CliCommandParser.parse()                       │
│    2. switch(command.type)                                       │
│       ├── 斜杠命令 -> CLI 自身处理（模式切换/模型切换/记忆管理等）   │
│       └── 普通文本 -> 进入 Agent 执行                             │
│  }                                                               │
└──────────────┬──────────────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────────────┐
│                   三大执行模式 (共享依赖)                         │
│                                                                  │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │
│  │  ReAct Agent    │  │ PlanExecuteAgent │  │AgentOrchestrator│  │
│  │  (默认)         │  │ (/plan)         │  │ (/team)         │  │
│  │                 │  │                 │  │                 │  │
│  │ think-act-loop  │  │ plan→exec→merge │  │ plan→worker→    │  │
│  │                 │  │                 │  │ review          │  │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘  │
│           │                    │                    │           │
│           └──────────┬─────────┴─────────┬──────────┘           │
│                      │                   │                      │
│                      ▼                   ▼                      │
│           ┌──────────────────┐  ┌──────────────────┐           │
│           │   ToolRegistry   │  │  MemoryManager   │           │
│           │  (9 内置 + MCP)  │  │ (三層記憶)        │           │
│           └────────┬─────────┘  └────────┬─────────┘           │
│                    │                     │                      │
└────────────────────┼─────────────────────┼──────────────────────┘
                     │                     │
                     ▼                     ▼
┌─────────────────────────────────────────────────────────────────┐
│                   基础服务层                                      │
│                                                                  │
│  LLM: LlmClient ← GLMClient / DeepSeekClient / StepClient / ... │
│  MCP: McpServerManager → StdioTransport / StreamableHttpTransport│
│  RAG: CodeChunker → EmbeddingClient → VectorStore (SQLite)       │
│  HITL: HitlToolRegistry → TerminalHitlHandler / RendererHitl     │
│  Skill: SkillRegistry (3层加载) → SkillContextBuffer 注入       │
│  渲染: InlineRenderer / PlainRenderer / LanternaRenderer        │
│  安全: PathGuard + CommandGuard + AuditLog + PolicyException     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 1.6 读完这一步你应该能回答的问题

| 问题 | 答案在哪里 |
|------|-----------|
| 用户输入 `/model glm-5.1` 发生了什么？ | `CliCommandParser` 返回 `SWITCH_MODEL` → `Main.java:491` 调用 `LlmClientFactory.create("glm", config)` |
| 普通文本（如"实现登录功能"）流向哪里？ | `CliCommandParser.parse()` 返回 `NONE` → `Main.java:748` `reactAgent.run(input)` |
| 三种 Agent 模式怎么共享 ToolRegistry？ | Main 中 ReAct Agent 持有 ToolRegistry，`createPlanAgent`/`createTeamAgent` 通过 `reactAgent.getToolRegistry()` 传入 |
| /plan 和 /team 在执行完一条任务后会自动切回 ReAct 吗？ | 会。`nextTaskUsePlanMode` 和 `nextTaskUseTeamMode` 每轮执行完后重置为 `false`（`Main.java:759-760`） |
| 为什么提示词里要强制有 "## Language" 段落？ | `PromptAssembler.validateLanguageSection()` 在装配后做校验，不存在就抛 `IllegalStateException` |
| 渲染器怎么选型的？ | `RendererFactory.resolveMode()`：系统属性 > 环境变量 > 默认 inline |

---

## 总结：第 1 步的核心收获

1. **入口理解**：`Main.java` 不只是个启动类，它是整个应用的**路由中心**——管理初始化、命令分发、模式切换、MCP 生命周期
2. **三种模式的关系**：ReAct 是默认/基础，Plan 和 Team 是增强策略，共享底层依赖
3. **依赖注入模式**：`ToolRegistry`、`MemoryManager` 是"全局单例"，通过构造注入到所有 Agent 中
4. **插件化扩展点**：LLM Provider（`LlmClient` 接口）、MCP 工具（`McpTransport` 接口）、渲染器（`Renderer` 接口）都是策略模式设计

完成这一步，你已经对 PaiCLI 有了**全局地图**。下一步（第 2.1 节 MCP 集成）就可以深入具体模块的代码细节了。

---

## 附录：自己动手验证理解

### 练习 1：追踪一次完整的用户请求

1. 从 `Main.java` 的 `main()` 找到 `while(true)` 循环
2. 在 `CliCommandParser.parse()` 设断点（或用 Find Usages 追踪）
3. 沿着 `command.type() == NONE` 的路径，找到 `reactAgent.run()`
4. 进入 `Agent.run()` 观察 ReAct 循环的完整流程

### 练习 2：画出模块依赖图

打开画图工具（或直接手绘），把下面 8 个模块的位置和连线画出来：
`cli → prompt → agent → llm → tool → mcp → memory → render`

每一条连线代表一个**构造注入**或**方法调用**。画完后对照本文的架构图，看漏了谁。

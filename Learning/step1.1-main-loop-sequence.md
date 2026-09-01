# 第 2 步：主循环时序详解 —— 用户输入到 Agent → MCP → Tools 的完整链路

> 接 `step1-main-flow-explained.md`，本文深入 `Main.java` 阶段 4 的 `while(true)` 主循环，
> 逐帧追踪一条用户输入如何依次流过 CLI 命令分发、Agent ReAct 循环、ToolRegistry 路由，
> 最终到达 MCP Server 或内置工具的完整过程。

---

## 全景时序图

```
时间 →
─────

用户                      Main.java 主循环                  Agent (ReAct)              ToolRegistry              MCP Server / 本地工具
 │                           │                              │                          │                          │
 │  输入 "实现二分查找"        │                              │                          │                          │
 ├──────────────────────────►│                              │                          │                          │
 │                           │                              │                          │                          │
 │                           │  1. readPromptInput()        │                          │                          │
 │                           │     JLine LineReader 读取     │                          │                          │
 │                           │                              │                          │                          │
 │                           │  2. CliCommandParser.parse()  │                          │                          │
 │                           │     → CommandType.NONE       │                          │                          │
 │                           │     (普通文本)                │                          │                          │
 │                           │                              │                          │                          │
 │                           │  3. @引用展开                 │                          │                          │
 │                           │     mentionExpander.expand()  │                          │                          │
 │                           │     localPathMention...()     │                          │                          │
 │                           │                              │                          │                          │
 │                           │  4. 选执行模式                │                          │                          │
 │                           │     ┌──────────────┐         │                          │                          │
 │                           │     │ ReAct (默认)  │         │                          │                          │
 │                           │     │ Plan (/plan)  │         │                          │                          │
 │                           │     │ Team (/team)  │         │                          │                          │
 │                           │     └──────────────┘         │                          │                          │
 │                           │                              │                          │                          │
 │                           │  5. snapshotService          │                          │                          │
 │                           │     .runTurn()               │                          │                          │
 │                           │                              │                          │                          │
 │                           │  6. runWithCancelSupport()    │                          │                          │
 │                           │     在新线程中执行 Agent      │                          │                          │
 │                           │     主线程每 150ms 监听 ESC   │                          │                          │
 ├ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┤  (ESC 取消检测)              │                          │                          │
 │                           │                              │                          │                          │
 │                           ├─────────────────────────────►│                          │                          │
 │                           │  reactAgent.run(taskInput)   │                          │                          │
 │                           │                              │                          │                          │
 │                           │                              │  7. 构建 system prompt    │                          │
 │                           │                              │     PromptAssembler       │                          │
 │                           │                              │     → base.md             │                          │
 │                           │                              │     → personalities/      │                          │
 │                           │                              │     → memory context     │                          │
 │                           │                              │     → skill index        │                          │
 │                           │                              │     → 当前日期+时区      │                          │
 │                           │                              │     → 上下文管理提示     │                          │
 │                           │                              │                          │                          │
 │                           │                              │  8. ReAct 循环开始       │                          │
 │                           │                              │     ┌──────────────┐     │                          │
 │                           │                              │     │ while(true)  │     │                          │
 │                           │                              │     └──────┬───────┘     │                          │
 │                           │                              │            │             │                          │
 │                           │                              │  9. budget.check()       │                          │
 │                           │                              │     (Token/死循环/轮数)   │                          │
 │                           │                              │            │             │                          │
 │                           │                              │  10. 调用 LLM            │                          │
 │                           │                              │  llmClient.chat(         │                          │
 │                           │                              │    conversationHistory,  │                          │
 │                           │                              │    toolDefinitions)      │                          │
 │                           │                              │      │                   │                          │
 │                           │                              │      │ ←─ tool_defs ──   │                          │
 │                           │                              │      │    (9 内置 + MCP)  │                          │
 │                           │                              │      │                   │                          │
 │                           │                              │      ▼                   │                          │
 │                           │                              │  LLM 返回:              │                          │
 │                           │                              │  "我需要创建文件..."     │                          │
 │                           │                              │  + tool_calls:           │                          │
 │                           │                              │    [write_file           │                          │
 │                           │                              │     (path, content),     │                          │
 │                           │                              │     execute_command      │                          │
 │                           │                              │     (npm install)]       │                          │
 │                           │                              │                          │                          │
 │                           │                              │  11. 记录到对话历史      │                          │
 │                           │                              │                          │                          │
 │                           │                              │  12. executeToolCalls()  │                          │
 │                           │                              ├─────────────────────────►│                          │
 │                           │                              │  toolRegistry            │                          │
 │                           │                              │  .executeTools()         │                          │
 │                           │                              │                          │                          │
 │                           │                              │                          │  ┌─────────────────────┐ │
 │                           │                              │                          │  │ 并行执行同轮工具     │ │
 │                           │                              │                          │  │ (线程池)            │ │
 │                           │                              │                          │  └─────────────────────┘ │
 │                           │                              │                          │                          │
 │                           │                              │                          │  [工具1: write_file]   │
 │                           │                              │   ┌──────────────────┐   │                          │
 │                           │                              │   │ HitlToolRegistry  │   │                          │
 │                           │                              │   │ (如果是 HITL 路径) │   │                          │
 │                           │                              │   │                   │   │                          │
 │                           │                              │   │ ApprovalPolicy    │   │                          │
 │                           │                              │   │ .requiresApproval │   │                          │
 │                           │                              │   │ ("write_file")    │   │                          │
 │                           │                              │   │ → true 🟡         │   │                          │
 │                           │                              │   └────────┬─────────┘   │                          │
 │                           │                              │            │             │                          │
 │                           │                              │            ▼             │                          │
 │                           │                              │   ┌──────────────────┐   │                          │
 │                           │  ┌──── 弹审批框 ────┐         │   │ requestApproval() │   │                          │
 │                           │  │ [y]批准 [a]全部  │◄────────│   │ TerminalHitl      │   │                          │
 │                           │  │ [n]拒绝 [s]跳过  │         │   │ Handler           │   │                          │
 │                           │  │ [m]修改参数       │         │   └────────┬─────────┘   │                          │
 │                           │  └───────┬──────────┘         │            │             │                          │
 │                           │          │                    │            ▼             │                          │
 │                           │          │ y 批准              │   ┌──────────────────┐   │                          │
 │                           │          ├────────────────────►│   │ doExecuteTool()  │   │                          │
 │                           │          │                    │   └────────┬─────────┘   │                          │
 │                           │          │                    │            │             │                          │
 │                           │          │                    │            ▼             │                          │
 │                           │          │                    │    ┌───────────────┐     │                          │
 │                           │          │                    │    │ 检查 mcpTools │     │                          │
 │                           │          │                    │    │ 表:           │     │                          │
 │                           │          │                    │    │               │     │                          │
 │                           │          │                    │    │ write_file 是  │     │                          │
 │                           │          │                    │    │ 内置工具吗?    │     │                          │
 │                           │          │                    │    │ → YES (tools) │     │                          │
 │                           │          │                    │    └───────┬───────┘     │                          │
 │                           │          │                    │            │             │                          │
 │                           │          │                    │            ▼             │                          │
 │                           │          │                    │    JsonNode args =      │                          │
 │                           │          │                    │    mapper.readTree()    │                          │
 │                           │          │                    │    tool.executor()      │                          │
 │                           │          │                    │    .execute(argMap)     │                          │
 │                           │          │                    │            │             │                          │
 │                           │          │                    │            ▼             │                          │
 │                           │          │                    │   ← "文件已写入 ✔️"      │                          │
 │                           │          │                    │                          │                          │
 │                           │          │                    │  [工具2: execute_command]│                          │
 │                           │          │                    │   ┌──────────────────┐   │                          │
 │                           │          │                    │   │ PathGuard       │   │                          │
 │                           │          │                    │   │ CommandGuard    │   │                          │
 │                           │          │                    │   │ (策略检查)       │   │                          │
 │                           │          │                    │   └──────────────────┘   │                          │
 │                           │          │                    │            │             │                          │
 │                           │          │                    │            ▼             │                          │
 │                           │          │                    │   检查 mcpTools 表:      │                          │
 │                           │          │                    │   → 不是内置，不是 MCP    │                          │
 │                           │          │                    │   → 抛错 "未知工具"      │                          │
 │                           │          │                    │                          │                          │
 │                           │          │                    │   [工具3: MCP 工具]      │                          │
 │                           │          │                    │   mcp__chrome-devtools   │                          │
 │                           │          │                    │   __navigate             │                          │
 │                           │          │                    │            │             │                          │
 │                           │          │                    │            ▼             │                          │
 │                           │          │                    │   检查 mcpTools 表:      │                          │
 │                           │          │                    │   → YES!                 │                          │
 │                           │          │                    │   mcpTool.invoker()      │                          │
 │                           │          │                    │   .apply(argumentsJson)  ├─────────────────────────►
 │                           │          │                    │                          │  MCP Client 发送请求    │
 │                           │          │                    │                          │  (Stdio or HTTP)        │
 │                           │          │                    │                          │                          │
 │                           │          │                    │                          │  MCP Server 返回结果    │
 │                           │          │                    │  ← 导航到 example.com ✔️ ◄─────────────────────────┤
 │                           │          │                    │                          │                          │
 │                           │          │                    │  ← 审计日志写入           │                          │
 │                           │          │                    │                          │                          │
 │                           │          │                    │  结果按原始顺序返回       │                          │
 │                           │          │                    ├──────────────────────────►│                          │
 │                           │          │                    │                          │                          │
 │                           │          │                   ◄──────────────────────────┤                          │
 │                           │          │                    │                          │                          │
 │                           │          │  13. 工具结果写回历史                         │                          │
 │                           │          │      conversationHistory.add(tool result)     │                          │
 │                           │          │      memoryManager.addToolResult()            │                          │
 │                           │          │                          │                   │                          │
 │                           │          │  14. continue 回到步骤 8                      │                          │
 │                           │          │     (让 LLM 根据工具结果继续)                  │                          │
 │                           │          │                          │                   │                          │
 │                           │          │  15. 再次调用 LLM         │                   │                          │
 │                           │          │     (对话历史包含工具结果)  │                   │                          │
 │                           │          │                          │                   │                          │
 │                           │          │  LLM 返回: "已完成...   " │                   │                          │
 │                           │          │  (没有 tool_calls)        │                   │                          │
 │                           │          │                          │                   │                          │
 │                           │          │  16. 输出最终结果         │                   │                          │
 │                           │          │      memoryManager        │                   │                          │
 │                           │          │      .addAssistantMessage()                   │                          │
 │                           │          │                          │                   │                          │
 │                           │◄───────────────────────────────────┤                   │                          │
 │                           │          │                          │                   │                          │
 │                           │  17. response 不为空 → 打印         │                   │                          │
 │                           │      ui.println(response)           │                   │                          │
 ├──────────────────────────◄│          │                          │                   │                          │
 │   输出显示在终端            │          │                          │                   │                          │
 │                           │          │                          │                   │                          │
 │                           │  18. 重置模式标志                   │                   │                          │
 │                           │      nextTaskUsePlanMode = false    │                   │                          │
 │                           │      nextTaskUseTeamMode = false    │                   │                          │
 │                           │          │                          │                   │                          │
 │                           │  19. 回到 while(true) 开头          │                   │                          │
 │                           │      等待下一次输入                  │                   │                          │
```

---

## 分步代码溯源

下面每一步都标注了对应的代码位置。

### 第 1 步：输入读取

**Main.java:370-398** — `readPromptInput()`

```java
PromptInput promptInput;
try {
    promptInput = readPromptInput(terminal, lineReader, renderer,
            nextTaskUsePlanMode || nextTaskUseTeamMode, spaciousPrompt);
} catch (UserInterruptException e) {
    continue;  // Ctrl+C → 跳过
} catch (EndOfFileException e) {
    break;     // Ctrl+D → 退出
}
```

内部调用 JLine 的 `lineReader.readLine()`，支持：

- 历史记录（↑/↓）
- Tab 补全（斜杠命令 / `@` MCP 资源引用）
- 语法高亮
- ESC 预读（取消待执行的 Plan/Team 模式）

### 第 2 步：输入解析与命令分发

**Main.java:400-741** — `CliCommandParser.parse()`

```java
CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);

switch (command.type()) {
    // 38 种命令类型，每种的处理逻辑
    case EXIT, CLEAR, SWITCH_MODEL, MEMORY_LIST, MCP_LIST, SKILL_LIST, ...
    case NONE -> {
        // ★ 普通文本：唯一通往 LLM 的路径
    }
}
```

所有斜杠命令（`/exit`, `/model`, `/plan`, `/memory list` 等）都在 CLI 层直接处理，
**不让 LLM 知道**。只有 `NONE` 才进入 Agent 执行。

### 第 3 步：@引用展开

**Main.java:745-746**

```java
input = mentionExpander.expand(input);              // 展开 MCP 资源 @引用
input = localPathMentionExpander.expand(input);      // 展开本地文件 @引用
```

例如：用户输入 `@src/main/java/App.java 是什么意思？` →
将文件内容读取后拼接到 prompt 中，LLM 直接看到文件内容。

### 第 4 步：执行模式选择

**Main.java:757-779** — 三种 Agent 模式

```java
Callable<String> runTask;
if (nextTaskUsePlanMode) {
    // Plan-and-Execute：先规划再执行（DAG 拓扑排序）
    snapshotMode = "plan";
    runTask = () -> {
        PlanExecuteAgent planAgent = createPlanAgent(activeClient, reactAgent, terminal, lineReader, ui);
        return planAgent.run(taskInput);
    };
} else if (nextTaskUseTeamMode) {
    // Multi-Agent：Planner + Worker + Reviewer 多角色协作
    snapshotMode = "team";
    runTask = () -> {
        AgentOrchestrator orchestrator = createTeamAgent(activeClient, reactAgent, ui);
        return orchestrator.run(taskInput);
    };
} else {
    // ReAct：默认模式，LLM 一步一决策
    snapshotMode = "react";
    runTask = () -> reactAgent.run(taskInput);
}
```

**设计要点**：三种模式共享同一套 `ToolRegistry` 和 `MemoryManager`（从 `reactAgent` 中取出传入），
区别只在决策方式。

### 第 5 步：Snapshot 包装

**Main.java:782-784**

```java
SnapshotService snapshotService = reactAgent.getToolRegistry().getSnapshotService();
String response = runWithCancelSupport(terminal, ui,
    () -> snapshotService.runTurn(snapshotMode, taskInput, runTask::call));
```

执行前自动做 Git 快照（`pre-turn`），执行后再做一次快照（`post-turn`），
支持 `/restore N` 回滚操作。

### 第 6 步：ESC 取消支持

**Main.java:901-954** — `runWithCancelSupport()`

```java
// 新线程执行 Agent
Future<String> future = executor.submit(task);

// 主线程进入 raw mode，每 150ms 轮询一次 ESC 键
while (!future.isDone()) {
    if (readEscCancel(terminal)) {    // 检测到孤立的 ESC
        token.cancel();
        future.cancel(true);           // 中断 Agent 线程
        return "⏹️ 已取消当前任务。";
    }
    return future.get(150, TimeUnit.MILLISECONDS);
}
```

**readEscCancel()** 通过 `classifyEscapeSequence()` 区分：

- `STANDALONE_ESC`（孤立 ESC）→ 真正取消
- `CONTROL_SEQUENCE`（ESC + `[` + 字符）→ 方向键/Home/End，不取消

---

## Agent.run() 内部 —— ReAct 循环

**Agent.java:121-251**

```java
public String run(String userInput) {
    // 存入短期记忆
    memoryManager.addUserMessage(userInput);

    // 检索长期记忆并注入 system prompt
    String memoryContext = memoryManager.buildContextForQuery(...);
    updateSystemPromptWithMemory(memoryContext);

    // 添加到对话历史
    conversationHistory.add(ImageReferenceParser.userMessage(userInput, ...));

    while (true) {
        // ① Budget 检查（Token 上限 / 是否有工具调用 / 硬轮数）
        AgentBudget.ExitReason exitReason = budget.check();
        if (exitReason != WITHIN_BUDGET) return "❌ " + description;

        int iteration = budget.beginIteration();

        // ② 获取完整的 tool definitions（9 内置 + 所有 MCP 工具）
        List<LlmClient.Tool> toolDefinitions = toolRegistry.getToolDefinitions();

        // ③ 调用 LLM（含 streaming 渲染）
        LlmClient.ChatResponse response = llmClient.chat(
                conversationHistory, toolDefinitions, streamRenderer);

        budget.recordTokens(response.inputTokens(), response.outputTokens(), ...);

        if (response.hasToolCalls()) {
            // ④ 记录 LLM 的 tool_calls
            conversationHistory.add(Message.assistant(..., response.toolCalls()));

            // ⑤ 执行工具（核心！）
            List<ToolExecutionResult> toolResults = executeToolCalls(response.toolCalls(), iteration);

            // ⑥ 结果写回历史
            for (var toolResult : toolResults) {
                conversationHistory.add(Message.tool(toolResult.id(), toolResult.result()));
            }

            continue; // 回到 ①，让 LLM 根据工具结果继续决策
        }

        // ⑦ 没有 tool_calls → 输出最终回复，结束循环
        conversationHistory.add(Message.assistant(response.content()));
        memoryManager.addAssistantMessage(response.content());
        return response.content();
    }
}
```

**ReAct 循环的本质**：

```text
┌───────────────────────────────────────────────────────┐
│                  ReAct 循环                             │
│                                                       │
│  Think → Act → Observe → Think → Act → Observe → ... │
│                                                       │
│  ① LLM 思考（Chat 调用）                                │
│  ② LLM 决定调用工具（tool_calls）                        │
│  ③ 执行工具（executeToolCalls）                          │
│  ④ 结果写回历史（LLM 看到工具结果）                       │
│  ⑤ 回到 ①                                              │
│                                                       │
│  退出条件：LLM 不再调用工具，直接输出最终文本               │
└───────────────────────────────────────────────────────┘
```

---

## ToolRegistry 内部 —— 工具执行路由

### executeToolCalls → executeTools → doExecuteTool

**Agent.java:617-642** → **ToolRegistry.java:1196-1265** → **ToolRegistry.java:1111-1167**

```text
Agent.executeToolCalls()
  │
  ├── 将 ToolCall → ToolInvocation（解构 JSON 参数）
  │
  ├── toolRegistry.executeTools(invocations)
  │    │
  │    ├── 单工具 → 直接调用 executeToolOutput()
  │    │
  │    ├── 多工具 → 线程池并行执行
  │    │    │         (MAX_PARALLEL_TOOLS 控制并发度)
  │    │    │         (toolBatchTimeoutSeconds 超时兜底)
  │    │    │
  │    │    └── 结果按原始顺序返回（LLM 依赖 index 关联结果）
  │    │
  │    └── (如果 HitlToolRegistry，先审批再执行)
  │
  └── 结果返回 Agent → 写回 conversationHistory
```

### doExecuteTool —— 最终路由

**ToolRegistry.java:1111-1167**

```java
protected ToolOutput doExecuteTool(String name, String argumentsJson) {
    Tool tool = tools.get(name);          // 先从 tools 表查

    // ① 优先查 MCP 工具表
    McpRegisteredTool mcpTool = mcpTools.get(name);
    if (mcpTool != null) {
        // MCP 工具：通过 invoker 发送给 MCP Server
        ToolOutput output = mcpTool.invoker().apply(argumentsJson);
        // 写审计日志
        auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, ...));
        return output;
    }

    // ② 非 MCP → 查内置工具表
    JsonNode args = mapper.readTree(argumentsJson);
    Map<String, String> argMap = ...;
    String result = tool.executor().execute(argMap);
    return ToolOutput.text(result);
}
```

### 工具查找顺序

```text
doExecuteTool(name)
  │
  ├── 1. mcpTools 表（HashMap）
  │      key = "mcp__chrome-devtools__navigate"
  │      │
  │      ├── 命中 → 调用 MCP Client → 返回结果
  │      │
  │      └── 未命中 → 继续
  │
  └── 2. tools 表（HashMap，内置工具）
         key = "write_file"
         │
         ├── 命中 → 调用 Java 实现
         │
         └── 未命中 → 返回 "未知工具: xxx"
```

---

## 内置工具 vs MCP 工具对比

| 维度    | 内置工具                                                            | MCP 工具                          |
| ----- | --------------------------------------------------------------- | ------------------------------- |
| 注册时机  | `ToolRegistry` 构造时硬编码注册                                         | 运行时通过 `registerMcpTool()` 动态注入  |
| 存储    | `tools` 表 (HashMap)                                             | `mcpTools` 表 (HashMap)          |
| 查找优先级 | 两个表都查，先查 mcpTools                                               | 先查 mcpTools                     |
| 命名    | `write_file`, `read_file`, `execute_command`...                 | `mcp__{serverName}__{toolName}` |
| 执行    | 直接调用 Java 方法                                                    | 通过 MCP Client 发送给外部进程           |
| 审计    | 仅 `write_file`/`execute_command`/`create_project`/`revert_turn` | 所有 `mcp__*` 都审计                 |
| HITL  | 以上 4 个需审批                                                       | 全部 `mcp__*` 都需审批                |

### 9 个内置工具列表

| 工具名               | 功能          | 危险等级  |
| ----------------- | ----------- | ----- |
| `read_file`       | 读取文件        | 🟢 安全 |
| `write_file`      | 写入/覆盖文件     | 🟡 中危 |
| `list_dir`        | 列出目录        | 🟢 安全 |
| `glob_files`      | 模式匹配文件      | 🟢 安全 |
| `grep_code`       | 正则搜索代码      | 🟢 安全 |
| `execute_command` | 执行 Shell 命令 | 🔴 高危 |
| `create_project`  | 创建项目目录      | 🟡 中危 |
| `search_code`     | RAG 语义搜索    | 🟢 安全 |
| `revert_turn`     | 回退上一步       | 🔴 高危 |

---

## MCP 工具注入流程

MCP 工具的注册发生在 `McpServerManager.startAll()` 时：

```text
MCP Server 启动流程：
─────────────────

Main.java:287-289
mcpServerManager.loadConfiguredServers();     ← 读取 ~/.paicli/mcp.json
mcpServerManager.startAll(ui, waitDuration);   ← 启动所有 MCP Server
  │
  ├── 遍历 mcp.json 中的每个 server 配置
  │
  ├── 根据 transport 类型创建客户端:
  │   ├── "command" + "args" → StdioTransport（子进程 stdin/stdout）
  │   └── "url"             → StreamableHttpTransport（HTTP + SSE）
  │
  ├── 发送 initialize 请求 → 获取 tools/resources 列表
  │
  └── 回调 ToolRegistry.registerMcpTool()
        │
        └── mcpTools.put("mcp__{serverName}__{toolName}", registered)
             │
             └── invoker 持有 MCP Client 的调用句柄
                 执行时 → 序列化参数 → 发送请求 → 等待响应 → 返回结果
```

工具定义（发给 LLM 的 JSON Schema）也在此过程中构建：

```java
// ToolRegistry.getToolDefinitions() 会合并：
// 1. 内置工具的 JSON Schema（硬编码）
// 2. MCP 工具的 JSON Schema（来自 MCP Server 的 initialize 响应）
// 返回 List<Tool> → 作为 tool_use 参数传给 LLM
```

---

## HITL 审批在工具调用链中的位置

**HitlToolRegistry.java:35-54** — 覆写 `executeToolOutput()` 插入审批：

```text
toolRegistry.executeTools(invocations)
  │
  ├── executeToolOutput(name, argumentsJson)
  │    │
  │    ├── HitlToolRegistry 版本（如果启用 HITL）
  │    │    │
  │    │    ├── ApprovalPolicy.requiresApproval(name)?
  │    │    │    ├── write_file / execute_command / create_project / revert_turn
  │    │    │    │   → 需要审批 🟡
  │    │    │    └── read_file / list_dir / mcp__* 查表规则
  │    │    │
  │    │    ├── hitlHandler.isApprovedAllByTool(name)?
  │    │    │    ├── 用户之前按过 "a"（全部放行）? → 自动通过
  │    │    │    └── 否 → 弹审批框
  │    │    │
  │    │    ├── hitlHandler.requestApproval(request)
  │    │    │    ├── y/Enter → 批准 → doExecuteTool()
  │    │    │    ├── a      → 全部放行 → 记录到 approvedAllByTool
  │    │    │    ├── n      → 拒绝 → 写审计日志 → 返回拒绝消息
  │    │    │    ├── s      → 跳过 → 返回跳过消息
  │    │    │    └── m      → 修改参数 → JSON 校验 → 执行修改后的参数
  │    │    │
  │    │    └── doExecuteTool(name, effectiveArgs)
  │    │
  │    └── ToolRegistry 原始版本（如果未启用 HITL）
  │         → 直接 doExecuteTool()
  │
  └── 结果返回
```

---

## 安全策略检查（doExecuteTool 内部）

在执行工具之前，还有一层安全策略检查：

```text
doExecuteTool()
  │
  ├── PathGuard（路径围栏）
  │   └── write_file / read_file 的路径是否在项目根内？
  │       └── 否 → PolicyException → "策略拒绝: 路径越界"
  │
  ├── CommandGuard（命令黑名单）
  │   └── execute_command 是否在黑名单内？
  │       (sudo / rm -rf / mkfs / dd / fork bomb / curl|sh / ...)
  │       └── 是 → PolicyException → "策略拒绝: 命令被拦截"
  │
  ├── 文件大小上限（5MB）
  │   └── write_file 的内容是否超过 5MB？
  │       └── 是 → PolicyException → "策略拒绝: 文件过大"
  │
  └── 通过 → 实际执行
```

---

## 完整调用链总结

```text
用户输入
  │
  ▼
Main.java while(true) 主循环
  │
  ├── 1. readPromptInput()           (JLine LineReader)
  ├── 2. CliCommandParser.parse()    (CommandType.NONE 才继续)
  ├── 3. mentionExpander.expand()    (@引用展开)
  ├── 4. 选择 Agent 模式             (ReAct / Plan / Team)
  ├── 5. snapshotService.runTurn()   (Git 快照包装)
  └── 6. runWithCancelSupport()      (ESC 取消支持)
        │
        ▼
  Agent.run()
        │
        ├── 7. 构建 system prompt    (PromptAssembler + Memory + Skill)
        │
        ├── 8-16. ReAct 循环
        │     │
        │     ├── budget.check()     (Token/死循环/轮数)
        │     ├── llmClient.chat()   (调用 LLM)
        │     │
        │     ├── LLM 返回 tool_calls?
        │     │   │
        │     │   ├── Yes → executeToolCalls()
        │     │   │         │
        │     │   │         ├── ToolRegistry.executeTools()
        │     │   │         │     │
        │     │   │         │     ├── HitlToolRegistry 审批?
        │     │   │         │     │   ├── [y] 批准 → doExecuteTool()
        │     │   │         │     │   ├── [n] 拒绝 → 返回
        │     │   │         │     │   └── [m] 修改参数 → 执行修改后
        │     │   │         │     │
        │     │   │         │     └── doExecuteTool()
        │     │   │         │           │
        │     │   │         │           ├── mcpTools 表命中?
        │     │   │         │           │   ├── Yes → MCP Client → MCP Server
        │     │   │         │           │   └── No  → 继续
        │     │   │         │           │
        │     │   │         │           ├── tools 表命中?
        │     │   │         │           │   ├── Yes → 内置 Java 实现
        │     │   │         │           │   └── No  → "未知工具"
        │     │   │         │           │
        │     │   │         │           └── 安全策略检查
        │     │   │         │                 (PathGuard / CommandGuard / 大小限制)
        │     │   │         │
        │     │   │         └── 结果写回 conversationHistory
        │     │   │               memoryManager.addToolResult()
        │     │   │
        │     │   │         → continue (回到 budget.check)
        │     │   │
        │     │   └── No → 输出最终文本，结束循环
        │     │
        │     └── 返回 response
        │
        └── 17. ui.println(response)   ← 打印结果给用户
              │
              └── 18. 重置模式标志
                    nextTaskUsePlanMode = false
                    nextTaskUseTeamMode = false
                    │
                    └── 19. 回到 while(true) 开头
                          等待下一次输入
```

---

## 代码路径速查表

| 步骤        | 文件                         | 行号        | 关键方法                        |
| --------- | -------------------------- | --------- | --------------------------- |
| 输入读取      | `Main.java`                | 370-398   | `readPromptInput()`         |
| 命令解析      | `Main.java`                | 400       | `CliCommandParser.parse()`  |
| @引用展开     | `Main.java`                | 745-746   | `mentionExpander.expand()`  |
| 模式选择      | `Main.java`                | 757-779   | `nextTaskUsePlanMode` 分支    |
| Snapshot  | `Main.java`                | 782-784   | `snapshotService.runTurn()` |
| ESC 取消    | `Main.java`                | 901-954   | `runWithCancelSupport()`    |
| ReAct 循环  | `Agent.java`               | 121-251   | `Agent.run()`               |
| Budget 检查 | `Agent.java`               | 158-166   | `budget.check()`            |
| LLM 调用    | `Agent.java`               | 175-179   | `llmClient.chat()`          |
| 工具执行入口    | `Agent.java`               | 208       | `executeToolCalls()`        |
| 多工具并行     | `ToolRegistry.java`        | 1196-1265 | `executeTools()`            |
| 工具最终路由    | `ToolRegistry.java`        | 1111-1167 | `doExecuteTool()`           |
| MCP 工具分发  | `ToolRegistry.java`        | 1125-1142 | `mcpTool.invoker().apply()` |
| HITL 审批   | `HitlToolRegistry.java`    | 35-79     | `executeToolOutput()`       |
| HITL 弹窗   | `TerminalHitlHandler.java` | 71-92     | `requestApproval()`         |
| 危险策略      | `ToolRegistry.java`        | 1154-1158 | PathGuard/CommandGuard      |
| 系统提示装配    | `Agent.java`               | 288-293   | `buildSystemPrompt()`       |

---

## 面试自测题

读完本文后，你应该能秒答以下问题：

| 问题                                           | 答案                                                                                    |
| -------------------------------------------- | ------------------------------------------------------------------------------------- |
| 用户输入普通文本后，代码如何区分这是"斜杠命令"还是"交给 LLM 的 prompt"？ | `CliCommandParser.parse()` 返回 `CommandType.NONE` 时才进入 Agent，其他都在 CLI 层消耗              |
| Agent.run() 的 ReAct 循环什么时候结束？                | 当 LLM 返回的响应中没有 `tool_calls` 时 —— 即 LLM 自己决定不再调用更多工具                                   |
| 同一轮 LLM 返回多个 tool_calls（如同时写文件和查代码），怎么执行？    | `ToolRegistry.executeTools()` 用线程池并行执行，结果按原始顺序返回                                      |
| doExecuteTool 怎么区分 MCP 工具和内置工具？              | 先查 `mcpTools` 表，再查 `tools` 表。MCP 工具通过 invoker 发给外部进程，内置工具直接调 Java 方法                  |
| HITL 审批框在哪个环节插入？                             | `HitlToolRegistry.executeToolOutput()` 中，在调用 `doExecuteTool()` 之前                     |
| Agent 执行期间用户按 ESC 会怎样？                       | `runWithCancelSupport()` 监听终端 raw mode，收到孤立 ESC → `future.cancel(true)` → 中断 Agent 线程 |
| @引用展开在什么时候发生？                                | 命令解析之后、Agent 执行之前。`mentionExpander.expand(input)` 将 `@path` 替换为文件内容                   |

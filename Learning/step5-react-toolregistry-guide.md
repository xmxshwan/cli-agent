# 第 5 步：ReAct + ToolRegistry —— 最核心的执行引擎

> 搭配 `learningPlan.md` 第 2.4 节食用。
>
> ReAct 是 PaiCLI 的"主脑循环"，ToolRegistry 是"工具箱"——两者配合构成整个 Agent 的执行引擎。

---

## 整体定位

ReAct 和 ToolRegistry 是 PaiCLI **最基本、最核心**的模块。三种执行模式（ReAct / Plan-and-Execute / Multi-Agent）都共享同一套 ToolRegistry。

```
┌─────────────────────────────────────────────┐
│            三种执行模式                        │
│                                              │
│  Agent.java      (ReAct — 默认)              │
│  PlanExecuteAgent (/plan)                    │
│  AgentOrchestrator (/team — Multi-Agent)     │
│              │    共享                        │
│              ▼                               │
│  ToolRegistry  ← 所有工具的注册和执行中心      │
│                                              │
│  内置工具(9+)  +  MCP 工具(动态注册)           │
└─────────────────────────────────────────────┘
```

---

## 核心文件清单

| 文件 | 角色 | 行数 | 优先级 |
|------|------|------|--------|
| `agent/Agent.java` | **ReAct 循环**——整个项目的执行引擎 | ~1042 | ★★★ |
| `tool/ToolRegistry.java` | **工具注册表**——注册/查找/执行 | ~1423 | ★★★ |
| `tool/ToolOutput.java` | 工具执行结果模型（文本 + 图片） | - | ★★☆ |

---

## 一、ReAct 循环（Agent.java）

### 核心数据结构

```java
// Agent.java:46-56
public class Agent {
    private LlmClient llmClient;                          // LLM 客户端
    private final ToolRegistry toolRegistry;               // 工具注册表
    private final List<LlmClient.Message> conversationHistory;  // ★ 消息历史——循环的"燃料"
    private final MemoryManager memoryManager;             // 记忆管理器
    private final ConversationHistoryCompactor historyCompactor; // 压缩器
    private SkillRegistry skillRegistry;                   // Skill 系统
    private Renderer renderer;                             // 渲染器
}
```

`conversationHistory` 是 ReAct 循环的核心：

```java
// 运行时状态（按时间顺序）:
conversationHistory = [
    system:   "你是 PaiCLI Agent...",   ← 第 0 位：system prompt
    user:     "帮我查 JWT 逻辑",         ← 用户输入
    assistant: "让我搜索" + tool_calls,   ← LLM 决定调工具
    tool:     "找到 JwtUtil.java:35",   ← 工具执行结果
    assistant: "JWT 的逻辑是..."         ← LLM 最终回答（无 tool_calls）
]
```

### ReAct 循环源码（精简版）

```java
// Agent.java:147-251
while (true) {
    // 0. 调 LLM 前准备
    maybeCompactHistory();                     // Token 超阈值→压缩
    budget.check();                            // 预算检查兜底

    // 1. 拿工具定义 + 调 LLM
    List<LlmClient.Tool> toolDefinitions = toolRegistry.getToolDefinitions();
    LlmClient.ChatResponse response = llmClient.chat(
        conversationHistory,     // 对话历史（system + user + assistant + tool）
        toolDefinitions,         // 全部工具定义（序列化为 tools 参数）
        streamRenderer           // 流式输出渲染器
    );

    // 2. 判断 LLM 的决策
    if (response.hasToolCalls()) {
        // ★ 有工具调用 — 执行 → 回灌 → 继续循环
        conversationHistory.add(LlmClient.Message.assistant(
            response.reasoningContent(), response.content(), response.toolCalls()));

        List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);
        for (ToolExecutionResult result : results) {
            memoryManager.addToolResult(result.name(), result.result());
            conversationHistory.add(LlmClient.Message.tool(result.id(), result.result()));
        }
        continue;  // 回到 while 开头，让 LLM 看到工具结果
    } else {
        // ★ 无工具调用 — 返回最终回答
        conversationHistory.add(LlmClient.Message.assistant(response.content()));
        memoryManager.addAssistantMessage(response.content());
        return response.content();
    }
}
```

### 完整流程图

```
用户输入 "帮我查 JWT 的逻辑"
    │
    ▼
Agent.run(userInput)                               第 121 行
    │
    ├── memoryManager.addUserMessage(content)      第 125 行
    ├── memoryManager.buildContextForQuery(query)   第 130 行 → 查长期记忆
    ├── updateSystemPromptWithMemory(ctx)           第 131 行 → 注入 system
    ├── conversationHistory.add(userMsg)            第 135 行
    │
    ▼
    ReAct 循环开始 — while (true)                   第 147 行
    │
    ├── maybeCompactHistory()                       第 157 行
    │   → Token 超阈值? → ConversationHistoryCompactor 压缩
    │
    ├── budget.check()                              第 158 行
    │   → Token 超/死循环/超轮数? → return ❌
    │
    ├── toolDefinitions = toolRegistry.getToolDefinitions()  第 171 行
    │   → [{name, description, parameters}, ...]
    │
    ├── response = llmClient.chat(history, tools)   第 175 行
    │   → HTTP POST 到 LLM API: {messages, tools}
    │   → 流式渲染 reasoning + content
    │
    ├── hasToolCalls()?
    │   │
    │   ├── YES ──────────────────────────────────── 第 191 行
    │   │   │
    │   │   ├── conversationHistory.add(assistant)  第 196 行
    │   │   │
    │   │   ├── toolRegistry.executeTools(calls)    第 208 行
    │   │   │   │
    │   │   │   ├── 1 个 → 串行 (第 1205 行)
    │   │   │   └── N 个 → 线程池并行 (第 1212 行)
    │   │   │         最多 4 个并发
    │   │   │         结果按原始顺序返回
    │   │   │
    │   │   ├── for each result:
    │   │   │   ├── memoryManager.addToolResult()   第 210 行
    │   │   │   └── conversationHistory.add(tool)   第 211 行
    │   │   │
    │   │   └── continue → 回到 while 开头          第 217 行
    │   │
    │   └── NO ───────────────────────────────────── 第 220 行
    │       │
    │       ├── conversationHistory.add(assistant)  第 222 行
    │       ├── memoryManager.addAssistantMessage() 第 225 行
    │       └── return answer                       第 244 行
    │
    ▼
用户看到结果
```

### 一个具体的 ReAct 例子

用户："帮我打开百度"

```
第 1 轮迭代：
  history = [system, user: "打开百度"]
  + tools = [read_file, write_file, mcp__chrome-devtools__navigate, ...]
  ──→ LLM 返回 tool_calls: navigate({url: "https://baidu.com"})
  ──→ 执行 navigate → 成功导航
  ──→ history += [assistant(tool_calls), tool(导航成功)]
  ──→ continue

第 2 轮迭代：
  history = [system, user, assistant(tool_calls), tool(导航成功)]
  ──→ LLM 返回 text: "已为您打开百度首页"
  ──→ 无 tool_calls → return "已为您打开百度首页"
```

### 退出条件

| 条件 | 触发 | 源码行 |
|------|------|--------|
| **正常退出** | LLM 不再调工具，返回 text | 第 220-244 行 |
| **Token 超限** | `budget.check()` 检测到 | 第 158-165 行 |
| **死循环** | 连续 N 轮重复相同工具调用 | 第 158 行 (budget 内部) |
| **超轮数** | 超过硬轮数上限 | 第 158 行 (budget 内部) |
| **用户取消** | `CancellationContext.isCancelled()` | 第 148-152 行 |
| **LLM 调用失败** | IOException | 第 246-249 行 |

---

## 二、ToolRegistry（ToolRegistry.java）

### 两种工具

```java
// 第 82-83 行
private final Map<String, Tool> tools = new ConcurrentHashMap<>();        // 内置工具
private final Map<String, McpRegisteredTool> mcpTools = new ConcurrentHashMap<>();  // MCP 工具
```

| 维度 | 内置工具 | MCP 工具 |
|------|---------|---------|
| **注册时机** | 构造时（`ToolRegistry()` 第 118-128 行） | `McpServerManager` 启动后动态注册 |
| **命名** | `read_file`、`write_file` | `mcp__server__tool` |
| **执行器** | `ToolExecutor.execute(Map<String,String>)` | `Function<String, ToolOutput>` |
| **数据模型** | `Tool(name, description, parameters, executor)` | `McpRegisteredTool(descriptor, invoker)` |

### 内置工具注册

```java
// 第 118-128 行：构造时一次性注册
public ToolRegistry() {
    registerFileTools();     // read_file, write_file, list_dir, glob_files, grep_code
    registerShellTools();    // execute_command
    registerCodeTools();     // create_project
    registerRagTools();      // search_code
    registerWebTools();      // web_search, web_fetch
    registerBrowserTools();  // browser 相关
    registerMemoryTools();   // 记忆相关
    registerSkillTools();    // skill 相关
    registerSnapshotTools(); // snapshot/revert 相关
}
```

### 每个工具的注册结构

```java
// 第 1375 行：Tool 数据模型
public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

// 第 1420 行：执行器接口
public interface ToolExecutor {
    String execute(Map<String, String> args);
}
```

以 `read_file` 为例：

```java
// 第 234-250 行
tools.put("read_file", new Tool(
    "read_file",
    "读取文件内容（仅限项目根目录之内）",
    createParameters(
        new Param("path", "string", "文件路径", true),
        new Param("offset", "integer", "起始行号", false),
        new Param("limit", "integer", "最多读取多少行", false)
    ),
    args -> {                               // ★ 执行逻辑
        Path safe = pathGuard.resolveSafe(args.get("path"));
        return readFileForTool(safe, args);
    }
));
```

### 获取工具定义（发给 LLM）

```java
// 第 1027-1031 行：序列化为 LLM 可理解的格式
public List<LlmClient.Tool> getToolDefinitions() {
    return tools.values().stream()
        .map(t -> new LlmClient.Tool(t.name(), t.description(), t.parameters()))
        .toList();
}
```

这个结果被 `Agent.java:171` 取走，序列化成 JSON 放到 LLM 请求体的 `tools` 字段：

```json
{
  "messages": [...],
  "tools": [
    {"type": "function", "function": {
      "name": "read_file",
      "description": "读取文件内容...",
      "parameters": {"type": "object", "properties": {...}}
    }},
    {"type": "function", "function": {
      "name": "mcp__chrome-devtools__navigate",
      "description": "导航到指定 URL",
      "parameters": {"type": "object", "properties": {...}}
    }}
  ]
}
```

**这些工具定义占用的 token 就是为什么 `McpSchemaSanitizer` 要裁剪 Schema——省 Token！**

### 工具执行链路（doExecuteTool）

```java
// 第 1111-1167 行：核心执行方法
protected ToolOutput doExecuteTool(String name, String argumentsJson) {
    Tool tool = tools.get(name);                    // 1. 查找
    if (tool == null) return "未知工具: " + name;

    try {
        McpRegisteredTool mcpTool = mcpTools.get(name);
        if (mcpTool != null) {
            // ★ MCP 工具路径
            BrowserCheckResult check = checkBrowserTool(name, args, false);
            if (check.blocked()) throw new PolicyException(check.reason());
            return mcpTool.invoker().apply(argumentsJson);
              // → 最终转到 McpClient.callToolOutput()
        }

        // ★ 内置工具路径
        JsonNode args = mapper.readTree(argumentsJson);
        Map<String, String> argMap = new HashMap<>();
        args.fields().forEachRemaining(e -> argMap.put(e.getKey(), e.getValue().asText()));
        String result = tool.executor().execute(argMap);
        return ToolOutput.text(result);
    } catch (PolicyException e) {
        return "🛡️ 策略拒绝: " + e.getMessage();       // 策略拦截
    } catch (Exception e) {
        return "工具执行失败: " + e.getMessage();        // 异常
    }
}
```

### 并行工具执行

```java
// 第 1196-1265 行
public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
    if (invocations.size() == 1) {
        return List.of(executeOne(invocation));   // 单个→串行
    }

    // 多个→线程池并行，最多 4 个并发
    int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);  // MAX = 4
    ExecutorService executor = Executors.newFixedThreadPool(parallelism);

    List<Future<ToolExecutionResult>> futures =
        executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

    // ★ 结果按原始顺序组装返回！
    List<ToolExecutionResult> results = new ArrayList<>();
    for (int i = 0; i < futures.size(); i++) {
        if (futures.get(i).isCancelled()) {
            results.add(ToolExecutionResult.timedOut(...));  // 超时
        } else {
            results.add(futures.get(i).get());               // 正常完成
        }
    }
    return results;
    // → 回到 Agent.java:208-212 → 按顺序回灌 conversationHistory
}
```

**关键设计：** LLM 协议要求 tool call 的结果按原始 index 顺序返回，所以必须保持顺序。

### 审计与策略

```java
// 第 1275-1277 行
private static boolean shouldAudit(String name) {
    return AUDIT_TOOLS.contains(name) || (name != null && name.startsWith("mcp__"));
    // write_file / execute_command / create_project / revert_turn + 所有 MCP 工具
}

// doExecuteTool 中的审计路径:
//   - 正常 → AuditEntry.allow
//   - 策略拒绝 → AuditEntry.denyByPolicy
//   - 异常 → AuditEntry.error
```

### 数据模型一览

```java
// 第 1373-1422 行
private record Param(String name, String type, String description, boolean required) {}

public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

private record McpRegisteredTool(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {}

public record ToolInvocation(String id, String name, String argumentsJson) {}

public record ToolExecutionResult(String id, String name, String argumentsJson,
                                   String result, long elapsedMillis, boolean timedOut,
                                   List<LlmClient.ContentPart> imageParts) {
    static ToolExecutionResult completed(ToolInvocation, ToolOutput, long elapsed);
    static ToolExecutionResult timedOut(ToolInvocation, long timeoutSeconds);
    static ToolExecutionResult failed(ToolInvocation, String message);
}

public interface ToolExecutor {
    String execute(Map<String, String> args);
}
```

---

## 三、Agent.java 的其他重要职责

| 职责 | 方法 | 行号 | 说明 |
|------|------|------|------|
| 构建 system prompt | `buildSystemPrompt(memoryContext)` | 288-294 | PromptAssembler 装配 system prompt |
| 注入长期记忆 | `updateSystemPromptWithMemory(ctx)` | 284-286 | 替换 conversationHistory[0] |
| Skill 前置注入 | `prependSkillBodies(userInput)` | 348-355 | Skill 内容前置到用户输入前 |
| 压缩历史 | `maybeCompactHistory()` | 296-307 | ConversationHistoryCompactor 压缩 |
| 历史图片清理 | `pruneHistoricalImagePayloads()` | 309-326 | 避免重复发图浪费 Token |
| 执行工具批 | `executeToolCalls(calls, iter)` | 617-636 | 组装 ToolInvocation → 调 ToolRegistry |
| 流式渲染 | `StreamRenderer`（内部类） | 779-1040 | 分区展示 reasoning / content |
| 状态推送 | `pushStatus(budget, startNanos, phase)` | 576-605 | 推到底部 dock 状态栏 |
| 上下文状态 | `getContextStatus()` | 395-443 | `/context` 命令输出 |

---

## 四、conversationHistory 的完整生命周期

```
启动时:
  conversationHistory = [system prompt]
    │
用户输入:
  addUserMessage → memoryManager
  → conversationHistory += user("帮我查 JWT")
    │
ReAct 循环:
  ├── LLM 返回 tool_calls
  │   → conversationHistory += assistant(reasoning, text, tool_calls)
  │   → execute → tool_results
  │   → for each result:
  │       → memoryManager.addToolResult()
  │       → conversationHistory += tool(id, result)
  │   → continue
  │
  └── LLM 返回 text（无 tool_calls）
      → conversationHistory += assistant(text)
      → memoryManager.addAssistantMessage(text)
      → return
    │
压缩触发时:
  maybeCompactHistory()
  → historyCompactor.compactIfNeeded(history, triggerTokens)
  → history 被替换为:
    [system]
    [user("[已压缩的历史对话摘要]\n...")]
    [assistant("好的，我已了解之前的上下文，请继续。")]
    [尾部保留的最近 N 轮消息]
```

---

## 五、完整架构图

```
┌──────────────────────────────────────────────────────────────┐
│                        Agent.java                             │
│                        ReAct 循环                              │
│                                                               │
│  ┌──────────────────────────────────────────────────┐        │
│  │  conversationHistory (List<LlmClient.Message>)    │        │
│  │  [system] [user] [assistant+tc] [tool] [...]     │        │
│  └──────────────────────────────────────────────────┘        │
│       │                                                     │
│       │  + toolRegistry.getToolDefinitions()                 │
│       ▼                                                     │
│  ┌──────────────────────────────────────────────────┐        │
│  │  LlmClient.chat(history, tools, listener)         │        │
│  │  → HTTP POST → LLM API → 响应                     │        │
│  │  → 流式渲染 reasoning + content                    │        │
│  └──────────────────────────────────────────────────┘        │
│       │                                                     │
│       ├── tool_calls? ──→ ToolRegistry.executeTools()        │
│       │                      │                               │
│       │                      ├── 内置工具 → ToolExecutor     │
│       │                      ├── MCP 工具 → McpClient       │
│       │                      ├── 策略拦截 → PolicyException  │
│       │                      └── 审计 → AuditLog             │
│       │                           │                          │
│       │                           ▼                          │
│       │                     结果回灌 conversationHistory      │
│       │                     continue                         │
│       │                                                     │
│       └── text? ──→ return answer                            │
│                                                               │
│  两道防线:                                                    │
│    ① maybeCompactHistory() (第 157 行)                       │
│    ② budget.check() (第 158 行)                              │
│                                                               │
│  依赖注入:                                                    │
│    MemoryManager ↦ 记忆                                       │
│    SkillRegistry  ↦ Skill                                     │
│    Renderer       ↦ 渲染                                      │
│    HITL           ↦ 审批                                      │
└──────────────────────────────────────────────────────────────┘
```

---

## 六、关键源码行速查

| 功能 | 文件 | 行号 |
|------|------|------|
| ReAct 循环入口 | `Agent.java` `run()` | 121 |
| 调 LLM | `Agent.java` `llmClient.chat()` | 175 |
| 工具调用分支 | `Agent.java` `hasToolCalls()` | 191 |
| 执行工具批 | `Agent.java` `executeToolCalls()` | 617 |
| 结果回灌 | `Agent.java` 第 208-212 行 | 208 |
| 正常退出 | `Agent.java` `return answer` | 244 |
| 注册内置工具 | `ToolRegistry.java` 构造器 | 118 |
| 获取工具定义 | `ToolRegistry.java` `getToolDefinitions()` | 1027 |
| 注册 MCP 工具 | `ToolRegistry.java` `registerMcpToolOutput()` | 1046 |
| 执行单个工具 | `ToolRegistry.java` `doExecuteTool()` | 1111 |
| 并行执行工具 | `ToolRegistry.java` `executeTools()` | 1196 |
| 审计判定 | `ToolRegistry.java` `shouldAudit()` | 1275 |
| Tool 数据模型 | `ToolRegistry.java` `record Tool` | 1375 |
| ToolExecutor 接口 | `ToolRegistry.java` `interface ToolExecutor` | 1420 |

---

## 面试亮点

> ReAct 循环采用 **think-act-observe** 模式：每轮把 `conversationHistory`（system + 用户输入 + 历史助手回复 + 工具结果）和 `toolRegistry.getToolDefinitions()`（全部工具定义序列化）发给 LLM，由 LLM 自主决定调用工具还是返回最终回答。退出条件只有 LLM 自决和预算耗尽（Token/死循环/硬轮数）两种。
>
> ToolRegistry 统一管理 9+ 个内置工具和动态注册的 MCP 工具，内置工具通过 `ToolExecutor.execute(Map)` 执行，MCP 工具通过 `mcp__{server}__{tool}` 前缀转发到 `McpServerManager`。多个工具调用默认最多 4 个并发执行，结果按原始顺序组装返回以兼容 LLM 协议。危险工具（write_file / execute_command / 全部 MCP 工具）自动纳入审计日志。
>
> 同一轮多 tool_calls 的并行执行是本项目的一个优化点：通过 `ExecutorService.invokeAll()` 实现，支持批次超时取消，已完成工具不受影响。ReAct / Plan-and-Execute / Multi-Agent 三种模式复用同一套调度器。

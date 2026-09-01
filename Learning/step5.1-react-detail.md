# Step 5.1：ReAct 循环详解 —— 从源码理解 think-act-observe

> 基于 `step5-react-toolregistry-guide.md` 和源码逐行分析。
> 建议搭配 `Agent.java` 一起阅读。

---

## 一、核心定位

**ReAct（Reasoning + Acting）** 是 PaiCLI 的"主脑循环"，本质是一个 **while(true)** 循环，每轮完成：

```
Think（思考）  →  LLM 决定下一步
  ↓
Act（行动）    →  执行工具（如有）
  ↓
Observe（观察）→  工具结果回灌历史
  ↓
      ← 继续循环，直到 LLM 不再调工具
```

---

## 二、Agent 的核心数据结构

**文件：** `src/main/java/com/paicli/agent/Agent.java` 第 51-63 行

```java
public class Agent {
    private LlmClient llmClient;                          // 第 53 行 — LLM 客户端
    private final ToolRegistry toolRegistry;               // 第 54 行 — 工具注册表
    private final List<LlmClient.Message> conversationHistory;  // 第 55 行 ★ 核心！
    private final MemoryManager memoryManager;             // 第 56 行 — 记忆管理器
    private final ConversationHistoryCompactor historyCompactor; // 第 57 行 — 压缩器
    private SkillRegistry skillRegistry;                   // 第 59 行 — Skill 系统
    private Renderer renderer;                             // 第 61 行 — 渲染器
}
```

### 2.1 `conversationHistory` —— 循环的"燃料"（第 55 行）

`conversationHistory` 是一个 `List<LlmClient.Message>`，按时间顺序存放所有消息。它的运行时快照长这样：

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

### 2.2 `LlmClient.Message` 数据结构

**文件：** `src/main/java/com/paicli/llm/LlmClient.java` 第 54-55 行

```java
record Message(String role, String content, String reasoningContent,
               List<ToolCall> toolCalls, String toolCallId,
               List<ContentPart> contentParts) {
```

支持的消息类型（第 65-96 行）：
- `Message.system(content)` — 系统提示
- `Message.user(content)` — 用户输入
- `Message.assistant(content)` — LLM 回复
- `Message.assistant(reasoning, content, toolCalls)` — LLM 回复 + 工具调用
- `Message.tool(toolCallId, content)` — 工具执行结果

---

## 三、ReAct 入口：`run()` 方法

**文件：** `Agent.java:128`

```java
public String run(String userInput) {         // ← 第 128 行
```

### 3.1 准备工作（第 130-145 行）

```java
pruneHistoricalImagePayloads();               // 第 130 行 — 清理历史图片负载，省 Token
memoryManager.addUserMessage(userInput);      // 第 132 行 — 写入短期记忆

String memoryContext = memoryManager.buildContextForQuery(
    userInput, contextProfile.memoryContextTokens());  // 第 137 行 — 查询长期记忆
updateSystemPromptWithMemory(memoryContext);   // 第 138 行 — 注入 system prompt

String userMessageContent = prependSkillBodies(userInput);  // 第 141 行 — Skill 内容前置
conversationHistory.add(ImageReferenceParser.userMessage(
    userMessageContent, ...));                 // 第 142 行 — 用户消息加入历史
```

**`updateSystemPromptWithMemory`（第 291-293 行）：**
```java
private void updateSystemPromptWithMemory(String memoryContext) {
    conversationHistory.set(0, LlmClient.Message.system(
        promptAssembler.assemble(PromptMode.AGENT, PromptContext.builder()
            .memoryContext(memoryContext)       // 注入长期记忆
            .externalContext(buildExternalContext())  // MCP Resource 索引
            .skillIndex(buildSkillIndex())      // Skill 索引
            .build())
    ));
}
```

### 3.2 主循环开始：`while(true)`（第 154 行）

```java
long startNanos = System.nanoTime();
AgentBudget budget = AgentBudget.fromLlmClient(llmClient);
pushStatus(budget, startNanos, "running");

while (true) {                                     // ← 第 154 行
```

---

## 四、ReAct 循环六步详解

### 第 1 步：安全防护（第 155-173 行）

```java
// ★ 1a. 用户取消检查（第 155 行）
if (CancellationContext.isCancelled()) {
    return "⏹️ 已取消当前任务。";
}

// ★ 1b. LSP 诊断注入（第 163 行）
injectPendingLspDiagnostics();
// 如果有未推送的 LSP 错误，作为 user message 注入

// ★ 1c. 压缩历史（第 164 行）
maybeCompactHistory();
// 如果 conversationHistory 估算 Token 接近窗口上限，
// 用 ConversationHistoryCompactor 把早期消息压缩为摘要

// ★ 1d. 预算检查（第 165 行）
AgentBudget.ExitReason exitReason = budget.check();
if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
    return "❌ " + budget.describeExit(exitReason);
    // 三种退出：Token 超限 / 死循环 / 超硬轮数
}

int iteration = budget.beginIteration();             // 第 175 行
```

**`maybeCompactHistory` 源码（第 303-313 行）：**
```java
private void maybeCompactHistory() {
    if (historyCompactor == null) return;
    int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
    try {
        boolean compacted = historyCompactor.compactIfNeeded(conversationHistory, trigger);
        if (compacted) {
            renderer().stream().println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
        }
    } catch (Exception e) {
        log.warn("conversationHistory compaction failed", e);
    }
}
```

### 第 2 步：获取工具定义（第 178 行）

```java
List<LlmClient.Tool> toolDefinitions = toolRegistry.getToolDefinitions();
```

这调用 `ToolRegistry.java:1027-1031`，遍历所有工具（内置 + MCP），把它们的 `name`、`description`、`parameters` 序列化为 `LlmClient.Tool` 列表。具体见 `step5.2-toolregistry-detail.md`。

### 第 3 步：调用 LLM（第 180-186 行）

```java
streamRenderer.beginThinking();

LlmClient.ChatResponse response = llmClient.chat(
    conversationHistory,       // 整段对话历史（system + user + assistant + tool）
    toolDefinitions,           // 全部工具定义
    streamRenderer             // 流式渲染器
);
```

**这一步实际上发生了什么（`AbstractOpenAiCompatibleClient.java:59-72`）：**
```java
RequestBody body = RequestBody.create(
    buildRequestBody(messages, tools).toString(),   // 序列化为 JSON
    MediaType.parse("application/json")
);

Request request = new Request.Builder()
    .url(getApiUrl())                               // "https://api.deepseek.com/chat/completions"
    .header("Authorization", "Bearer " + getApiKey())
    .post(body)
    .build();

try (Response response = SHARED_HTTP_CLIENT.newCall(request).execute()) {
    // 流式解析 SSE 响应
}
```

**发送给 LLM 的 HTTP 请求体长什么样子：**

```json
{
  "model": "deepseek-v4-flash",
  "stream": true,
  "messages": [
    {"role": "system", "content": "你是 PaiCLI Agent..."},
    {"role": "user", "content": "帮我读一下 Agent.java"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "read_file",
        "description": "读取文件内容...",
        "parameters": {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "文件路径"},
            "offset": {"type": "integer", "description": "起始行号"},
            "limit": {"type": "integer", "description": "最多读取多少行"}
          },
          "required": ["path"]
        }
      }
    },
    {
      "type": "function",
      "function": {
        "name": "mcp__filesystem__read_file",
        "description": "读取文件内容 (MCP server: filesystem, tool: read_file)",
        "parameters": { ... }
      }
    }
  ]
}
```

> 💡 **tools 字段就是《工具说明书》**——LLM 靠它知道有哪些工具、每个工具的参数是什么！

### 第 4 步：解析 LLM 响应

**`AbstractOpenAiCompatibleClient.java:82-161`（流式解析 SSE 行）：**

```java
while (!source.exhausted()) {
    String line = source.readUtf8Line();
    // data: {"choices":[{"delta":{"content":"..."}}]}
    // data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"read_file","arguments":"{\"path\":\"..."}}}]}}]}
    // data: [DONE]

    String payload = trimmed.substring("data:".length()).trim();
    JsonNode root = mapper.readTree(payload);

    // 1. 累加 reasoning_content
    String reasoningDelta = extractReasoningDelta(delta);
    reasoning.append(reasoningDelta);
    streamListener.onReasoningDelta(reasoningDelta);

    // 2. 累加 content
    String contentDelta = delta.path("content").asText("");
    content.append(contentDelta);
    streamListener.onContentDelta(contentDelta);

    // 3. 累加 tool_calls delta
    mergeToolCallDeltas(toolAccumulators, delta.path("tool_calls"));
}

return new ChatResponse(role, content.toString(), reasoning.toString(),
    buildToolCalls(toolAccumulators),    // ← 组装出完整的 tool_calls
    inputTokens, outputTokens, cachedInputTokens);
```

### 第 5 步：判断 LLM 的决策（第 198 行）

```java
if (response.hasToolCalls()) {
    // → 执行工具 + 结果回灌 + continue
} else {
    // → 返回最终回答
}
```

#### 分支 A：有工具调用（第 198-224 行）

```java
if (response.hasToolCalls()) {
    // a) LLM 回复（含 tool_calls）记入历史
    conversationHistory.add(LlmClient.Message.assistant(
        response.reasoningContent(),
        response.content(),
        response.toolCalls()                       // 第 203-207 行
    ));

    // b) 渲染工具调用信息
    streamRenderer.resetBetweenIterations();
    renderer().appendToolCalls(response.toolCalls());

    // c) 执行工具（第 215 行）→ 进入 executeToolCalls
    List<ToolExecutionResult> toolResults = executeToolCalls(
        response.toolCalls(), iteration);

    // d) 结果回灌（第 216-219 行）
    for (ToolExecutionResult toolResult : toolResults) {
        memoryManager.addToolResult(toolResult.name(), toolResult.result());
        conversationHistory.add(LlmClient.Message.tool(
            toolResult.id(), toolResult.result()    // ★ 回灌！
        ));
    }

    continue;  // 回到 while(true) 开头，让 LLM 看到工具结果
}
```

#### 分支 B：无工具调用（第 227-251 行）——正常退出

```java
// a) LLM 最终回答记入历史
conversationHistory.add(LlmClient.Message.assistant(response.content()));

// b) 存入短期记忆
memoryManager.addAssistantMessage(response.content());
memoryManager.recordTokenUsage(...);

// c) 返回
pushStatus(budget, startNanos, "idle");
return formatUserFacingResponse(reasoningTranscript.toString(), response.content());
```

### 第 6 步：异常处理（第 253-257 行）

```java
catch (IOException e) {
    log.error("LLM call failed in ReAct loop", e);
    streamRenderer.finish();
    return "❌ 调用 LLM 失败: " + e.getMessage();
}
```

---

## 五、executeToolCalls 详解

**文件：** `Agent.java:624-643`

```java
private List<ToolExecutionResult> executeToolCalls(List<LlmClient.ToolCall> toolCalls, int iteration) {
    List<ToolInvocation> invocations = new ArrayList<>();

    // a) 把 ToolCall 转成 ToolInvocation（第 625-632 行）
    for (LlmClient.ToolCall toolCall : toolCalls) {
        String toolName = toolCall.function().name();       // 如 "read_file"
        String toolArgs = toolCall.function().arguments();  // 如 '{"path":"..."}'
        log.info("Scheduling tool: {} (iteration={})", toolName, iteration);
        invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
    }

    // b) 交给 ToolRegistry 执行（第 637 行）
    List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);

    // c) 打印摘要（第 638-642 行）
    for (ToolExecutionResult result : results) {
        log.debug("Tool result preview [{}]: {}", result.name(), preview(result.result(), 300));
        emitToolResultSummary(result);
    }
    return results;
}
```

### 5.1 `LlmClient.ToolCall` 的数据结构

**文件：** `LlmClient.java:176-178`

```java
record ToolCall(String id, Function function) {
    record Function(String name, String arguments) {}
}
```

| 字段 | 含义 | 来自 LLM 响应的位置 |
|------|------|-------------------|
| `id` | 唯一标识（如 `call_abc123`） | `tool_calls[i].id` |
| `function.name()` | 工具名（如 `read_file`） | `tool_calls[i].function.name` |
| `function.arguments()` | 参数字符串（如 `{"path":"..."}`） | `tool_calls[i].function.arguments` |

### 5.2 `ToolInvocation` 的数据结构

**文件：** `ToolRegistry.java:1379`

```java
public record ToolInvocation(String id, String name, String argumentsJson) {}
```

### 5.3 `ToolExecutionResult` 的数据结构

**文件：** `ToolRegistry.java:1381-1418`

```java
public record ToolExecutionResult(String id, String name, String argumentsJson,
                                   String result, long elapsedMillis, boolean timedOut,
                                   List<LlmClient.ContentPart> imageParts) {
    static ToolExecutionResult completed(ToolInvocation, ToolOutput, long elapsed);  // 正常完成
    static ToolExecutionResult timedOut(ToolInvocation, long timeoutSeconds);         // 超时
    static ToolExecutionResult failed(ToolInvocation, String message);                // 失败
}
```

---

## 六、6 种退出条件

**核心原则：** 正常退出只有一种——**LLM 自决不再调工具**。其余全是兜底。

| 条件 | 触发 | 源码位置 |
|------|------|---------|
| **✅ 正常退出** | LLM 返回 text（无 tool_calls） | 第 227-251 行 |
| **⏹️ 用户取消** | `CancellationContext.isCancelled()` | 第 155、188 行 |
| **🪣 Token 超限** | `budget.check()` 检测 | 第 165-173 行 |
| **🔄 死循环** | 连续 N 轮重复相同工具调用 | 第 165 行（budget 内部） |
| **🔢 超轮数** | 超过硬轮数上限 | 第 165 行（budget 内部） |
| **❌ LLM 调用失败** | `IOException` | 第 253-257 行 |

---

## 七、一个完整的 ReAct 例子

用户输入："帮我打开百度"

```
【第 0 轮】准备阶段（第 130-145 行）
  conversationHistory = [system, user: "帮我打开百度"]

【第 1 轮迭代】
  ┌── 安全防护（第 155-173 行）
  │   budget.check() → WITHIN_BUDGET
  │
  ├── 获取工具定义（第 178 行）
  │   tools = [read_file, write_file, mcp__chrome-devtools__navigate, ...]
  │
  ├── 调 LLM（第 182 行）
  │   请求: {messages: [system, user], tools: [...]}
  │
  ├── LLM 返回 tool_calls（第 198 行）
  │   [{name: "mcp__chrome-devtools__navigate", args: {url: "https://baidu.com"}}]
  │
  ├── assistant 消息入历史（第 203 行）
  │   conversationHistory += assistant(tool_calls)
  │
  ├── 执行工具（第 215 行）
  │   → executeToolCalls → ToolRegistry.executeTools
  │   → McpClient.callTool("navigate", {url: "..."})
  │   → MCP Server 执行导航 → 成功
  │   → 返回 "导航成功"
  │
  ├── 结果回灌（第 218 行）
  │   conversationHistory += tool("导航成功")
  │
  └── continue（第 224 行）

【第 2 轮迭代】
  conversationHistory = [system, user, assistant(tool_calls), tool("导航成功")]
  │
  ├── LLM 看到工具结果："导航成功"
  │
  └── LLM 返回 text（无 tool_calls）——第 227 行
      "已为您打开百度首页"
      │
      ├── assistant 消息入历史（第 229 行）
      ├── 存入记忆（第 232 行）
      └── return "已为您打开百度首页"（第 251 行）
```

---

## 八、完整架构图

```
┌─ Agent.java ─────────────────────────────────────────────────────┐
│                                                                    │
│  run(userInput)  ──→ 准备工作 ──→ while(true)                      │
│                                                                    │
│  ┌──────────────────────────────────────────────────┐             │
│  │  conversationHistory (List<LlmClient.Message>)     │             │
│  │  [system] [user] [assistant+tc] [tool] [...]      │             │
│  └──────────────────────────────────────────────────┘             │
│       │                                                           │
│       │ + toolRegistry.getToolDefinitions()  (第 178 行)          │
│       ▼                                                           │
│  ┌──────────────────────────────────────────────────┐             │
│  │  LlmClient.chat(history, tools, listener)         │ ← 第 182 行│
│  │  → HTTP POST → LLM API → SSE 流式响应            │             │
│  │  → 流式渲染 reasoning + content                    │             │
│  └──────────────────────────────────────────────────┘             │
│       │                                                           │
│       ├── hasToolCalls()?                                         │
│       │   │                                                       │
│       │   YES ──→ 第 203 行 assistant+tc 入历史                   │
│       │          → 第 215 行 executeToolCalls()                   │
│       │          → ToolRegistry.executeTools()                    │
│       │          → 工具执行（内置/MCP）                            │
│       │          → 第 218 行 结果回灌 conversationHistory          │
│       │          → continue                                       │
│       │                                                           │
│       └── NO  ──→ 第 229 行 assistant 入历史                      │
│                   → 第 232 行 存入短期记忆                         │
│                   → 第 251 行 return answer                       │
│                                                                    │
│  两道防线:                                                        │
│    ① maybeCompactHistory()（第 164 行）—— Token 预算             │
│    ② budget.check()（第 165 行）—— Token/死循环/轮数             │
│                                                                    │
│  依赖注入:                                                        │
│    MemoryManager   ↔ 记忆管理                                     │
│    ToolRegistry    ↔ 工具注册与执行                                │
│    SkillRegistry   ↔ Skill 系统                                   │
│    Renderer        ↔ 流式渲染                                     │
└──────────────────────────────────────────────────────────────────┘
```

---

## 九、关键源码行速查

| 功能 | 行号 |
|------|------|
| ReAct 入口 `run()` | `Agent.java:128` |
| 准备阶段（记忆注入、用户消息入历史） | `Agent.java:130-145` |
| **while(true) 循环开始** | `Agent.java:154` |
| 用户取消检查 | `Agent.java:155` |
| 历史压缩 `maybeCompactHistory()` | `Agent.java:164` |
| 预算检查 `budget.check()` | `Agent.java:165` |
| **获取工具定义** `getToolDefinitions()` | `Agent.java:178` |
| **调 LLM** `llmClient.chat()` | `Agent.java:182` |
| **工具调用分支** `hasToolCalls()` | `Agent.java:198` |
| assistant+tc 入历史 | `Agent.java:203` |
| 执行工具 `executeToolCalls()` | `Agent.java:215` |
| 结果回灌 `conversationHistory.add(tool(...))` | `Agent.java:218` |
| continue 继续循环 | `Agent.java:224` |
| **正常退出** return answer | `Agent.java:227-251` |
| LLM 调用失败异常处理 | `Agent.java:253` |
| `exeucteToolCalls()` 方法 | `Agent.java:624-643` |
| `ToolCall` record | `LlmClient.java:176-178` |
| `Tool` record（工具定义） | `LlmClient.java:180` |
| `ChatResponse` record | `LlmClient.java:190-205` |
| 流式解析 SSE → `ChatResponse` | `AbstractOpenAiCompatibleClient.java:82-161` |
| 序列化请求体 `buildRequestBody()` | `AbstractOpenAiCompatibleClient.java:206-253` |
| **tools 字段写入 HTTP 请求** | `AbstractOpenAiCompatibleClient.java:240-250` |

---

## 十、面试要点

> ReAct 循环采用 **think-act-observe** 模式：每轮把 `conversationHistory`（system + 用户输入 + 历史助手回复 + 工具结果）和 `toolRegistry.getToolDefinitions()`（全部工具定义序列化）发给 LLM，由 LLM 自主决定调用工具还是返回最终回答。
>
> 正常退出条件只有 LLM 自决（不再调工具）。兜底退出包括 Token 超限、死循环检测、超硬轮数和用户取消。
>
> 同一轮多 tool_calls 的并行执行由 `ToolRegistry.executeTools()` 统一处理，结果按原始顺序回灌以保证 LLM 协议兼容。
>
> 从 Agent.java 到 LLM API 的完整链路是：`Agent:178` → `ToolRegistry.getToolDefinitions()` → `Agent:182` → `AbstractOpenAiCompatibleClient:62 buildRequestBody()` → HTTP `tools` 字段。

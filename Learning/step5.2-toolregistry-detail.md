# Step 5.2：ToolRegistry 详解 —— 工具注册、查找与执行

> 基于 `step5-react-toolregistry-guide.md` 和源码逐行分析。
> 建议搭配 `ToolRegistry.java`、`McpServerManager.java`、`McpClient.java` 一起阅读。

---

## 一、核心定位

**ToolRegistry** 是整个 PaiCLI 的"工具箱"——所有工具（内置 9 组 + MCP 动态注册）的统一注册、查找、执行中心。三种执行模式（ReAct / Plan-and-Execute / Multi-Agent）都复用同一套 ToolRegistry。

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
│  内置工具(11+)  +  MCP 工具(动态注册)          │
└─────────────────────────────────────────────┘
```

---

## 二、核心数据结构

**文件：** `src/main/java/com/paicli/tool/ToolRegistry.java`

### 2.1 两个 Map（第 82-83 行）

```java
private final Map<String, Tool> tools = new ConcurrentHashMap<>();           // 内置工具
private final Map<String, McpRegisteredTool> mcpTools = new ConcurrentHashMap<>();  // MCP 工具
```

| 维度       | `tools` Map                                 | `mcpTools` Map                                                 |
| -------- | ------------------------------------------- | -------------------------------------------------------------- |
| **存什么**  | `Map<String, Tool>`                         | `Map<String, McpRegisteredTool>`                               |
| **注册时机** | 构造时一次性注册（第 118-128 行）                       | MCP Server 连接后动态注册                                             |
| **命名**   | `read_file`, `write_file`                   | `mcp__filesystem__read_file`, `mcp__chrome-devtools__navigate` |
| **谁往这放** | `tools.put(...)` 各种 `registerXxxTools()` 方法 | `registerMcpToolOutput()`（第 1046 行）                            |
| **谁读它**  | `getToolDefinitions()`（第 1027 行）→ 发给 LLM    | 同左；`doExecuteTool()` 先查 mcpTools（第 1125 行）                     |

### 2.2 5 个核心 Record（第 1373-1422 行）

```java
// 参数的 JSON Schema 定义
private record Param(String name, String type, String description, boolean required) {}

// ★ 内置工具的核心模型
public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

// MCP 工具的注册包装（不直接暴露给外部，在 mcpTools Map 里）
private record McpRegisteredTool(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {}

// ReAct 循环传来的"调用请求"
public record ToolInvocation(String id, String name, String argumentsJson) {}

// 工具执行的结果
public record ToolExecutionResult(String id, String name, String argumentsJson,
                                   String result, long elapsedMillis, boolean timedOut,
                                   List<LlmClient.ContentPart> imageParts) {
    static ToolExecutionResult completed(ToolInvocation, ToolOutput, long elapsed);
    static ToolExecutionResult timedOut(ToolInvocation, long timeoutSeconds);
    static ToolExecutionResult failed(ToolInvocation, String message);
}
```

### 2.3 ToolExecutor 接口（第 1420-1422 行）

```java
@FunctionalInterface
public interface ToolExecutor {
    String execute(Map<String, String> args);
}
```

> **内置工具**执行走 `ToolExecutor.execute(Map)`，参数是键值对。
>
> **MCP 工具**走 `Function<String, ToolOutput>`，参数是原始 JSON 字符串（直接透传 MCP 协议）。

---

## 三、内置工具注册

### 3.1 构造时注册（第 107-128 行）

```java
public ToolRegistry() {
    this(DEFAULT_COMMAND_TIMEOUT_SECONDS, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS);
}

ToolRegistry(long commandTimeoutSeconds, long toolBatchTimeoutSeconds) {
    this.commandTimeoutSeconds = commandTimeoutSeconds;
    this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
    // 注册内置工具（第 118-128 行）
    registerFileTools();       // read_file, write_file, list_dir, glob_files, grep_code
    registerShellTools();      // execute_command
    registerCodeTools();       // create_project
    registerRagTools();        // search_code
    registerWebTools();        // web_search, web_fetch
    registerBrowserTools();    // browser_connect, browser_disconnect, browser_status
    registerMemoryTools();     // save_memory
    registerSkillTools();      // load_skill
    registerSnapshotTools();   // revert_turn
}
```

小计 **11 个内置工具**：

| 工具名                  | 类别    | 注册方法                      | 行号  |
| -------------------- | ----- | ------------------------- | --- |
| `read_file`          | 文件    | `registerFileTools()`     | 234 |
| `write_file`         | 文件    | `registerFileTools()`     | 253 |
| `list_dir`           | 文件    | `registerFileTools()`     | 297 |
| `glob_files`         | 文件    | `registerFileTools()`     | 321 |
| `grep_code`          | 文件    | `registerFileTools()`     | 332 |
| `execute_command`    | Shell | `registerShellTools()`    | 517 |
| `create_project`     | 代码    | `registerCodeTools()`     | 529 |
| `search_code`        | RAG   | `registerRagTools()`      | 578 |
| `web_search`         | 网络    | `registerWebTools()`      | 619 |
| `web_fetch`          | 网络    | `registerWebTools()`      | 630 |
| `browser_connect`    | 浏览器   | `registerBrowserTools()`  | 643 |
| `browser_disconnect` | 浏览器   | `registerBrowserTools()`  | 651 |
| `browser_status`     | 浏览器   | `registerBrowserTools()`  | 659 |
| `load_skill`         | Skill | `registerSkillTools()`    | 670 |
| `save_memory`        | 记忆    | `registerMemoryTools()`   | 708 |
| `revert_turn`        | 快照    | `registerSnapshotTools()` | 732 |

### 3.2 每个工具的注册结构（以 `read_file` 为例）

**文件：** `ToolRegistry.java:234-250`

```java
tools.put("read_file", new Tool(
    "read_file",                                           // name
    "读取文件内容（仅限项目根目录之内）；可用 offset/limit 按行读取，避免把大文件整段塞进上下文",  // description
    createParameters(                                      // parameters — JSON Schema
        new Param("path", "string", "文件路径", true),
        new Param("offset", "integer", "起始行号，1 表示第一行；省略时读取全文", false),
        new Param("limit", "integer", "最多读取多少行；省略时读取全文，最大 2000 行", false)
    ),
    args -> {                                               // executor — 执行逻辑
        Path safe = pathGuard.resolveSafe(args.get("path")); // 安全检查
        try {
            return readFileForTool(safe, args);               // 实际读文件
        } catch (Exception e) {
            return "读取文件失败: " + e.getMessage();
        }
    }
));
```

**`createParameters` 辅助方法（第 1006-1022 行）：**

```java
private JsonNode createParameters(Param... params) {
    ObjectNode parameters = mapper.createObjectNode();
    parameters.put("type", "object");
    ObjectNode properties = parameters.putObject("properties");
    ArrayNode required = parameters.putArray("required");

    for (Param param : params) {
        ObjectNode prop = properties.putObject(param.name());
        prop.put("type", param.type());
        prop.put("description", param.description());
        if (param.required()) {
            required.add(param.name());
        }
    }
    return parameters;
}
```

输出示例：

```json
{
  "type": "object",
  "properties": {
    "path": {"type": "string", "description": "文件路径"},
    "offset": {"type": "integer", "description": "起始行号"},
    "limit": {"type": "integer", "description": "最多读取多少行"}
  },
  "required": ["path"]
}
```

### 3.3 每个组注册方法的位置

| 方法                        | 行号      | 注册的工具                                                  |
| ------------------------- | ------- | ------------------------------------------------------ |
| `registerFileTools()`     | 232-348 | read_file, write_file, list_dir, glob_files, grep_code |
| `registerShellTools()`    | 516-523 | execute_command                                        |
| `registerCodeTools()`     | 528-572 | create_project                                         |
| `registerRagTools()`      | 577-613 | search_code                                            |
| `registerWebTools()`      | 618-640 | web_search, web_fetch                                  |
| `registerBrowserTools()`  | 642-667 | browser_connect, browser_disconnect, browser_status    |
| `registerSkillTools()`    | 669-705 | load_skill                                             |
| `registerMemoryTools()`   | 707-729 | save_memory                                            |
| `registerSnapshotTools()` | 731-746 | revert_turn                                            |

---

## 四、MCP 工具注册

MCP 工具的注册时机是 **MCP Server 连接成功之后**，由 `McpServerManager.start()` 触发。

### 4.1 MCP Server 启动流程

**文件：** `McpServerManager.java:401-436`

```java
private void start(McpServer server) {
    unregisterTools(server);                          // 1. 清理旧工具（第 402 行）
    server.close();                                   // 2. 关闭旧连接（第 403 行）
    server.status(McpServerStatus.STARTING);          // 3. 标记启动中（第 408 行）

    configLoader.prepare(server.config());            // 4. 展开 ${VAR}（第 414 行）
    McpTransport transport = createTransport(server.config());  // 5. 创建传输层（第 416 行）
    McpClient client = new McpClient(server.name(), transport); // 6. 创建 MCP 客户端（第 418 行）
    client.initialize();                              // 7. 发送 initialize 握手（第 419 行）

    List<McpToolDescriptor> tools = buildToolList(server, client);  // 8. 获取工具列表（第 423 行）
    replaceTools(server, client, tools);              // 9. ★ 注册到 ToolRegistry（第 425 行）

    server.client(client);
    server.tools(tools);
    server.markStarted();
    server.status(McpServerStatus.READY);             // 10. 标记就绪（第 430 行）
}
```

### 4.2 获取 MCP Server 返回的工具列表

**文件：** `McpServerManager.java:438-447`

```java
private List<McpToolDescriptor> buildToolList(McpServer server, McpClient client) throws IOException {
    List<McpToolDescriptor> tools = new ArrayList<>(client.listTools());  // 调用 MCP 协议的 tools/list
    if (client.supportsResources()) {
        List<McpResourceDescriptor> resources = client.listResources();
        resourceCache.put(server.name(), resources);
        tools.addAll(McpResourceTool.descriptors(server.name()));  // 资源也注册为虚拟工具
    }
    validateNoDuplicateTools(server.name(), tools);   // 检查重名
    return tools;
}
```

**`McpClient.listTools()`（`McpClient.java:72-95`）：**

```java
public List<McpToolDescriptor> listTools() throws IOException {
    JsonNode result = rpc.request("tools/list", JsonNodeFactory.instance.objectNode(), 30);
    JsonNode tools = result.path("tools");
    List<McpToolDescriptor> descriptors = new ArrayList<>();
    for (JsonNode tool : tools) {
        String name = tool.path("name").asText("");
        String description = tool.path("description").asText("");
        JsonNode schema = McpSchemaSanitizer.sanitize(tool.path("inputSchema"));  // ★ 裁剪 Schema 省 Token
        descriptors.add(new McpToolDescriptor(
            serverName, name,
            McpToolDescriptor.namespaced(serverName, name),  // → "mcp__filesystem__read_file"
            description, schema
        ));
    }
    return descriptors;
}
```

### 4.3 注册到 ToolRegistry

**文件：** `McpServerManager.java:449-453`

```java
private void replaceTools(McpServer server, McpClient client, List<McpToolDescriptor> tools) {
    toolRegistry.replaceMcpToolOutputsForServer(server.name(), tools,
        descriptor -> isResourceVirtualTool(descriptor)
            ? args -> ToolOutput.text(McpResourceTool.invoker(client, descriptor).apply(args))
            : args -> invokeMcpToolOutput(client, descriptor, args));  // ← 生成 invoker lambda
}
```

**`ToolRegistry.replaceMcpToolOutputsForServer()`（第 1074-1090 行）：**

```java
public synchronized void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                        Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
    String prefix = "mcp__" + serverName + "__";                          // 如 "mcp__filesystem__"

    // 先清理该 Server 的旧工具
    List<String> existing = mcpTools.keySet().stream()
        .filter(name -> name.startsWith(prefix)).toList();
    for (String toolName : existing) {
        mcpTools.remove(toolName);    // 从 mcpTools 移除
        tools.remove(toolName);       // 从 tools 移除
    }

    // 再注册新工具
    for (McpToolDescriptor descriptor : newTools) {
        registerMcpToolOutput(descriptor, invokerFactory.apply(descriptor));
    }
}
```

**`registerMcpToolOutput()`（第 1046-1058 行）：**

```java
public synchronized void registerMcpToolOutput(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {
    String toolName = descriptor.namespacedName();       // → "mcp__filesystem__read_file"

    // ★ 放入 mcpTools Map（执行时通过这个 Map 找到）
    McpRegisteredTool registered = new McpRegisteredTool(descriptor, invoker);
    mcpTools.put(toolName, registered);

    // ★ 也放入 tools Map（为了让 getToolDefinitions() 同时返回 MCP 工具给 LLM）
    tools.put(toolName, new Tool(
        toolName,
        mcpDescription(descriptor),
        descriptor.inputSchema(),
        args -> "MCP 工具不应通过 Map<String,String> 入口执行"    // 占位 executor
    ));
}
```

> ⚠️ **关键设计**：MCP 工具同时注册到两个 Map：
>
> - `mcpTools`：执行时通过它找到对应的 `invoker`（实际发 JSON-RPC 的那个 lambda）
> - `tools`：为了让 `getToolDefinitions()` 把 MCP 工具的"说明书"也发给 LLM

### 4.4 MCP tools 的命名规范

MCP Server 返回的原始工具名如 `read_file`，注册时加 `mcp__{server}__` 前缀：

```
McpToolDescriptor.namespaced(serverName, name)
    → McpToolDescriptor.namespaced("filesystem", "read_file")
    → "mcp__filesystem__read_file"
```

这个前缀是区分内置工具和 MCP 工具的唯一标识，也是审计判定 `shouldAudit("mcp__...")` → `true` 的依据。

---

## 五、工具定义序列化（给 LLM 看）

**文件：** `ToolRegistry.java:1027-1031`

```java
public List<com.paicli.llm.LlmClient.Tool> getToolDefinitions() {
    return tools.values().stream()
        .map(t -> new com.paicli.llm.LlmClient.Tool(t.name(), t.description(), t.parameters()))
        .toList();
}
```

**被 `Agent.java:178` 调用**，在每次 LLM 请求前获取全部工具定义。

`tools` Map 中包含了**所有工具**：

- 构造时注册的 9 组内置工具
- 动态注册的所有 MCP 工具（`mcp__*`）

最终被 `AbstractOpenAiCompatibleClient.java:240-250` 序列化为：

```json
{
  "tools": [
    {"type": "function", "function": {
      "name": "read_file",
      "description": "读取文件内容...",
      "parameters": {"type": "object", "properties": {...}}
    }},
    {"type": "function", "function": {
      "name": "mcp__filesystem__read_file",
      "description": "读取文件内容 (MCP server: filesystem, tool: read_file)",
      "parameters": {"type": "object", "properties": {...}}
    }}
  ]
}
```

---

## 六、工具执行链路（核心）

### 6.1 入口：executeTools（第 1196-1265 行）

```java
public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
    if (invocations == null || invocations.isEmpty()) return List.of();

    if (CancellationContext.isCancelled()) {
        return invocations.stream()
            .map(inv -> ToolExecutionResult.failed(inv, "用户取消了此次工具调用"))
            .toList();
    }

    if (invocations.size() == 1) {
        // ★ 单个工具 → 串行执行（第 1205-1210 行）
        ToolInvocation invocation = invocations.get(0);
        long startedAt = System.nanoTime();
        ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
        return List.of(ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt)));
    }

    // ★ 多个工具 → 线程池并行，最多 4 个并发（第 1212-1264 行）
    int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);  // MAX = 4
    ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
        Thread thread = new Thread(r, "paicli-tool-executor");
        thread.setDaemon(true);
        return thread;
    });

    try {
        List<Callable<ToolExecutionResult>> tasks = invocations.stream()
            .map(invocation -> (Callable<ToolExecutionResult>) () -> {
                long startedAt = System.nanoTime();
                ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
                return ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt));
            })
            .toList();

        List<Future<ToolExecutionResult>> futures =
            executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

        // ★ 结果按原始顺序组装返回！（第 1234-1255 行）
        List<ToolExecutionResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            ToolInvocation invocation = invocations.get(i);
            Future<ToolExecutionResult> future = futures.get(i);
            if (future.isCancelled()) {
                results.add(ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
            } else {
                results.add(future.get());  // 正常结果
            }
        }
        return results;
    } finally {
        executor.shutdownNow();
    }
}
```

### 6.2 单工具执行：doExecuteTool（第 1111-1167 行）

```java
protected ToolOutput doExecuteTool(String name, String argumentsJson) {
    if (CancellationContext.isCancelled()) {
        return ToolOutput.text("用户取消了此次工具调用");
    }

    Tool tool = tools.get(name);                             // 第 1115 行 — 查找
    if (tool == null) return ToolOutput.text("未知工具: " + name);

    boolean shouldAudit = shouldAudit(name);                 // 第 1120 行 — 是否需要审计
    long start = System.nanoTime();
    BrowserAuditMetadata auditMetadata = null;

    try {
        // ── MCP 工具路径（第 1125-1142 行）──
        McpRegisteredTool mcpTool = mcpTools.get(name);
        if (mcpTool != null) {
            BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, false);
            if (browserCheck.blocked()) throw new PolicyException(browserCheck.reason());

            ToolOutput output = mcpTool.invoker().apply(argumentsJson);  // ★ 调用 MCP invoker

            if (browserGuard != null) {
                browserGuard.applyAfterExecution(name, argumentsJson, output.text());
            }
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
            }
            return output;
        }

        // ── 内置工具路径（第 1145-1153 行）──
        JsonNode args = mapper.readTree(argumentsJson);
        Map<String, String> argMap = new HashMap<>();
        args.fields().forEachRemaining(entry ->
            argMap.put(entry.getKey(), entry.getValue().asText()));

        String result = tool.executor().execute(argMap);    // ★ 调用 ToolExecutor lambda
        if (shouldAudit) {
            auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
        }
        return ToolOutput.text(result);

    } catch (PolicyException e) {                            // 策略拦截
        if (shouldAudit) {
            auditLog.record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
        }
        return ToolOutput.text("🛡️ 策略拒绝: " + e.getMessage());
    } catch (Exception e) {                                  // 异常
        if (shouldAudit) {
            auditLog.record(AuditLog.AuditEntry.error(name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
        }
        return ToolOutput.text("工具执行失败: " + e.getMessage());
    }
}
```

---

## 七、两条执行路径完整对比

### 7.1 内置工具路径（以 `read_file` 为例）

```
Agent.java:215 executeToolCalls()
    │ 构建 ToolInvocation(id, "read_file", '{"path":"..."}')
    ▼
Agent.java:637 toolRegistry.executeTools(invocations)
    │ ToolRegistry.java:1196
    ▼（size==1 → 串行）
ToolRegistry.java:1208 executeToolOutput()
    ▼
ToolRegistry.java:1111 doExecuteTool("read_file", '{"path":"..."}')
    │
    ├── ToolRegistry.java:1115 tools.get("read_file")           → 找到 Tool
    ├── ToolRegistry.java:1125 mcpTools.get("read_file")        → null（不是 MCP 工具）
    │
    ├── ToolRegistry.java:1145 解析 JSON 参数 → Map<String,String>
    ├── ToolRegistry.java:1149 tool.executor().execute(argMap)  → 调用 lambda
    │   │
    │   ▼（进入 read_file 注册时传入的 lambda——第 242 行）
    │   args -> {
    │       Path safe = pathGuard.resolveSafe(args.get("path"));  // PathGuard 安全检查
    │       return readFileForTool(safe, args);                    // 实际读文件
    │   }
    │   │
    │   ▼（readFileForTool 方法——第 350 行）
    │   Files.readString(file) → "文件内容:\npublic class Agent {..."
    │
    └── return ToolOutput.text("文件内容:\npublic class Agent {...")

ToolExecutionResult.completed(invocation, output, elapsed)
    ▼ 返回 Agent.java:215
for (ToolExecutionResult toolResult : toolResults) {
    conversationHistory.add(LlmClient.Message.tool(
        toolResult.id(), toolResult.result()    // ← 结果回灌！
    ));
}
continue;  // → 下一轮 ReAct 循环
```

### 7.2 MCP 工具路径（以 `mcp__filesystem__read_file` 为例）

```
Agent.java:215 executeToolCalls()
    │ 构建 ToolInvocation(id, "mcp__filesystem__read_file", '{"path":"..."}')
    ▼
ToolRegistry.java:1111 doExecuteTool("mcp__filesystem__read_file", '{"path":"..."}')
    │
    ├── ToolRegistry.java:1115 tools.get("mcp__filesystem__read_file")  → 找到 Tool
    ├── ToolRegistry.java:1125 mcpTools.get("mcp__filesystem__read_file") → ★ 找到 McpRegisteredTool!
    │
    ├── ToolRegistry.java:1127 BrowserGuard 检查
    ├── ToolRegistry.java:1132 mcpTool.invoker().apply(argumentsJson)  → 调用 invoker lambda
    │   │
    │   ▼（invoker 是 replaceTools 时注册的——McpServerManager.java:451）
    │   args -> invokeMcpToolOutput(client, descriptor, args)
    │   │
    │   ▼（McpServerManager.java:495-501）
    │   private static ToolOutput invokeMcpToolOutput(McpClient client, ...) {
    │       return client.callToolOutput(descriptor.name(), argumentsJson);
    │   }
    │   │
    │   ▼（McpClient.java:101-116）
    │   public ToolOutput callToolOutput(String toolName, String argumentsJson) {
    │       JsonNode args = MAPPER.readTree(argumentsJson);
    │       ObjectNode params = McpCallToolRequest.toJson(toolName, args);
    │       JsonNode result = rpc.request("tools/call", params, 60);   // ★ JSON-RPC 调用
    │       McpCallToolResult callResult = MAPPER.treeToValue(result, McpCallToolResult.class);
    │       return callResult.toToolOutput();
    │   }
    │   │
    │   ▼（JSON-RPC 网络层）
    │   发送: {"jsonrpc":"2.0","id":1,"method":"tools/call",
    │          "params":{"name":"read_file","arguments":{"path":"..."}}}
    │   接收: {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"文件内容..."}]}}
    │
    ├── ToolRegistry.java:1139-1141 审计记录（MCP 工具全部审计）
    └── return ToolOutput.text("文件内容...")

ToolExecutionResult.completed(...)
    ▼ 返回 Agent.java:215
结果回灌 conversationHistory → continue
```

### 7.3 核心区别

| 维度              | 内置工具                                       | MCP 工具                                       |
| --------------- | ------------------------------------------ | -------------------------------------------- |
| **mcpTools 查找** | `mcpTools.get(name)` → `null`              | `mcpTools.get(name)` → `McpRegisteredTool`   |
| **执行器类型**       | `ToolExecutor.execute(Map<String,String>)` | `Function<String, ToolOutput>.apply(String)` |
| **参数传递**        | JSON 解析为 `Map`                             | 原始 JSON 字符串透传                                |
| **安全策略**        | `PathGuard.resolveSafe()` / `CommandGuard` | `BrowserGuard` 浏览器策略                         |
| **审计**          | 仅 `AUDIT_TOOLS` 集合中的工具                     | **全部 MCP 工具**（`name.startsWith("mcp__")`）    |
| **本质**          | **同 JVM 方法调用**                             | **跨进程 JSON-RPC 通信**                          |

---

## 八、审计与策略

### 8.1 审计判定（第 1275-1277 行）

```java
private static boolean shouldAudit(String name) {
    return AUDIT_TOOLS.contains(name) || (name != null && name.startsWith("mcp__"));
}
```

危险内置工具集合（第 81 行）：

```java
private static final Set<String> AUDIT_TOOLS = Set.of(
    "write_file", "execute_command", "create_project", "revert_turn"
);
```

### 8.2 策略拦截

在 `doExecuteTool` 中有三层安全防护：

| 层            | 机制                | 触发条件                                       | 行号            |
| ------------ | ----------------- | ------------------------------------------ | ------------- |
| **1. 策略拦截**  | `PolicyException` | PathGuard 路径越界 / CommandGuard 黑名单 / 文件大小超限 | 第 1154 行      |
| **2. 浏览器策略** | `BrowserGuard`    | MCP Chrome 工具访问敏感页面                        | 第 1127 行      |
| **3. 审计日志**  | `AuditLog`        | 危险工具 + 所有 MCP 工具                           | 第 1139-1164 行 |

拦截优先级：用户取消 → 浏览器策略 → PolicyException → 异常 → 正常返回。

---

## 九、常量配置

**文件：** `ToolRegistry.java:61-81`

| 常量                                   | 值     | 用途         |
| ------------------------------------ | ----- | ---------- |
| `DEFAULT_COMMAND_TIMEOUT_SECONDS`    | 60    | 命令执行超时     |
| `DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS` | 90    | 工具批次总超时    |
| `MAX_PARALLEL_TOOLS`                 | 4     | 最大并行工具数    |
| `MAX_COMMAND_OUTPUT_CHARS`           | 8,000 | 命令输出截断长度   |
| `MAX_READ_FILE_LINES`                | 2,000 | 单次读取最大行数   |
| `MAX_GREP_RESULTS`                   | 200   | grep 最大结果数 |
| `MAX_WRITE_FILE_BYTES`               | 5 MB  | 写入文件上限     |

---

## 十、关键源码行速查

| 功能                               | 位置                              |
| -------------------------------- | ------------------------------- |
| `tools` Map（内置工具）                | `ToolRegistry.java:82`          |
| `mcpTools` Map（MCP 工具）           | `ToolRegistry.java:83`          |
| 构造时注册所有内置工具                      | `ToolRegistry.java:118-128`     |
| 注册 `read_file`                   | `ToolRegistry.java:234-250`     |
| 注册 `write_file`                  | `ToolRegistry.java:253-294`     |
| 每个工具的数据结构 `record Tool`          | `ToolRegistry.java:1375`        |
| `ToolExecutor` 接口                | `ToolRegistry.java:1420`        |
| **获取全部工具定义（发给 LLM）**             | `ToolRegistry.java:1027`        |
| 注册 MCP 工具                        | `ToolRegistry.java:1046-1058`   |
| 批量替换 MCP 工具                      | `ToolRegistry.java:1074-1090`   |
| **单工具执行核心** `doExecuteTool()`    | `ToolRegistry.java:1111`        |
| 内置工具执行路径                         | `ToolRegistry.java:1145-1153`   |
| MCP 工具执行路径                       | `ToolRegistry.java:1125-1142`   |
| 并行执行入口 `executeTools()`          | `ToolRegistry.java:1196`        |
| 串行分支                             | `ToolRegistry.java:1205-1210`   |
| 并行分支 + 按顺序组装                     | `ToolRegistry.java:1212-1264`   |
| 审计判定 `shouldAudit()`             | `ToolRegistry.java:1275`        |
| MCP Server 获取工具列表                | `McpServerManager.java:438-447` |
| MCP 工具注册到 ToolRegistry           | `McpServerManager.java:449-453` |
| MCP 调用入口 `invokeMcpToolOutput()` | `McpServerManager.java:495-501` |
| JSON-RPC 调用 `callToolOutput()`   | `McpClient.java:101-116`        |
| 获取 MCP Server 工具列表 `listTools()` | `McpClient.java:72-95`          |

---

## 十一、面试要点

> ToolRegistry 统一管理 11+ 个内置工具和动态注册的 MCP 工具，内置工具通过 `ToolExecutor.execute(Map)` 执行，MCP 工具通过 `mcp__{server}__{tool}` 命名前缀区分并转发到 `McpServerManager` → `McpClient.callToolOutput()`。
>
> 调用 LLM 时通过 `getToolDefinitions()` 把 `tools` Map 中所有工具的名称+描述+参数 Schema 序列化为 JSON 放到请求的 `tools` 字段。
>
> MCP 工具同时注册到 `mcpTools`（执行用）和 `tools`（展示给 LLM 用）两个 Map。
>
> 多个工具调用默认最多 4 个并发（`ExecutorService.invokeAll()`），结果按原始顺序组装返回以保证 LLM 协议兼容。危险工具（write_file / execute_command / create_project / revert_turn + 所有 MCP 工具）自动纳入审计日志。
>
> doExecuteTool 的执行优先级：用户取消 → 浏览器策略拦截 → MCP 路径 → 内置工具路径 → 策略拒绝 → 异常，三层安全防护（PathGuard / CommandGuard / BrowserGuard）。

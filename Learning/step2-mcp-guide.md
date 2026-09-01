# 第 2.1 步：MCP 集成 —— 结合源码的逐层解析

> 搭配 `learningPlan.md` 第 2.1 节食用。
>
> MCP 模块是全项目最大的模块（**29 个文件**），分三部分理解：协议层 → 传输层 → 集成层。

---

## 整体架构：MCP 在 PaiCLI 中的位置

```text
┌────────────────────────────────────────────────────────────────────────┐
│                            Agent (ReAct 循环)                          │
│  LLM 决定调用 tool → 通过 ToolRegistry.executeToolOutput() 执行        │
└────────────────────────────────┬───────────────────────────────────────┘
                                 │
                                 ▼
┌────────────────────────────────────────────────────────────────────────┐
│                          ToolRegistry                                  │
│  工具查找 → 发现 mcp__ 前缀 → 转发到 McpServerManager                  │
│  安全策略 → HitlToolRegistry 拦截危险 MCP 工具                         │
└────────────────────────────────┬───────────────────────────────────────┘
                                 │
                                 ▼
┌────────────────────────────────────────────────────────────────────────┐
│                          McpServerManager                              │
│  管理多个 MCP Server 的生命周期                                         │
│  启动/停止/重启/禁用/启用                                               │
└──────┬──────────────────────────────────────────────┬──────────────────┘
       │                                              │
       ▼                                              ▼
┌──────────────┐                              ┌──────────────┐
│ McpClient A   │                              │ McpClient B   │
│ (stdio)       │                              │ (HTTP)        │
│               │                              │               │
│ JsonRpcClient │                              │ JsonRpcClient │
└───────┬───────┘                              └───────┬───────┘
        │                                              │
        ▼                                              ▼
┌──────────────┐                              ┌──────────────────────┐
│ StdioTransport│                              │ StreamableHttpTransport│
│ (子进程管道)   │                              │ (HTTP + SSE)          │
└───────┬───────┘                              └──────────┬───────────┘
        │                                                  │
        ▼                                                  ▼
┌──────────────┐                              ┌──────────────────┐
│ npx/uvx/...  │                              │ Remote MCP Server │
│ 本地进程       │                              │ (HTTP API)        │
└──────────────┘                              └──────────────────┘
```

---

## Day 1：协议层 —— 理解 MCP 的数据结构

### 你要读的文件

| 优先级 | 文件 | 用途 | 行数 |
|--------|------|------|------|
| ★★★ | `mcp/protocol/McpToolDescriptor.java` | MCP 工具的数据结构 | 16 |
| ★★★ | `mcp/protocol/McpSchemaSanitizer.java` | Schema 自动裁剪 | 103 |
| ★★★ | `mcp/jsonrpc/JsonRpcClient.java` | JSON-RPC 2.0 通信客户端 | ~150 |
| ★★☆ | `mcp/protocol/McpInitializeRequest.java` | 初始化请求构建 | 自己看 |
| ★★☆ | `mcp/protocol/McpCallToolRequest.java` | 工具调用请求构建 | 自己看 |

### McpToolDescriptor —— MCP 工具的数据模型

```java
// mcp/protocol/McpToolDescriptor.java:5-15
public record McpToolDescriptor(
        String serverName,           // 所属 MCP Server 名称（如 "chrome-devtools"）
        String name,                 // 工具原始名称（如 "list_pages"）
        String namespacedName,       // 全局唯一名：mcp__chrome-devtools__list_pages
        String description,          // 工具描述
        JsonNode inputSchema         // JSON Schema 参数定义
) {
    public static String namespaced(String serverName, String toolName) {
        return "mcp__" + serverName + "__" + toolName;
    }
}
```

**为什么要 namespace？**

因为 LLM 能看到**全部工具**（9 个内置 + 所有 MCP 工具的合并列表）。如果两个 MCP Server 都定义了 `read_file` 工具，不加前缀就会冲突。`mcp__{serverName}__{toolName}` 这种命名规则确保了全局唯一性。

### McpSchemaSanitizer —— Schema 裁剪为什么是必须的？

```java
// mcp/protocol/McpSchemaSanitizer.java:18-41
public static JsonNode sanitize(JsonNode schema) {
    // 1. 安全的深拷贝，不修改原始节点
    JsonNode copy = schema.deepCopy();
    // 2. 递归清理
    JsonNode cleaned = clean(copy);
    // 3. 保证顶级一定有 type + properties
    if (!obj.has("type")) obj.put("type", "object");
    if (!obj.has("properties")) obj.putObject("properties");
    return obj;
}

private static JsonNode clean(JsonNode node) {
    // 递归删除以下 JSON Schema 关键字（它们对 LLM 无意义，白占 token）
    object.remove("$schema");    // JSON Schema 版本声明
    object.remove("$id");        // Schema ID
    object.remove("$ref");       // 引用（大部分 LLM 不支持）

    // 把 anyOf/oneOf 合并为 description 文本
    String alternatives = mergeAlternatives(object, "anyOf", "oneOf");
    if (!alternatives.isEmpty()) {
        object.put("description",
            existing + " (" + alternatives + ")");
    }

    // description 超长截断（>1000 字符）
    object.put("description", truncateDescription(child.asText()));
}
```

**为什么要做这个？** 很多 MCP Server 的 JSON Schema 包含大量元数据（`$schema`、`$id`、`$ref`），这些对 LLM 理解如何调用工具**毫无帮助**，却在上下文中占据大量 token。裁剪后能省 30-60% 的 Schema 空间。

**面试亮点** 💡

> Schema 裁剪是为了解决 LLM 上下文窗口有限的问题。MCP 工具的 JSON Schema 可能非常庞大（一些数据库工具的 schema 超过 5000 token），裁剪掉 `$schema`、`$id`、`$ref` 等对 LLM 无用的关键字，将 `anyOf/oneOf` 合并为 description 文本，并把 description 截断到 1000 字符，可以有效节省 token 占用。

### JsonRpcClient —— 手写 JSON-RPC 2.0 客户端

```java
// mcp/jsonrpc/JsonRpcClient.java:19-73
public class JsonRpcClient implements AutoCloseable {
    private final McpTransport transport;              // 底层传输层
    private final AtomicLong ids = new AtomicLong(1);   // 递增的请求 ID
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    public JsonNode request(String method, JsonNode params, long timeoutSeconds) throws IOException {
        long id = ids.getAndIncrement();
        // 构建 JSON-RPC 2.0 请求
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);

        // 创建 Future，放入等待队列
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        // 超时调度器：到期自动取消
        scheduler.schedule(() -> {
            CompletableFuture<JsonNode> removed = pending.remove(id);
            if (removed != null)
                removed.completeExceptionally(new TimeoutException("..."));
        }, timeoutSeconds, TimeUnit.SECONDS);

        transport.send(request);                    // 发送到子进程 / HTTP
        return future.get(timeoutSeconds + 1, ...); // 等待响应
    }
}
```

**核心机制**：每个 JSON-RPC 请求都有一个递增的 `id`，响应通过 `id` 匹配到对应的 `CompletableFuture`，从而实现**异步请求-响应**模式。

**消息接收处理**（注册在 `McpTransport.onReceive()` 回调中）：

```java
// 在构造函数中注册
this.transport.onReceive(this::handleMessage);

private void handleMessage(JsonNode message) {
    JsonNode idNode = message.get("id");
    if (idNode != null && idNode.isNumber()) {
        // 有 id → 是请求的响应 → 从 pending 找对应 Future
        CompletableFuture<JsonNode> future = pending.remove(idNode.longValue());
        if (future != null) {
            JsonNode error = message.get("error");
            if (error != null) future.completeExceptionally(new JsonRpcException(...));
            else future.complete(message.get("result"));
        }
    } else {
        // 无 id → 是服务端推送的通知 → 分发给通知监听器
        notificationListeners.forEach(l -> l.accept(message));
    }
}
```

---

## Day 2：传输层 —— stdio vs HTTP

### 你要读的文件

| 优先级 | 文件 | 用途 | 行数 |
|--------|------|------|------|
| ★★★ | `mcp/transport/McpTransport.java` | 传输层接口 | 29 |
| ★★★ | `mcp/transport/StdioTransport.java` | 子进程管道通信 | 162 |
| ★★★ | `mcp/transport/StreamableHttpTransport.java` | HTTP + SSE 通信 | ~200 |
| ★☆ | `mcp/mention/AtMentionParser.java` | @资源引用解析 | 了解即可 |

### McpTransport 接口 —— 多态设计的核心

```java
// mcp/transport/McpTransport.java:9-28
public interface McpTransport extends AutoCloseable {
    void send(JsonNode message) throws IOException;            // 发送消息
    void onReceive(Consumer<JsonNode> listener);               // 注册消息接收回调
    default List<String> stderrLines() { return List.of(); }   // 子进程 stderr（仅 stdio 有）
    default Long processId() { return null; }                  // 子进程 PID（仅 stdio 有）
    default String transportName() { return "unknown"; }
    void close();
}
```

**设计洞察**：这个接口只有 6 个方法，却是整个 MCP 系统的抽象核心。`ToolRegistry` 和 `Agent` 不关心 MCP 工具是通过本地子进程还是远程 HTTP 调用的——它们只认 `McpTransport`。

### StdioTransport —— 子进程管道通信

```java
// mcp/transport/StdioTransport.java:32-48
public StdioTransport(String command, List<String> args, Map<String, String> env, Path workingDir) {
    // ProcessBuilder 启动子进程（如 npx -y chrome-devtools-mcp@latest）
    ProcessBuilder builder = new ProcessBuilder(command, args...);
    builder.directory(workingDir);          // 在项目目录下启动
    if (env != null) builder.environment().putAll(env);

    Process process = builder.start();
    this.stdin = new BufferedWriter(process.getOutputStream());  // 子进程 stdin

    startStdoutReader();  // 读子进程 stdout → JSON-RPC 响应 → listener
    startStderrReader();  // 读子进程 stderr → 环形缓冲区 → /mcp logs
}
```

**Stdout 读取线程**：读子进程的 stdout（按行），每行是一条 JSON-RPC 消息，反序列化后派发给所有 `listener`。

**Stderr 读取线程**：读子进程的 stderr，存到环形缓冲区（最多保留 200 行），用户通过 `/mcp logs <name>` 查看。

**Close 的三阶段终止策略**：

```java
// StdioTransport.java:86-113
public void close() {
    closed = true;
    // 阶段 1：关 stdin → 子进程读到 EOF（优雅退出窗口 1 秒）
    stdin.close();
    if (process.waitFor(1, TimeUnit.SECONDS)) return;

    // 阶段 2：发 SIGTERM（优雅退出窗口 2 秒）
    process.destroy();
    if (process.waitFor(2, TimeUnit.SECONDS)) return;

    // 阶段 3：强制 SIGKILL
    process.destroyForcibly();
}
```

### StreamableHttpTransport —— HTTP + SSE

```java
// mcp/transport/StreamableHttpTransport.java:42-80
public void send(JsonNode message) throws IOException {
    // 构建 HTTP POST 请求
    RequestBody body = RequestBody.create(MAPPER.writeValueAsString(message), JSON);
    Request.Builder builder = new Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
        .post(body);

    // 如果有 sessionId，带在请求头中
    if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);

    try (Response response = client.newCall(builder.build()).execute()) {
        // 从响应头获取 sessionId（首次连接时服务器返回）
        String newSession = response.header("Mcp-Session-Id");
        if (newSession != null) sessionId = newSession;

        String contentType = response.header("Content-Type", "");
        String raw = responseBody.string();

        // 响应可以是 JSON 或 SSE 流
        List<JsonNode> messages = contentType.contains("text/event-stream")
            ? parseSse(raw)          // SSE 解析器
            : List.of(MAPPER.readTree(raw));

        for (JsonNode node : messages) {
            listeners.forEach(l -> l.accept(node));
        }
    }
}
```

**SSE 解析**：`parseSse()` 将 `text/event-stream` 格式解析为逐条 JSON 消息。SSE 的每条 `data:` 行就是一个 JSON-RPC 响应或通知。

### Stdio vs HTTP 对比（代码验证）

在 `McpServerManager` 中根据配置选择传输方式：

```java
// McpServerManager.java:497-502
private McpTransport createTransport(McpServerConfig config) throws IOException {
    if (config.isHttp()) {
        // mcp.json 中有 "url" 字段 → HTTP
        return new StreamableHttpTransport(config.getUrl(), config.getHeaders());
    }
    // mcp.json 中有 "command" 字段 → stdio
    return new StdioTransport(config.getCommand(), config.getArgs(), config.getEnv(), projectDir);
}
```

其中 `config.isHttp()` 的判断逻辑在 `McpServerConfig` 中：

```java
public boolean isHttp() {
    return url != null && !url.isBlank();
}
public boolean isStdio() {
    return command != null && !command.isBlank();
}
```

---

## Day 3：MCP 集成到 Agent —— 最核心的部分

### 你要读的文件

| 优先级 | 文件 | 用途 | 行数 |
|--------|------|------|------|
| ★★★ | `mcp/McpServerManager.java` | 多 Server 生命周期管理 | ~570 |
| ★★★ | `mcp/McpClient.java` | 单个 Server 通信客户端 | ~270 |
| ★★★ | `mcp/McpServer.java` | Server 状态模型 | ~105 |
| ★★★ | `tool/ToolRegistry.java` (MCP 相关部分) | MCP 工具动态注册 | 看 1050-1090 |
| ★★★ | `hitl/HitlToolRegistry.java` | HITL 对 MCP 的审批 | ~80 |
| ★★ | `mcp/config/McpConfigLoader.java` | mcp.json 加载 | ~190 |

### 整体生命周期

```text
PaiCLI 启动
    │
    ├── McpConfigLoader.load()                    读取 ~/.paicli/mcp.json + 项目 .paicli/mcp.json
    │      ↓
    ├── McpServerManager.loadConfiguredServers()  创建 McpServer 实例列表
    │      ↓
    ├── McpServerManager.startAll()               并行启动所有 Server
    │      │
    │      ├── 对每个 Server:
    │      │   ├── configLoader.prepare(config)    展开 ${VAR}、校验配置
    │      │   ├── createTransport(config)         创建 StdioTransport 或 StreamableHttpTransport
    │      │   ├── McpClient.initialize()          发送 initialize 请求
    │      │   │   ↓
    │      │   ├── client.listTools()              获取工具列表
    │      │   ├── McpSchemaSanitizer.sanitize()   裁剪每个工具的 Schema
    │      │   ├── client.listResources()          获取资源列表（可选）
    │      │   └── replaceTools()                  注册到 ToolRegistry
    │      │
    │      └── 启动完成 → 状态变为 READY 或 ERROR
    │
    └── Agent 开始接收用户输入
           │
           └── LLM 调用 mcp__xxx__xxx 工具
                  │
                  └── ToolRegistry → McpClient.callToolOutput() → 返回结果
```

### McpServerManager.start() —— 单 Server 启动的完整链路

```java
// McpServerManager.java:401-428
private void start(McpServer server) {
    unregisterTools(server);   // 1. 先清理旧工具注册
    server.close();
    if (server.config().isDisabled()) {
        server.status(DISABLED); return;
    }
    server.status(STARTING);   // 2. 标记为"启动中"

    try {
        // 3. 展开环境变量 ${VAR} 并校验配置
        configLoader.prepare(server.config());

        // 4. 创建传输层
        McpTransport transport = createTransport(server.config());

        // 5. 创建 MCP 客户端，发送 initialize
        McpClient client = new McpClient(server.name(), transport);
        client.initialize();

        // 6. 注册通知处理器（监听 tools/list_changed 等）
        registerNotificationHandlers(server, client);

        // 7. 获取工具列表 + 资源列表，构建 McpToolDescriptor[]
        List<McpToolDescriptor> tools = buildToolList(server, client);

        // 8. 动态注册到 ToolRegistry
        replaceTools(server, client, tools);

        // 9. 标记就绪
        server.client(client);
        server.tools(tools);
        server.markStarted();
        server.status(READY);
    } catch (Exception e) {
        server.close();
        server.errorMessage(e.getMessage());
        server.status(ERROR);
    }
}
```

### MCP 工具如何注入 ToolRegistry

这是整个集成中最巧妙的设计。`McpServerManager.replaceTools()` 将 MCP 工具的执行器注册到 `ToolRegistry` 中：

```java
// McpServerManager.java:442-447
private void replaceTools(McpServer server, McpClient client, List<McpToolDescriptor> tools) {
    toolRegistry.replaceMcpToolOutputsForServer(server.name(), tools,
        descriptor -> isResourceVirtualTool(descriptor)
            ? args -> ToolOutput.text(McpResourceTool.invoker(client, descriptor).apply(args))
            : args -> invokeMcpToolOutput(client, descriptor, args));
}
```

`ToolRegistry` 内部：

```java
// ToolRegistry.java:1074-1090
public synchronized void replaceMcpToolOutputsForServer(String serverName,
        List<McpToolDescriptor> newTools,
        Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {

    // 1. 清理该 Server 的旧工具
    String prefix = "mcp__" + serverName + "__";
    mcpTools.keySet().stream()
        .filter(name -> name.startsWith(prefix))
        .forEach(name -> { mcpTools.remove(name); tools.remove(name); });

    // 2. 注册新工具
    for (McpToolDescriptor descriptor : newTools) {
        registerMcpToolOutput(descriptor, invokerFactory.apply(descriptor));
    }
}
```

**执行时**，`ToolRegistry.doExecuteTool()` 会先查 `mcpTools` 表，如果找到就走 MCP 执行路径：

```java
// ToolRegistry.java:1125
McpRegisteredTool mcpTool = mcpTools.get(name);
if (mcpTool != null) {
    // 走 MCP 执行路径（有额外的浏览器安全检查）
    BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, false);
    auditMetadata = browserCheck.metadata();
    // ... 调用 mcpTool.invoker()
}
```

### 通知机制 —— 动态更新工具列表

MCP Server 可以在运行时通知客户端工具或资源列表发生了变化。

```java
// McpServerManager.java:454-473
private void registerNotificationHandlers(McpServer server, McpClient client) {
    NotificationRouter router = new NotificationRouter();

    // 工具列表变化 → 重新获取并更新 ToolRegistry
    router.on("notifications/tools/list_changed", ignored -> {
        List<McpToolDescriptor> tools = buildToolList(server, client);
        replaceTools(server, client, tools);
        server.tools(tools);
    });

    // 资源列表变化 → 刷新资源缓存
    router.on("notifications/resources/list_changed", ignored ->
        resourceCache.invalidateServer(server.name()));

    // 单个资源更新 → 标记该资源为"需要重新读取"
    router.on("notifications/resources/updated", params -> {
        String uri = params.path("uri").asText("");
        if (!uri.isBlank()) resourceCache.invalidateResource(server.name(), uri);
    });

    client.onNotification(router);
}
```

### HITL 对 MCP 工具的审批

所有 `mcp__` 前缀的工具都受到 HITL 审批管控：

```java
// ApproalPolicy.java 中
public static boolean requiresApproval(String toolName) {
    // 内置危险工具 + 所有 mcp__ 前缀工具
    return DANGEROUS_TOOLS.contains(toolName) || toolName.startsWith("mcp__");
}
```

用户在 Agent 调用 MCP 浏览器工具时的审批流程：

```text
LLM 调用 mcp__chrome-devtools__list_pages
    │
    ▼
HitlToolRegistry.executeToolOutput()
    │
    ├── HITL 关闭？ → 直接执行
    ├── 需要浏览器审批？ → BrowserGuard 检查目标 URL 是否敏感
    ├── 已永久批准该工具？ → 直接执行
    └── 需要人工审批？
        │
        ▼
    HitlHandler.requestApproval()
        │
        ├── 用户批准 → super.doExecuteTool() 执行
        ├── 用户拒绝 → 返回 "[HITL] 操作已被拒绝"
        └── 用户跳过 → 返回 "[HITL] 操作已被跳过"
```

### MCP Server 配置

```json
// ~/.paicli/mcp.json — 用户级配置
{
  "mcpServers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
    }
  }
}

// .paicli/mcp.json — 项目级配置（会合并到用户级之上）
{
  "mcpServers": {
    "my-db-server": {
      "command": "java",
      "args": ["-jar", "my-mcp-server.jar"],
      "env": {
        "DB_URL": "jdbc:sqlite:${PROJECT_DIR}/data.db"
      }
    }
  }
}
```

配置优先级：`用户级 (~/.paicli/mcp.json)` < `项目级 (.paicli/mcp.json)`（项目级覆盖同名的用户级）。

内置特殊 Server：`step_search` —— 如果配置了 `STEP_API_KEY` 环境变量，会自动添加一个 HTTP MCP Server，提供 `web_search` 和 `web_fetch` 工具（`McpConfigLoader.java:71-83`）。

---

## 面试亮点总结

学完 MCP 模块后，你应该能用以下三句话向面试官介绍这个设计：

### 1. 协议层
> MCP 工具通过 JSON-RPC 2.0 协议与 Server 通信，请求 ID 递增映射到 CompletableFuture 实现异步等待。McpSchemaSanitizer 自动裁剪 JSON Schema 中的无关元数据（$schema, $id, $ref），防止 LLM 上下文被无用信息撑爆。

### 2. 传输层
> 双传输层抽象使 Agent 统一对接本地和远程 MCP 工具。StdioTransport 通过 ProcessBuilder 启动子进程并管理其生命周期（stdin 写入请求 → stdout 读取响应 → 3 阶段终止策略）；StreamableHttpTransport 通过 OkHttp 发起 POST 请求并解析 SSE 流式响应。对业务代码完全透明。

### 3. 集成层
> MCP 工具通过 `mcp__{serverName}__{toolName}` 命名规约注册到 ToolRegistry 中，与 9 个内置工具统一暴露给 LLM。ToolRegistry 执行时先查 mcpTools 表，命中后走 MCP 执行路径。所有 mcp__ 工具默认纳入 HITL 审批范围，危险操作（如浏览器导航）需要用户确认。Server 支持通过 `notifications/tools/list_changed` 通知动态刷新工具列表，无需重启 Agent。

---

## 自检清单

读完本节后，你应该能回答：

| 问题 | 答案速查 |
|------|---------|
| MCP 工具名为什么带 `mcp__` 前缀？ | 避免不同 Server 的工具名冲突 |
| Schema 裁剪做了什么？ | 删 `$schema/$id/$ref`，合并 `anyOf/oneOf` 为文本，截断 description 到 1000 字符 |
| Stdio 和 HTTP 怎么选择的？ | `McpServerManager.createTransport()` 判断 config 有 url 还是 command |
| StdioTransport 关闭时分几步？ | 关 stdin（1s）→ SIGTERM（2s）→ SIGKILL |
| 启动时怎么并行启动多个 Server？ | `startAll()` 用 `Executors.newFixedThreadPool(min(N, 8))` 并行 |
| MCP 工具怎么注册到 ToolRegistry？ | `replaceMcpToolOutputsForServer()` 清理旧工具 → 遍历新工具调用 `registerMcpToolOutput()` |
| HITL 怎么管控 MCP 工具？ | 所有 `mcp__` 前缀工具都被 `ApprovalPolicy.requiresApproval()` 标记为需要审批 |
| 通知机制解决了什么问题？ | Server 运行时更新了工具列表，无需重启 Agent 即可同步到 ToolRegistry |

---

## 附录：MCP 模块文件速查

| 包 | 文件 | 核心职责 |
|----|------|---------|
| `mcp/` | `McpServerManager.java` | 多 Server 生命周期管理、工具注册、HITL 集成 |
| `mcp/` | `McpClient.java` | 单个 Server 的通信客户端（listTool/callTool/listResource/readResource） |
| `mcp/` | `McpServer.java` | Server 状态模型（READY/STARTING/ERROR/DISABLED） |
| `mcp/protocol/` | `McpToolDescriptor.java` | 工具描述符 record |
| `mcp/protocol/` | `McpSchemaSanitizer.java` | Schema 裁剪 |
| `mcp/transport/` | `McpTransport.java` | 传输层接口 |
| `mcp/transport/` | `StdioTransport.java` | 子进程管道实现 |
| `mcp/transport/` | `StreamableHttpTransport.java` | HTTP + SSE 实现 |
| `mcp/jsonrpc/` | `JsonRpcClient.java` | JSON-RPC 2.0 协议客户端 |
| `mcp/config/` | `McpConfigLoader.java` | mcp.json 加载 + `${VAR}` 展开 |
| `mcp/resources/` | `McpResourceCache.java` | Resource 缓存 + 失效 |
| `mcp/notifications/` | `NotificationRouter.java` | 通知路由分发 |
| `mcp/mention/` | `AtMentionParser.java` | `@server:uri` 引用解析 |
| `hitl/` | `HitlToolRegistry.java` | MCP 工具的安全审批封装 |

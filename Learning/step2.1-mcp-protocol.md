# 第 2.1 步：MCP 协议层 —— 数据结构与通信协议

> 搭配 `learningPlan.md` 第 2.1 节 + `step2-mcp-guide.md` Day 1 食用。
>
> 协议层位于 MCP 模块的最下层，定义"工具长什么样"和"消息怎么传"。

---

## 整体定位

```
Agent (ReAct 循环)
    │
ToolRegistry (工具注册表)
    │
McpServerManager (Server 生命周期管理)
    │
McpClient (业务通信：listTools / callTool)
    │
JsonRpcClient   ← ★ 协议层：JSON-RPC 2.0 通信
    │
McpTransport    ← ★ 协议层：传输抽象接口
    │
├── StdioTransport
└── StreamableHttpTransport
```

协议层包含三大核心类：

| 类 | 作用 | 一句话 |
|-----|------|--------|
| `McpToolDescriptor` | 工具数据模型 | 一个 MCP 工具的"身份证" |
| `McpSchemaSanitizer` | Schema 裁剪 | 删无用元数据，给 LLM 省 Token |
| `JsonRpcClient` | 协议客户端 | JSON-RPC 2.0 的 Java 实现 |

---

## 1. McpToolDescriptor —— 工具数据模型

**文件：** `src/main/java/com/paicli/mcp/protocol/McpToolDescriptor.java`

### 源码

```java
public record McpToolDescriptor(
        String serverName,          // 所属 MCP Server 名称（如 "chrome-devtools"）
        String name,                // 工具原始名称（如 "list_pages"）
        String namespacedName,      // 全局唯一名：mcp__chrome-devtools__list_pages
        String description,         // 工具描述
        JsonNode inputSchema        // 参数 JSON Schema
) {
    public static String namespaced(String serverName, String toolName) {
        return "mcp__" + serverName + "__" + toolName;
    }
}
```

### 为什么是 record？

Java 14+ 引入的**记录类型**——专门用来装数据的类，编译器自动生成构造器、`equals()`、`hashCode()`、`toString()`。比传统 POJO 省掉约 50 行样板代码。

### 为什么需要 `namespacedName`？

不同 MCP Server 可能定义同名工具（比如两个 Server 都有 `read_file`）。用 `mcp__{server}__{tool}` 格式确保 LLM 看到的工具列表全局唯一。

### 它在流程中的位置

```
MCP Server 返回 JSON
    │  { "name": "navigate", "description": "...", "inputSchema": {...} }
    ▼
McpClient.listTools()
    │  forEach tool → new McpToolDescriptor(serverName, name, namespacedName, description, schema)
    ▼
McpSchemaSanitizer.sanitize()   ← Schema 裁剪
    │
    ▼
McpServerManager.replaceTools()
    │  → ToolRegistry 注册
    ▼
LLM 看到 mcp__chrome-devtools__navigate 并调用
```

### 一句话

> **`McpToolDescriptor` 是 MCP 协议层与内部工具注册表之间的数据桥梁，把 MCP Server 返回的原始工具信息转为强类型对象，并通过 `mcp__{server}__{tool}` 命名规约确保全局唯一。**

---

## 2. McpSchemaSanitizer —— Schema 裁剪器

**文件：** `src/main/java/com/paicli/mcp/protocol/McpSchemaSanitizer.java`

### 为什么要裁剪？

MCP Server 不知道对面是 LLM，它返回**标准 JSON Schema**，包含大量对 LLM 无用的元数据：

| 关键字 | 对 IDE/开发者 | 对 LLM |
|--------|-------------|--------|
| `$schema` | 标明 Schema 版本 | ❌ 无用 |
| `$id` | 唯一标识 Schema | ❌ 无用 |
| `$ref` | 引用复用的定义 | ❌ 不会去查 |
| `anyOf/oneOf` | 多类型联合 | 🟡 复杂，LLM 经常搞混 |

不裁剪的话，这些无用元数据白占 LLM 的 context window token。

### 裁剪策略

```java
public static JsonNode sanitize(JsonNode schema) {
    // 1. 安全深拷贝，不修改原始 JSON
    JsonNode copy = schema.deepCopy();
    // 2. 递归清理
    JsonNode cleaned = clean(copy);
    // 3. 保证顶级一定有 type + properties
    if (!obj.has("type")) obj.put("type", "object");
    if (!obj.has("properties")) obj.putObject("properties");
    return obj;
}

private static JsonNode clean(JsonNode node) {
    // 删除无用元数据
    object.remove("$schema");
    object.remove("$id");
    object.remove("$ref");

    // 合并 anyOf/oneOf 为 description 文本
    String alternatives = mergeAlternatives(object, "anyOf", "oneOf");
    if (!alternatives.isEmpty()) {
        object.put("description", existing + " (" + alternatives + ")");
    }

    // description 超长截断（>1000 字符）
    object.put("description", truncateDescription(child.asText()));
}
```

### 裁剪前后对比

**裁剪前（~800 字符）：**
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://example.com/schemas/query.json",
  "type": "object",
  "properties": {
    "format": {
      "anyOf": [
        { "type": "string", "enum": ["json", "csv"] },
        { "type": "null" }
      ],
      "description": "输出格式"
    }
  }
}
```

**裁剪后（~300 字符，省 60%+）：**
```json
{
  "type": "object",
  "properties": {
    "format": {
      "type": "object",
      "description": "输出格式 (anyOf options: string, null)"
    }
  }
}
```

### 一句话

> **`McpSchemaSanitizer` 是 MCP 工具注册前的"净化器"，递归裁剪 JSON Schema 中的 `$schema`、`$id`、`$ref` 等无用元数据，将 `anyOf/oneOf` 合并为 description 文本，截断超长描述，最终让 LLM 看到的工具参数定义简洁干净，平均节省 30-60% 的 Schema Token 占用。**

---

## 3. JsonRpcClient —— JSON-RPC 2.0 客户端

**文件：** `src/main/java/com/paicli/mcp/jsonrpc/JsonRpcClient.java`

### 核心机制

**一句话：用递增 ID + CompletableFuture 实现异步请求-响应匹配。**

```java
public class JsonRpcClient implements AutoCloseable {
    private final AtomicLong ids = new AtomicLong(1);              // 递增 ID 计数器
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    //                                  ↑ 等待队列：请求 ID → 对应的 Future
}
```

### 请求-响应流程

```java
public JsonNode request(String method, JsonNode params, long timeoutSeconds) {
    long id = ids.getAndIncrement();                       // ① 分配唯一 ID

    ObjectNode request = MAPPER.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", id);                                 // ② 构建 JSON-RPC 请求
    request.put("method", method);
    request.set("params", params);

    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    pending.put(id, future);                               // ③ 存入等待队列

    scheduler.schedule(() -> {
        pending.remove(id);
        future.completeExceptionally(new TimeoutException());
    }, timeoutSeconds, TimeUnit.SECONDS);                  // ④ 启动超时定时器

    transport.send(request);                               // ⑤ 发出去！
    return future.get(timeoutSeconds + 1, ...);            // ⑥ 阻塞等结果
}
```

### 消息接收处理

```java
private void handleMessage(JsonNode message) {
    JsonNode idNode = message.get("id");

    if (idNode == null || idNode.isNull()) {
        // ★ 无 id → 服务端推送的通知（广播给所有监听器）
        notificationListeners.forEach(l -> l.accept(message));
        return;
    }

    // ★ 有 id → 响应某个请求（从 pending 找到对应 Future 并填结果）
    CompletableFuture<JsonNode> future = pending.remove(idNode.longValue());
    if (message.has("error")) {
        future.completeExceptionally(new JsonRpcException(code, message));
    } else {
        future.complete(message.get("result"));
    }
}
```

### 通知（notification）

MCP Server 可以**主动推送消息**，没有 `id` 字段：

```json
{"jsonrpc": "2.0", "method": "notifications/tools/list_changed"}
```

`handleMessage()` 看到无 `id`，不走 Future 匹配，而是**广播给所有通知监听器**，让 `McpServerManager` 知道"工具列表变了，需要重新获取"。

### 两种消息对比

| | 请求-响应（request） | 通知（notification） |
|---|---|---|
| 有 `id` | ✅ 是 | ❌ 否 |
| 谁发起 | 客户端 | 服务端主动推 |
| 匹配方式 | `pending[id] → future.complete()` | 广播给所有 listener |
| 例子 | `tools/list`、`tools/call` | `notifications/tools/list_changed` |

### 发送通知

```java
public void sendNotification(String method, JsonNode params) {
    // 不包含 id 字段，不期待响应
    ObjectNode notification = MAPPER.createObjectNode();
    notification.put("jsonrpc", "2.0");
    notification.put("method", method);
    transport.send(notification);
}
```

### 调用层级关系

```
McpClient（业务语言：listTools / callTool / listResources）
    │
    │  内部使用
    ▼
JsonRpcClient（协议语言：request / sendNotification / handleMessage）
    │
    │  内部使用
    ▼
McpTransport（传输语言：send / onReceive — 不关心是 stdio 还是 HTTP）
```

### 关键理解

> **JSON-RPC 2.0 本身就是异步协议（通过 `id` 匹配请求和响应），`JsonRpcClient` 不是"加了异步"，而是用 `CompletableFuture` 把协议的异步模式包装成**看似同步的 API 调用**，让 `McpClient` 只写 `rpc.request("tools/list", ...)` 就行，不用手动管理 id、匹配、超时。

---

## 协议层三件套的协作关系

```
MCP Server 返回原始工具列表（JSON）
    │
    ▼
McpClient.listTools()
    │  JsonRpcClient.request("tools/list")     ← 发请求
    │  for 每个工具:
    │    McpSchemaSanitizer.sanitize(schema)   ← 裁剪 Schema
    │    new McpToolDescriptor(...)             ← 包装为 Java 对象
    ▼
返回 List<McpToolDescriptor>
    │
    ▼
McpServerManager → ToolRegistry 注册
```

| 类 | 角色 | 好比 |
|-----|------|------|
| `McpSchemaSanitizer` | 裁剪 Schema 省 Token | 洗菜切菜 |
| `McpToolDescriptor` | 包装成强类型数据 | 装盘 |
| `JsonRpcClient` | 负责与 Server 通信 | 传菜员 |

---

## 面试亮点

> MCP 模块的协议层包含三个核心设计：
> 1. **`McpToolDescriptor`** 使用 Java record 建模工具元数据，通过 `mcp__{server}__{tool}` 命名规约确保跨 Server 的工具名全局唯一。
> 2. **`McpSchemaSanitizer`** 自动裁剪 JSON Schema 中的 `$schema`、`$id`、`$ref` 等对 LLM 无用的元数据，将 `anyOf/oneOf` 合并为文本描述以节省 Token，平均压缩 30-60%。
> 3. **`JsonRpcClient`** 用递增 ID + `ConcurrentHashMap<CompletableFuture>` 实现异步请求-响应匹配，无 ID 消息广播为通知——`McpClient` 无需关心协议细节。

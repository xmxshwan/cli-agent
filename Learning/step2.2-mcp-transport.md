# 第 2.2 步：MCP 传输层 —— 子进程管道 vs HTTP

> 搭配 `learningPlan.md` 第 2.1 节 + `step2-mcp-guide.md` Day 2 食用。
>
> 传输层解决"JSON 消息怎么从 PaiCLI 进程送到 MCP Server 进程"的问题。

---

## 整体定位

```
Agent (ReAct 循环)
    │
ToolRegistry
    │
McpServerManager
    │
McpClient（业务：listTools / callTool）
    │
JsonRpcClient（协议：request / handleMessage）
    │
McpTransport（接口）  ← ★ 传输层：这里是重点
    │
├── StdioTransport            → 子进程 stdin/stdout/stderr
└── StreamableHttpTransport   → HTTP POST + SSE
```

---

## 1. McpTransport —— 传输抽象接口

**文件：** `src/main/java/com/paicli/mcp/transport/McpTransport.java`

### 源码

```java
public interface McpTransport extends AutoCloseable {
    void send(JsonNode message) throws IOException;           // 发一条消息
    void onReceive(Consumer<JsonNode> listener);              // 注册收消息回调
    default List<String> stderrLines() { return List.of(); }  // stderr 日志（仅 stdio）
    default Long processId() { return null; }                 // 子进程 PID（仅 stdio）
    default String transportName() { return "unknown"; }
    void close();
}
```

**只有 6 个方法，却是整个传输层的抽象核心。**

没有这个接口的话，`JsonRpcClient` 得写满 if-else：

```java
// ★ 没有接口 —— 灾难版
public void send(JsonNode message) {
    if (isStdio) {
        stdin.write(...);
    } else {
        httpClient.newCall(...).execute();
    }
}
```

有了接口，上层完全不知道底层是什么：

```java
// ★ 有接口 —— 优雅
public class JsonRpcClient {
    private final McpTransport transport;  // 管你是 stdio 还是 HTTP
    public void send(JsonNode msg) {
        transport.send(msg);               // 一行搞定
    }
}
```

---

## 2. StdioTransport —— 子进程管道通信

**文件：** `src/main/java/com/paicli/mcp/transport/StdioTransport.java`

### 适用场景

本地 MCP Server，通过 `npx`、`uvx`、`java -jar` 等命令启动的子进程。

### 架构图

```
┌──────────────────────────────────────────────┐
│ PaiCLI (Java 进程)                            │
│                                               │
│ StdioTransport                                 │
│                                               │
│  Thread[stdout-reader] (daemon)               │
│    while (readLine from stdout)               │
│      → MAPPER.readTree(line)                  │
│      → listeners.forEach(l -> l.accept(msg))  │
│                                               │
│  Thread[stderr-reader] (daemon)               │
│    while (readLine from stderr)               │
│      → stderrRing (环形缓冲区 200 行)           │
│                                               │
│  send(JsonNode)                               │
│    → stdin.write(JSON + "\n")                 │
│    → stdin.flush()                            │
└────────────────────┬─────────────────────────┘
                     │ stdin / stdout / stderr
                     ▼
┌──────────────────────────────────────────────┐
│ 子进程 (npx -y chrome-devtools-mcp)            │
│ MCP Server 从 stdin 读请求、往 stdout 写响应    │
└──────────────────────────────────────────────┘
```

### 构造方法 —— 启动子进程

```java
public StdioTransport(String command, List<String> args,
                      Map<String, String> env, Path workingDir) {
    // 1. 组装命令
    List<String> commandLine = new ArrayList<>();
    commandLine.add(command);       // "npx"
    commandLine.addAll(args);        // "-y", "chrome-devtools-mcp"

    // 2. 配置环境和工作目录
    ProcessBuilder builder = new ProcessBuilder(commandLine);
    builder.directory(workingDir);
    builder.environment().putAll(env);

    // 3. 启动子进程！
    this.process = builder.start();

    // 4. 包装 stdin 为 Writer
    this.stdin = new BufferedWriter(
        new OutputStreamWriter(process.getOutputStream(), UTF_8));

    // 5. 启动两个后台读取线程
    startStdoutReader();  // 读 stdout → JSON 消息
    startStderrReader();  // 读 stderr → 日志缓冲区
}
```

### send() —— 写 stdin

```java
public synchronized void send(JsonNode message) throws IOException {
    stdin.write(MAPPER.writeValueAsString(message));   // JSON 序列化
    stdin.newLine();                                    // 换行（每行一条 JSON-RPC 消息）
    stdin.flush();
}
```

往子进程的 stdin 写入类似：`{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}\n`

### stdout-reader 线程 —— 读 stdout

```java
private void startStdoutReader() {
    Thread thread = new Thread(() -> {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {   // 逐行读取子进程输出
            if (line.isBlank()) continue;
            JsonNode message = MAPPER.readTree(line);   // 每行是一条 JSON
            for (Consumer<JsonNode> listener : listeners) {
                listener.accept(message);               // 派发给 JsonRpcClient
            }
        }
    }, "paicli-mcp-stdio-stdout");
    thread.setDaemon(true);
    thread.start();
}
```

**关键点：** 这是一个 **daemon 线程**（不会阻止 JVM 退出），`while ((line = reader.readLine()) != null)` 会**阻塞等待**——子进程没输出时线程挂起，子进程有输出时立即被唤醒。

### stderr-reader 线程 —— 读 stderr

```java
private void startStderrReader() {
    Thread thread = new Thread(() -> {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getErrorStream(), UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            appendStderr(line);     // 存到环形缓冲区
        }
    }, "paicli-mcp-stdio-stderr");
    thread.setDaemon(true);
    thread.start();
}
```

```java
// 环形缓冲区：最多保留 200 行，超出则丢弃最旧的
private void appendStderr(String line) {
    synchronized (stderrLock) {
        while (stderrRing.size() >= MAX_STDERR_LINES) {
            stderrRing.removeFirst();
        }
        stderrRing.addLast(line);
    }
}
```

用户通过 `/mcp logs <server-name>` 查看 stderr，对调试非常有用。

### close() —— 3 阶段终止策略

关子进程不能直接 kill——要给时间保存状态、释放资源：

```java
public void close() {
    closed = true;

    // 阶段 1：关 stdin → 子进程读到 EOF（优雅退出窗口 1 秒）
    stdin.close();
    if (process.waitFor(1, TimeUnit.SECONDS)) return;

    // 阶段 2：发 SIGTERM（再等 2 秒）
    process.destroy();
    if (process.waitFor(2, TimeUnit.SECONDS)) return;

    // 阶段 3：强制 SIGKILL
    process.destroyForcibly();
}
```

| 阶段 | 操作 | 等待 | 适用场景 |
|------|------|------|---------|
| 1 | 关 stdin，让子进程读到 EOF | 1 秒 | 子进程检测到 EOF 就自己退出 |
| 2 | `destroy()` → SIGTERM | 2 秒 | 子进程忙但能响应信号 |
| 3 | `destroyForcibly()` → SIGKILL | 立即 | 子进程卡死/不响应 |

### 完整请求-响应时间线

```
PaiCLI                              npx chrome-devtools-mcp
  │                                         │
  │  send({"id":1,"method":"tools/list"})   │
  │  → stdin 写入一行 JSON                  │
  │────────────────────────────────────────►│
  │                                         │  处理请求
  │                                         │  ...
  │                                         │
  │  stdout-reader 线程阻塞醒来              │
  │◄────────────────────────────────────────│  stdout 输出一行 JSON
  │                                         │
  │  listener.accept(message)                │
  │  → JsonRpcClient.handleMessage()         │
  │    → pending[1].complete(result)         │
  │    → request() 返回                      │
```

---

## 3. StreamableHttpTransport —— HTTP + SSE 通信

**文件：** `src/main/java/com/paicli/mcp/transport/StreamableHttpTransport.java`

### 适用场景

远程 MCP Server，通过 `url` 配置的 HTTP 服务。

### 架构图

```
PaiCLI                              Remote MCP Server
  │                                         │
  │  HTTP POST /mcp                         │
  │  Content-Type: application/json         │
  │  MCP-Protocol-Version: 2024-11-05       │
  │  (首次无 sessionId)                      │
  │────────────────────────────────────────►│
  │                                         │
  │  HTTP 200                               │
  │  Mcp-Session-Id: sess_xxx               │
  │  Content-Type: application/json         │
  │◄────────────────────────────────────────│
  │                                         │
  │  (后续请求带上 sessionId)                 │
  │  HTTP POST /mcp                         │
  │  Mcp-Session-Id: sess_xxx               │
  │────────────────────────────────────────►│
```

### send() —— 每次调用都是一次 HTTP POST

```java
public void send(JsonNode message) throws IOException {
    // 1. 构建 POST 请求
    RequestBody body = RequestBody.create(
        MAPPER.writeValueAsString(message), JSON);
    Request.Builder builder = new Request.Builder()
        .url(url)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
        .post(body);
    headers.forEach(builder::header);

    // ★ 第二次请求起，带上 sessionId
    if (sessionId != null) {
        builder.header("Mcp-Session-Id", sessionId);
    }

    // 2. 发出去，等响应
    try (Response response = client.newCall(builder.build()).execute()) {
        // 3. 首次连接时服务端返回 Mcp-Session-Id
        String newSession = response.header("Mcp-Session-Id");
        if (newSession != null) sessionId = newSession;

        // 4. 解析响应（普通 JSON 或 SSE 流）
        String contentType = response.header("Content-Type", "");
        String raw = responseBody.string();

        List<JsonNode> messages = contentType.contains("text/event-stream")
            ? parseSse(raw)                    // SSE 流 → 解析多条消息
            : List.of(MAPPER.readTree(raw));   // 普通 JSON → 单条消息

        // 5. 派发给所有 listener
        for (JsonNode node : messages) {
            listeners.forEach(l -> l.accept(node));
        }
    }
}
```

### parseSse() —— SSE 流解析

SSE（Server-Sent Events）格式：

```
data: {"jsonrpc":"2.0","id":1,"result":{"tools":[...]}}

data: {"jsonrpc":"2.0","method":"notifications/tools/list_changed"}
```

```java
private static List<JsonNode> parseSse(String raw) {
    List<JsonNode> messages = new ArrayList<>();
    StringBuilder data = new StringBuilder();
    for (String line : raw.split("\\R")) {
        if (line.isBlank()) {                          // 空行 → 一条消息结束
            if (!data.isEmpty()) {
                messages.add(MAPPER.readTree(data.toString()));
                data.setLength(0);
            }
            continue;
        }
        if (line.startsWith("data:")) {                 // data: 开头
            if (!data.isEmpty()) data.append('\n');
            data.append(line.substring("data:".length()).trim());
        }
    }
    if (!data.isEmpty()) {                              // 收尾的最后一条
        messages.add(MAPPER.readTree(data.toString()));
    }
    return messages;
}
```

### close() —— best-effort 关闭

```java
public void close() {
    if (sessionId == null) return;       // 从未成功连接，无需关闭
    // 短超时（5s）DELETE 请求，可能失败但不阻塞主进程退出
    OkHttpClient closeClient = client.newBuilder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build();
    try (Response ignored = closeClient.newCall(builder.delete().build()).execute()) {
        // best effort — 不管成功失败都不抛异常
    } catch (IOException ignored) {}
}
```

---

## 4. Stdio vs HTTP 完整对比

| 对比维度 | StdioTransport | StreamableHttpTransport |
|---------|---------------|------------------------|
| **通信方式** | 子进程 stdin/stdout 管道 | HTTP POST 请求 + 响应 |
| **启动方式** | ProcessBuilder 启动子进程 | 独立运行的 HTTP 服务 |
| **进程管理** | ✅ 启动、3 阶段终止、stderr 收集 | ❌ 不需要 |
| **会话** | 无（子进程天然一对一） | ✅ sessionId 维持对话 |
| **消息模式** | 写一行、读一行，全双工持续连接 | 每次 send() 是一次独立 HTTP 请求 |
| **SSE 支持** | ❌ 不需要（本身就是逐行的） | ✅ 需要解析 text/event-stream |
| **stderr 日志** | ✅ 环形缓冲区 200 行，`/mcp logs` 查看 | ❌ 无 |
| **进程 PID** | ✅ `process.pid()` | ❌ 无 |
| **transportName** | `"stdio"` | `"http"` |
| **close** | 3 阶段终止（关 stdin → SIGTERM → SIGKILL） | HTTP DELETE，best-effort |
| **适用场景** | 本地 `npx` / `uvx` / `java -jar` | 远程 HTTP MCP Server |

### 同一个请求，两种传输方式的差异

请求：`tools/list`

**StdioTransport：**
```
PaiCLI → [stdin]:  {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}\n
         [stdout]: {"jsonrpc":"2.0","id":1,"result":{"tools":[...]}}\n
```
写一行 JSON，读一行 JSON。**一次连接，持续复用。**

**StreamableHttpTransport：**
```
PaiCLI → [HTTP POST]: POST /mcp  →  请求体: {"jsonrpc":"2.0","id":1,"method":"tools/list"}
        [HTTP 200]:   ←  响应体: {"jsonrpc":"2.0","id":1,"result":{"tools":[...]}}
```
每次 `send()` 都是一次完整的 HTTP 请求-响应。**每次独立的 TCP 连接。**

---

## 5. 传输层如何统一？—— 工厂方法

```java
// McpServerManager.java
private McpTransport createTransport(McpServerConfig config) throws IOException {
    if (config.isHttp()) {      // mcp.json 有 "url" 字段？
        return new StreamableHttpTransport(config.getUrl(), config.getHeaders());
    }
    // mcp.json 有 "command" 字段？
    return new StdioTransport(config.getCommand(), config.getArgs(),
                              config.getEnv(), projectDir);
}
```

判断依据在 `McpServerConfig`：

```java
public boolean isHttp() { return url != null && !url.isBlank(); }
public boolean isStdio() { return command != null && !command.isBlank(); }
```

配置示例：

```json
// 本地 MCP Server → StdioTransport
{ "command": "npx", "args": ["-y", "chrome-devtools-mcp"] }

// 远程 MCP Server → StreamableHttpTransport
{ "url": "https://api.example.com/mcp", "headers": { "Authorization": "Bearer xxx" } }
```

---

## 面试亮点

> MCP 传输层采用接口分离设计：**`McpTransport` 定义 `send()` 和 `onReceive()` 抽象契约，`StdioTransport` 通过 `ProcessBuilder` 启动子进程并管理其标准流实现本地通信，`StreamableHttpTransport` 通过 OkHttp 发起 HTTP POST 请求并解析 SSE 实现远程通信。两个实现在 `McpServerManager.createTransport()` 中根据配置自动选择，上层 `JsonRpcClient` 完全无感知。这种设计的核心价值在于：**对业务代码（协议层和集成层）屏蔽了传输差异，新增一种传输方式只需实现 `McpTransport` 接口，无需修改任何现有代码。**

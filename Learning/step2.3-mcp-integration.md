# 第 2.3 步：MCP 集成层 —— 从配置到运行

> 搭配 `learningPlan.md` 第 2.1 节 + `step2-mcp-guide.md` Day 3 食用。
>
> 集成层把协议层（Day 1）和传输层（Day 2）组装起来，让 Agent 能真正使用 MCP 工具。

---

## 整体定位

回顾三天的学习路径：

```
Day 1 (协议层):   McpToolDescriptor + McpSchemaSanitizer + JsonRpcClient
Day 2 (传输层):   McpTransport → StdioTransport / StreamableHttpTransport
Day 3 (集成层):   McpServer + McpClient + McpServerManager → ToolRegistry + HITL
```

Day 3 的四个核心类：

| 类 | 角色 | 好比 |
|-----|------|------|
| `McpServer` | 单个 MCP Server 的状态模型 | 一个"设备"的铭牌和指示灯 |
| `McpClient` | 与单个 MCP Server 通信 | 一个设备的"遥控器" |
| `McpServerManager` | 管理所有 MCP Server 的生命周期 | 整个"设备机房"的管理员 |
| `ToolRegistry` (MCP 部分) | 把 MCP 工具注册为 LLM 可调用的工具 | 机房的"电话总机" |

---

## 1. McpServer —— Server 状态模型

**文件：** `src/main/java/com/paicli/mcp/McpServer.java`

### 源码

```java
public class McpServer {
    private final String name;                             // 名字：如 "chrome-devtools"
    private final McpServerConfig config;                   // 配置：command/url/env
    private volatile McpServerStatus status;                // 当前状态
    private volatile McpClient client;                      // 通信客户端（启动后才有）
    private volatile List<McpToolDescriptor> tools = List.of();  // 当前工具列表
    private volatile String errorMessage;                   // 错误信息
    private volatile Instant startedAt;                     // 启动时间
}
```

### 状态枚举：4 个值

```java
public enum McpServerStatus {
    STARTING,   // 正在启动中
    READY,      // 就绪，可以使用
    ERROR,      // 启动失败
    DISABLED    // 被用户禁用
}
```

### 生命周期

```
new McpServer() → status = DISABLED
    │
    ├── start() → STARTING → READY ✅（成功）
    │
    ├── start() → STARTING → ERROR ❌（失败）
    │
    └── disable() → DISABLED （用户手动禁用）
```

---

## 2. McpClient —— 单个 Server 的遥控器

**文件：** `src/main/java/com/paicli/mcp/McpClient.java`

### 对业务暴露的三个核心方法

```java
public class McpClient implements AutoCloseable {
    private final String serverName;          // 对哪个 Server 通信
    private final JsonRpcClient rpc;          // JSON-RPC 协议
    private final McpTransport transport;     // 传输层（stdio / HTTP）

    // ① 获取工具列表 → List<McpToolDescriptor>
    public List<McpToolDescriptor> listTools() { ... }

    // ② 调用工具 → 返回 ToolOutput
    public ToolOutput callToolOutput(String toolName, String argumentsJson) { ... }

    // ③ 获取资源列表（可选）
    public List<McpResourceDescriptor> listResources() { ... }
}
```

### 创建 McpClient 时的握手

```java
// McpClient.java 第 41-44 行
public void initialize() throws IOException {
    // 发 initialize 请求，拿 capabilities
    JsonNode result = rpc.request("initialize", McpInitializeRequest.toJson(), timeout);
    serverCapabilities = result.path("capabilities");
    // 发 initialized 通知
    rpc.sendNotification("notifications/initialized", emptyNode);
}
```

这是 JSON-RPC 通信的第一步——客户端和服务端互相确认协议版本和能力。

---

## 3. McpServerManager —— 总管理员（最核心）

**文件：** `src/main/java/com/paicli/mcp/McpServerManager.java`

### 职责一览

| 职责 | 方法 |
|------|------|
| 加载配置 | `loadConfiguredServers()` — 从 mcp.json 加载所有 Server |
| 并行启动 | `startAll()` — 同时启动所有 Server（最多 8 个并发） |
| 逐个启动 | `start(server)` — 一个 Server 的完整启动流程 |
| 热重启 | `restart(name)` — 重启指定 Server |
| 禁用/启用 | `disable(name)` / `enable(name)` |
| 工具注册 | `replaceTools()` — MCP 工具注入 ToolRegistry |
| 通知处理 | `registerNotificationHandlers()` — 响应 Server 推送 |
| 状态查看 | `formatStatus()` — `/mcp` 命令输出 |
| 日志查看 | `logs(name)` — `/mcp logs <name>` |

### 核心数据结构

```java
public class McpServerManager implements AutoCloseable {
    private final ToolRegistry toolRegistry;                    // 工具注册表
    private final Path projectDir;                              // 项目根目录
    private final McpConfigLoader configLoader;                 // 配置加载器
    private final Map<String, McpServer> servers = new ConcurrentHashMap<>();  // 所有 Server
    private final McpResourceCache resourceCache = new McpResourceCache();      // 资源缓存
}
```

---

## 4. 用一个完整例子串起一切

假设 `~/.paicli/mcp.json` 配置了两个 MCP Server：

```json
{
  "mcpServers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest"]
    },
    "remote-db": {
      "url": "https://api.example.com/mcp"
    }
  }
}
```

---

### 第 1 步：启动时加载配置

```java
// Main.java 启动时
McpServerManager manager = new McpServerManager(toolRegistry, projectDir);
manager.loadConfiguredServers();    // 解析 mcp.json → 2 个 McpServer 对象
manager.startAll(maxWait);          // 并行启动所有 Server
```

`loadConfiguredServers()` 内部：

```java
public void loadConfiguredServers() throws IOException {
    Map<String, McpServerConfig> configs = configLoader.load();
    servers.clear();
    configs.forEach((name, config) -> servers.put(name, new McpServer(name, config)));
    // 此时 servers = {
    //   "chrome-devtools": McpServer(status=DISABLED, config={command: "npx", ...}),
    //   "remote-db":      McpServer(status=DISABLED, config={url: "https://...", ...})
    // }
}
```

---

### 第 2 步：并行启动所有 Server

```java
public void startAll(PrintStream progressOut, Duration maxWait) {
    List<McpServer> targets = servers.values().stream()
        .filter(s -> !s.config().isDisabled())
        .toList();

    // 用专属 daemon 线程池并行启动，最多 8 个并发
    ExecutorService executor = Executors.newFixedThreadPool(
        Math.min(targets.size(), 8));

    for (McpServer server : targets) {
        CompletableFuture.runAsync(() -> start(server), executor);
    }

    // 等待全部完成，或超时（maxWait 后未完成的进后台继续）
    if (maxWait == null) {
        all.join();              // 无限等（历史行为）
    } else {
        try {
            all.get(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 超时了，打印提示，剩余 Server 在后台继续启动
            printStartupTimeout(targets, progressOut, maxWait);
        }
    }
}
```

启动期间终端会实时显示进度：

```
   ⏳ chrome-devtools  stdio   启动中...（已等待 3s）
   ⏳ remote-db        http    启动中...（已等待 3s）
```

如果超时，PaiCLI 不会卡住，而是提示：

```
⚠️ MCP 启动超过 8s，先进入 CLI；后台继续启动: chrome-devtools, remote-db
   可用 /mcp 查看最新状态，或 /mcp logs <name> 查看日志。
```

---

### 第 3 步：单个 Server 启动流程

这是**整个 MCP 集成最核心的方法**——`start(server)`：

```java
private void start(McpServer server) {
    unregisterTools(server);             // ① 清理旧工具
    server.close();                      // ② 关闭旧连接
    if (server.config().isDisabled()) {
        server.status(DISABLED); return;
    }
    server.status(STARTING);             // ③ 标记启动中

    try {
        configLoader.prepare(server.config());             // ④ 展开 ${VAR}

        McpTransport transport = createTransport(server.config());
        //    chrome-devtools → StdioTransport("npx", ...)
        //    remote-db      → StreamableHttpTransport("https://...")

        McpClient client = new McpClient(server.name(), transport);
        client.initialize();                               // ⑤ 握手

        registerNotificationHandlers(server, client);      // ⑥ 注册通知

        List<McpToolDescriptor> tools = buildToolList(server, client);
        //   ⑦ 获取工具列表：client.listTools() + client.listResources()
        //     并做重复工具名校验

        replaceTools(server, client, tools);               // ⑧ 注册到 ToolRegistry

        server.client(client);                             // ⑨ 标记就绪
        server.tools(tools);
        server.markStarted();
        server.status(READY);
    } catch (Exception e) {
        server.close();
        server.errorMessage(e.getMessage());
        server.status(ERROR);             // ← 单个失败不影响其他 Server
    }
}
```

### 启动流程图

```
startAll() 被调用
    │
    ├── start(chrome-devtools)
    │       │  status = STARTING
    │       │
    │       ├── createTransport(config) → StdioTransport("npx -y ...")
    │       │                           → 启动子进程
    │       │
    │       ├── new McpClient(transport) + initialize()
    │       │   └── JsonRpcClient.request("initialize") → 握手
    │       │
    │       ├── buildToolList()
    │       │   ├── client.listTools()      → [list_pages, navigate, take_snapshot]
    │       │   ├── client.listResources()  → [到 URL 映射]
    │       │   ├── McpResourceTool.descriptors() → [虚拟资源工具]
    │       │   └── validateNoDuplicateTools()
    │       │
    │       ├── replaceTools(server, client, tools)
    │       │   └── toolRegistry.replaceMcpToolOutputsForServer()
    │       │       ├── 清理旧 mcp__chrome-devtools__* 工具
    │       │       └── 注册新工具：mcp__chrome-devtools__list_pages
    │       │                              mcp__chrome-devtools__navigate
    │       │                              mcp__chrome-devtools__take_snapshot
    │       │
    │       ├── registerNotificationHandlers()
    │       │   └── router.on("tools/list_changed", ...)
    │       │
    │       └── status = READY ✅
    │
    ├── start(remote-db)
    │       │  status = STARTING
    │       ├── createTransport(config) → StreamableHttpTransport("https://...")
    │       ├── ...同上...
    │       └── status = READY ✅
    │
    └── 两个 Server 都就绪，MCP 工具已在 ToolRegistry 中
```

---

### 第 4 步：MCP 工具注册到 ToolRegistry

`replaceTools()` 是 MCP 集成到 Agent 的**关键桥梁**：

```java
private void replaceTools(McpServer server, McpClient client,
                          List<McpToolDescriptor> tools) {
    toolRegistry.replaceMcpToolOutputsForServer(
        server.name(),                     // "chrome-devtools"
        tools,                              // [McpToolDescriptor...]
        descriptor -> isResourceVirtualTool(descriptor)
            ? args -> ToolOutput.text(McpResourceTool.invoker(client, descriptor).apply(args))
            : args -> invokeMcpToolOutput(client, descriptor, args)
    );
}
```

`ToolRegistry` 内部执行：

```java
public synchronized void replaceMcpToolOutputsForServer(
        String serverName,
        List<McpToolDescriptor> newTools,
        Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {

    // 1. 清理该 Server 的旧工具（按前缀匹配）
    String prefix = "mcp__" + serverName + "__";
    mcpTools.keySet().stream()
        .filter(name -> name.startsWith(prefix))
        .forEach(name -> { mcpTools.remove(name); tools.remove(name); });

    // 2. 注册新工具（每个工具都有一个执行闭包）
    for (McpToolDescriptor descriptor : newTools) {
        registerMcpToolOutput(descriptor, invokerFactory.apply(descriptor));
    }
}
```

注册后，LLM 看到的工具列表：

```
内置工具（9 个）:
  read_file, write_file, execute_command, ...

MCP 工具（chrome-devtools → 3 个）:
  mcp__chrome-devtools__list_pages
  mcp__chrome-devtools__navigate
  mcp__chrome-devtools__take_snapshot

MCP 工具（remote-db → 2 个）:
  mcp__remote-db__query
  mcp__remote-db__list_tables
```

---

### 第 5 步：Agent 运行时调用 MCP 工具

用户在终端输入："帮我打开百度"

```
Agent (ReAct 循环)
    │
    │ LLM 决定调用 mcp__chrome-devtools__navigate({"url": "https://baidu.com"})
    ▼
ToolRegistry.executeToolOutput("mcp__chrome-devtools__navigate", "...")
    │
    │ 在 mcpTools 表中找到该工具
    ▼
HitlToolRegistry 审批拦截
    │
    ├── HITL 启用？ 且 ApprovalPolicy.requiresApproval() 为 true？
    │   → 所有 mcp__ 前缀工具都需要审批
    │
    ├── 用户批准 → 继续执行
    ├── 用户拒绝 → 返回 "[HITL] 操作已被拒绝"
    └── 用户跳过 → 返回 "[HITL] 操作已被跳过"
    │
    ▼
ToolRegistry.doExecuteTool() → 执行 MCP 工具
    │
    ▼
McpServerManager.invokeMcpToolOutput(client, descriptor, args)
    │
    ▼
McpClient.callToolOutput("navigate", "{\"url\":\"https://baidu.com\"}")
    │
    ▼
JsonRpcClient.request("tools/call", {name: "navigate", arguments: {...}})
    │
    ▼
StdioTransport.send(jsonNode)  → 往子进程 stdin 写一行 JSON
    │
    ▼
Chrome DevTools MCP Server 收到 → 打开浏览器 → 返回结果
    │
    ▼
结果原路返回给 LLM → LLM 继续推理
```

---

### 第 6 步：通知机制（运行时热更新）

MCP Server 可以在运行时主动通知工具或资源变更：

```java
private void registerNotificationHandlers(McpServer server, McpClient client) {
    NotificationRouter router = new NotificationRouter();

    // 工具列表变化 → 重新获取并更新 ToolRegistry
    router.on("notifications/tools/list_changed", ignored -> {
        List<McpToolDescriptor> tools = buildToolList(server, client);
        replaceTools(server, client, tools);    // ★ 热更新，无需重启！
        server.tools(tools);
    });

    // 资源列表变化 → 刷新缓存
    router.on("notifications/resources/list_changed", ignored ->
        resourceCache.invalidateServer(server.name()));

    // 单个资源更新 → 标记为需要重新读取
    router.on("notifications/resources/updated", params -> {
        String uri = params.path("uri").asText("");
        if (!uri.isBlank()) resourceCache.invalidateResource(server.name(), uri);
    });

    client.onNotification(router);
}
```

**这意味着**：MCP Server 运行中新增/删除了工具，PaiCLI 能**动态感知并同步**，无需重启 Agent。

---

## 5. HITL 对 MCP 工具的审批

**文件：** `src/main/java/com/paicli/hitl/ApprovalPolicy.java`

所有 MCP 工具默认需要人工审批：

```java
public static boolean requiresApproval(String toolName) {
    return DANGEROUS_TOOLS.contains(toolName) || isMcpTool(toolName);
}

public static boolean isMcpTool(String toolName) {
    return toolName != null && toolName.startsWith("mcp__");
}
```

### 用户审批交互

```
⚠️  需要审批
├────────────────────────────┤
│ 工具: mcp__chrome-devtools__navigate
│ MCP server: chrome-devtools
│ 等级: 🟡 MCP
│ 风险: 将调用外部 MCP server 提供的工具，
│       可能访问网络、文件或第三方服务
├────────────────────────────┤
│ [y/n/a/s/m] >
```

| 按键 | 含义 |
|------|------|
| `y` 或 `Enter` | 批准本次操作 |
| `a` | 批准本次会话所有后续同类工具 |
| `server` | 批准整个 MCP Server 的所有工具（连续浏览器操作推荐） |
| `n` | 拒绝本次操作 |
| `s` | 跳过本步骤 |
| `m` | 修改参数后执行 |

`HitlToolRegistry` 是 `ToolRegistry` 的一个装饰器，在所有工具调用前插入审批逻辑：

```java
// HitlToolRegistry.java:36-39
public ToolOutput executeToolOutput(String name, String argumentsJson) {
    if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
        return super.doExecuteTool(name, argumentsJson);  // 不需要审批，直接执行
    }
    // 需要审批 → 弹框等用户决策
    ...
}
```

---

## 6. 完整的生命周期图

```
┌──────────────────────────────────────────────────────────────────────┐
│                        PaiCLI 启动流程                                │
│                                                                      │
│  Main.java                                                           │
│    │                                                                 │
│    ├── new ToolRegistry()                            创建工具注册表   │
│    │                                                                 │
│    ├── new McpServerManager(toolRegistry, dir)      创建 Server 管理器│
│    │    │                                                           │
│    │    ├── loadConfiguredServers()                  加载 mcp.json     │
│    │    │   └── McpConfigLoader.load() → Map<name, config>          │
│    │    │                                                           │
│    │    └── startAll(maxWait)                        并行启动 Server  │
│    │         │                                                      │
│    │         ├── start(chrome-devtools)   ───┐                       │
│    │         │    createTransport()            │                     │
│    │         │    new McpClient()              │ 并行线程池            │
│    │         │    client.initialize()          │ (最多 8 个)           │
│    │         │    buildToolList()              │                     │
│    │         │    replaceTools() → ToolRegistry│                     │
│    │         │    status(READY)                │                     │
│    │         │                                │                       │
│    │         ├── start(remote-db)       ──────┘                       │
│    │         │    ...同上                                                │
│    │         │                                                      │
│    │         └── 全部完成后，MCP 工具已在 ToolRegistry 中               │
│    │                                                                 │
│    └── Agent.run()                           开始接收用户输入         │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│                    ReAct 循环中的 MCP 调用                            │
│                                                                      │
│  LLM 决定调用 mcp__chrome-devtools__navigate                          │
│    │                                                                 │
│    ├── ToolRegistry.findTool()   → 在 mcpTools 表里找到               │
│    │                                                                 │
│    ├── HitlToolRegistry 审批     → 用户确认                           │
│    │                                                                 │
│    ├── McpServerManager.invokeMcpToolOutput()                        │
│    │    └── client.callToolOutput("navigate", args)                  │
│    │         └── rpc.request("tools/call", {name, args})             │
│    │              └── transport.send(json)                           │
│    │                   └── 子进程/HTTP → 外部 MCP Server              │
│    │                                                                 │
│    └── 结果返回给 LLM → LLM 继续推理                                  │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 7. MCP 模块配置速查

### 配置文件位置

| 位置 | 作用域 | 优先级 |
|------|--------|--------|
| `~/.paicli/mcp.json` | 用户级全局配置 | 低（被项目级覆盖） |
| `.paicli/mcp.json` | 项目级配置 | 高 |

### 配置格式

```json
// 本地 MCP Server → StdioTransport
{
  "mcpServers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest"],
      "env": {
        "CHROME_PATH": "/usr/bin/google-chrome"
      }
    }
  }
}

// 远程 MCP Server → StreamableHttpTransport
{
  "mcpServers": {
    "remote-db": {
      "url": "https://api.example.com/mcp",
      "headers": {
        "Authorization": "Bearer ${MY_API_KEY}"
      }
    }
  }
}
```

### 内置特殊 Server

如果检测到 `STEP_API_KEY` 环境变量，自动添加 `step_search` HTTP MCP Server：

```java
// McpConfigLoader.java:71-83
// 自动提供 web_search 和 web_fetch 两个工具
```

---

## 8. 三天的内容串联回顾

```
                     ┌──────────────────────────────────────┐
                     │           ToolRegistry               │
                     │  (mcp__server__tool → 执行器)         │
                     └──────────────────┬───────────────────┘
                                        │
                     ┌──────────────────▼───────────────────┐
                     │         McpServerManager              │  ← ★ 集成层
                     │  管理生命周期 / 注册工具 / 通知处理      │
                     └──────┬─────────────────────┬─────────┘
                            │                     │
              ┌─────────────▼─────────┐  ┌────────▼──────────┐
              │  McpClient(chrome)    │  │  McpClient(db)     │
              │  listTools/callTool   │  │  listTools/callTool│
              └──────────┬───────────┘  └────────┬───────────┘
                         │                       │
              ┌──────────▼───────────┐  ┌────────▼───────────┐
              │   JsonRpcClient      │  │  JsonRpcClient      │  ← 协议层
              │   request/notificatin│  │  request/notificatin│
              └──────────┬───────────┘  └────────┬───────────┘
                         │                       │
              ┌──────────▼───────────┐  ┌────────▼───────────┐
              │  StdioTransport      │  │StreamableHttpTranspt│  ← 传输层
              │  stdin/stdout/stderr │  │HTTP POST + SSE      │
              └──────────┬───────────┘  └────────┬───────────┘
                         │                       │
              ┌──────────▼───────────┐  ┌────────▼───────────┐
              │ npx chrome-devtools  │  │ https://api.xxx/mcp│
              │ (本地子进程)          │  │ (远程 HTTP 服务)     │
              └──────────────────────┘  └────────────────────┘
```

---

## 面试亮点

> MCP 集成层的核心是 `McpServerManager`，它通过四个步骤将 MCP 工具无缝接入 Agent：
>
> 1. **配置加载**：合并用户级和项目级的 mcp.json，支持 `${VAR}` 环境变量展开，内置 step_search 特殊 Server
>
> 2. **并行启动**：使用独立 daemon 线程池同时启动多个 MCP Server，单个失败不影响其他；支持可配置超时阈值，超时后不阻塞 CLI 启动，未完成的 Server 在后台继续启动
>
> 3. **动态注册**：通过 `replaceMcpToolOutputsForServer()` 将 MCP 工具以 `mcp__{server}__{tool}` 命名规约注册到 ToolRegistry 的 mcpTools 表，与 9 个内置工具统一暴露给 LLM。执行时 ToolRegistry 先查 mcpTools 表，命中后走 MCP 执行路径
>
> 4. **运行时热更新**：通过 NotificationRouter 监听 `notifications/tools/list_changed` 通知，Server 运行中更新工具列表时自动同步到 ToolRegistry，无需重启 Agent
>
> 安全方面，所有 `mcp__` 前缀工具通过 ApprovalPolicy 纳入 HITL 审批范围，用户在终端可通过 y/n/a/server/s/m 进行维度化的审批控制，连续浏览器操作时可一键放行整个 Server，兼顾安全与效率。

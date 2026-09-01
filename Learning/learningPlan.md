# PaiCLI 项目学习计划

> 基于方案 B 的五条简历条目：MCP → Multi-Agent → Memory → ReAct+ToolRegistry → Agentic Search+RAG

---

## 项目速览

| 维度 | 数据 |
|------|------|
| 语言 | Java 17 |
| 构建 | Maven (maven-shade-plugin 打包 fat JAR) |
| 代码量 | 约 29,000 行 Java (主源码) + 12,500 行测试 |
| 核心模块 | 28 个包，182 个源文件 |
| 入口 | com.paicli.cli.Main |
| 三大执行模式 | ReAct (默认) / Plan-and-Execute (/plan) / Multi-Agent (/team) |

**技术栈一览：**

| 分类 | 技术 |
|------|------|
| 语言/构建 | Java 17, Maven |
| HTTP | OkHttp 4.12.0 |
| JSON | Jackson 2.16.0 |
| 终端 UI | JLine 4.0.0 + Lanterna 3.1.3 |
| 数据库 | SQLite (sqlite-jdbc) |
| AST 分析 | JavaParser 3.28.0 |
| HTML 提取 | Jsoup 1.18.1 |
| 中文分词 | Jieba |
| Git | JGit 7.6.0 |
| 测试 | JUnit 5, MockWebServer, Mockito |
| LLM Providers | GLM, DeepSeek, StepFun, Kimi, FreeLLMAPI |

---

## 学习路线总览

```
第 1 步：俯瞰全局 —— 搞清楚项目的主干脉络 (1-2 天)

     V

第 2 步：逐一攻克 5 大技术点 (按简历顺序，每个 2-3 天)
    - 2.1 MCP 集成 (简历第 1 条)
    - 2.2 Multi-Agent 架构 (简历第 2 条)
    - 2.3 三层记忆系统 (简历第 3 条)
    - 2.4 ReAct + ToolRegistry (简历第 4 条)
    - 2.5 Agentic Search + RAG (简历第 5 条)

     V

第 3 步：串联复盘 —— 理解各模块如何协作 (1 天)
```

---

## 第 1 步：俯瞰全局 (建议 1-2 天)

**目标：** 不看代码细节，先建立项目的"地图"。这一步最重要，决定了后面深入时不会迷路。

### 1.1 入口到命令分发 (cli 包)

| 阅读顺序 | 文件 | 核心问题 |
|----------|------|---------|
| 1 | src/main/java/com/paicli/cli/Main.java | 启动流程？3 种渲染模式怎么选择？ |
| 2 | src/main/java/com/paicli/cli/CliCommandParser.java | 斜杠命令 (/plan、/team) 如何分发？ |
| 3 | src/main/java/com/paicli/prompt/PromptAssembler.java | 最终发给 LLM 的提示词如何逐层装配？ |

### 1.2 三大执行模式的入口

| 模式 | 类 | 触发方式 | 一句话理解 |
|------|-----|---------|-----------|
| ReAct | agent/Agent.java | 默认模式 | LLM 一步一决策的 think-act-observe 循环 |
| Plan-and-Execute | agent/PlanExecuteAgent.java | /plan 命令 | 先规划再执行，DAG 拓扑排序 |
| Multi-Agent | agent/AgentOrchestrator.java | /team 命令 | Planner + Worker + Reviewer 多角色协作 |

**关键理解：** 这三种模式共享 ToolRegistry 和 MemoryManager，区别只在"决策方式"。

### 1.3 Tool 与 LLM 的桥梁

| 阅读顺序 | 文件 | 核心问题 |
|----------|------|---------|
| 1 | src/main/java/com/paicli/tool/ToolRegistry.java | 工具怎么注册、怎么被 LLM 调用？ |
| 2 | src/main/java/com/paicli/llm/LlmClient.java (接口) | 多模型适配层怎么抽象的？ |
| 3 | src/main/java/com/paicli/llm/LlmClientFactory.java | 如何根据环境变量切换 Provider？ |

### 1.4 输出渲染

快速浏览 render/ + inline/，知道有 3 种渲染模式 (inline / TUI / plain) 即可，不必深入。

### 1.5 核心流程图

学完以上内容后，你应该能画出这样的图：

```
用户输入
    |
    V
CliCommandParser 解析 ( /开头 -> 技能/模式切换；否则 -> LLM )
    |
    V
Agent / PlanExecuteAgent / AgentOrchestrator 之一
    |
    +-- 调用 LLM (LlmClient)
    +-- LLM 返回 tool_calls -> ToolRegistry 执行
    +-- 结果写回 MemoryManager
    +-- 渲染输出 (InlineRenderer / TUI / Plain)
    |
    V
回到循环起点
```

---

## 第 2 步：逐一攻克 5 大技术点 (核心，建议 10-15 天)

---

### 2.1 MCP 集成 (简历第 1 条 | 建议 3 天)

> 集成 MCP 接入外部工具生态，支持 stdio/HTTP 双传输协议和 Schema 自动裁剪，并通过 HITL 审批机制实现工具调用的安全管控。

**为什么放第一？** MCP 是当前 Agent 生态最热话题，也是本项目最大的模块 (29 个文件，全项目最大)。

#### Day 1：理解 MCP 协议骨架

先读协议层，建立概念：

| 优先级 | 文件 | 阅读重点 |
|--------|------|---------|
| 核心 | mcp/protocol/McpTool.java | MCP 工具的数据结构定义 |
| 核心 | mcp/protocol/McpResource.java | MCP 资源的数据结构定义 |
| 核心 | mcp/protocol/McpCapabilities.java | 客户端/服务端的能力声明 |
| 核心 | mcp/protocol/SchemaSanitizer.java | Schema 自动裁剪 (处理超长 tool schema) |
| 辅助 | jsonrpc/JsonRpcClient.java | 手写 JSON-RPC 2.0 客户端 |
| 辅助 | jsonrpc/JsonRpcMessage.java | JSON-RPC 消息模型 |
| 了解 | mcp/mention/MentionParser.java | @资源引用解析 |

**面试点：** Schema 裁剪是为了解决 LLM 上下文窗口有限、MCP tool 的 JSON Schema 过长时被截断的问题。

#### Day 2：传输层——两种实现

| 优先级 | 文件 | 阅读重点 |
|--------|------|---------|
| 核心 | mcp/transport/McpTransport.java (接口) | 传输层抽象，理解多态设计 |
| 核心 | mcp/transport/stdio/StdioTransport.java | 子进程管道通信：ProcessBuilder + std in/out |
| 核心 | mcp/transport/http/StreamableHttpTransport.java | HTTP + SSE 流式解析 (OkHttp) |

**核心对比：**

| 维度 | stdio | Streamable HTTP |
|------|-------|----------------|
| 通信方式 | 子进程 std in/out | HTTP 请求 + SSE 流 |
| 适用场景 | 本地 MCP Server (如 npx 启动) | 远程 MCP Server |
| 生命周期 | 随进程启动/停止 | 独立 HTTP 服务 |
| 实现复杂度 | 较低 | 较高 (SSE 解析) |

**面试点：** 双传输层抽象使 Agent 统一对接本地和远程 MCP 工具，对业务代码透明。

#### Day 3：MCP 集成到 Agent

| 优先级 | 文件 | 阅读重点 |
|--------|------|---------|
| 核心 | mcp/McpServerManager.java | 多 MCP Server 的生命周期管理 |
| 核心 | mcp/McpClient.java | 与 MCP Server 通信的客户端 |
| 核心 | mcp/McpServer.java (模型) | MCP Server 配置和状态 |
| 辅助 | tool/ToolRegistry.java | MCP 工具如何动态注入 (搜索 mcp__ 前缀) |
| 辅助 | mcp/config/McpConfigLoader.java | mcp.json 配置文件加载 |
| 了解 | hitl/HitlToolRegistry.java | HITL 审批如何管控 MCP 调用 |

**核心洞察：** MCP 工具在 Agent 中的命名规则是 mcp__{serverName}__{toolName}，通过 ToolRegistry 动态注册。HITL 审批按操作危险级别分级拦截。

---

### 2.2 Multi-Agent 架构 (简历第 2 条 | 建议 2-3 天)

> 构建 Plan-and-Execute + Multi-Agent 架构，Planner 将复杂任务拆解为 DAG，Worker 并行执行，Reviewer 审核质量并支持自动重试，最大并行 4 线程。

#### Day 1：Planner——任务分解

| 优先级 | 文件 | 阅读重点 |
|--------|------|---------|
| 核心 | plan/Planner.java | LLM 如何将复杂任务拆成子步骤 (JSON 格式输出) |
| 核心 | plan/ExecutionPlan.java | DAG 数据结构：任务节点 + 依赖边 |
| 核心 | plan/Task.java | 单个任务的状态模型 (PENDING/RUNNING/COMPLETED/FAILED/SKIPPED) |

**核心流程：** Planner 将用户请求发给 LLM → LLM 返回 5-10 个子任务的 JSON 计划 → 解析为 ExecutionPlan (DAG) → 执行。

#### Day 2：执行引擎

| 优先级 | 文件 | 阅读重点 |
|--------|------|---------|
| 核心 | agent/AgentOrchestrator.java | Multi-Agent 总控：Planner → Worker → Reviewer |
| 核心 | agent/SubAgent.java | Worker 子 Agent 的实现 (每次只负责一个子任务) |
| 核心 | agent/PlanExecuteAgent.java | Plan-and-Execute 模式串联 |
| 辅助 | agent/AgentBudget.java | Token 预算管理 |

#### Day 3：调度与容错

搜索并理解以下机制：

| 机制 | 要理解的点 |
|------|-----------|
| DAG 拓扑排序 | 如何将 DAG 转为线性执行顺序 |
| 并行执行 | 线程池如何控制最大并行数 (4 线程) |
| 失败容错 | 一个任务失败时，下游如何自动标记为 SKIP |
| 质量审核 | Reviewer 如何审核 Worker 的结果，什么条件下重试 |

**核心调度模型：**

```
用户请求："实现一个用户登录功能"
    |
    V
Planner (LLM) 拆解为 5 个子任务：
    +-- 1. 建数据库表 (无依赖)             -> 立即执行
    +-- 2. 写实体类 (依赖 1)               -> 等 1 完成
    +-- 3. 写登录接口 (依赖 2)             -> 等 2 完成
    +-- 4. 写前端页面 (依赖 2)             -> 等 2 完成 (与 3 并行)
    +-- 5. 写测试用例 (依赖 3, 4)          -> 等 3、4 都完成
```

**面试亮点：**

```text
我们的 Multi-Agent 架构通过 DAG 拓扑排序管理子任务依赖，同批次无依赖任务并行执行。
单个子任务失败时，下游自动标记为 SKIP 而不是阻塞整个工作流。
这意味着"写数据库表"成功但"写接口"失败时，"写测试用例"不会白白等待。
```

---

### 2.3 三层记忆系统 (简历第 3 条 | 建议 2 天)

> 实现三层记忆系统：短期记忆管理当前对话、长期记忆用 JSON 文件持久化跨会话知识、Compactor 做边界感知的上下文压缩，支持 jieba 分词 + 子串匹配检索。

#### 先读接口 (建立概念)

| 优先级 | 文件 | 角色 |
|--------|------|------|
| 核心 | memory/MemoryManager.java | 记忆系统的总控 |
| 核心 | memory/ConversationMemory.java | 短期记忆：当前对话的消息列表 |
| 核心 | memory/LongTermMemory.java | 长期记忆：跨会话持久化（JSON 文件） |
| 核心 | memory/TokenBudget.java | Token 预算管理：根据模型窗口计算可用 Token |
| 辅助 | memory/MemoryRetriever.java | 检索：jieba 分词 + 子串匹配 + 时间衰减 |
| 辅助 | memory/ConversationHistoryCompactor.java | 上下文压缩：Map-Reduce 策略 |

#### 再读关键机制

| 优先级 | 文件 | 阅读重点 |
|--------|------|---------|
| 核心 | memory/ConversationHistoryCompactor.java | 何时触发压缩？压缩策略？哪些消息不压缩？ |
| 核心 | memory/LongTermMemory.java | 什么信息被持久化？存储格式 (JSON)？跨 Session 自动加载？ |
| 核心 | memory/MemoryRetriever.java | jieba 分词 + 子串匹配 + 时间衰减的检索策略 |
| 辅助 | memory/TokenBudget.java | 如何支持不同模型的上下文窗口 (如 DeepSeek 1M) |

#### 观察交互 (搜索追踪)

- 搜索 Compactor 被调用的地方 —— 触发时机：**90% 上下文占用时自动压缩**
- 搜索 LongTermMemory 的读写 —— 持久化时机：**对话结束时提取关键事实**
- 搜索 MemoryManager 在 Agent 中的使用 —— **启动时加载、每轮对话前注入**

#### 三层记忆架构图

```
+--------------------------------------------------+
|                  MemoryManager                     |
+--------------------------------------------------+
|                                                    |
|  +-------------------+  +-------------------+      |
|  |   短期记忆         |  |   长期记忆          |      |
|  | ConversationMemory |  | LongTermMemory    |      |
|  |  (当前对话的消息)    |  | (JSON 文件持久化)  |      |
|  +--------+----------+  +--------+----------+      |
|           |                    |                    |
|           v                    v                    |
|  +----------------------------------------------+  |
|  |      ConversationHistoryCompactor              |  |
|  |  (Map-Reduce 压缩，90% Token 占用时触发)        |  |
|  +----------------------------------------------+  |
|           |                                         |
|           v                                         |
|  +----------------------------------------------+  |
|  |      MemoryRetriever                            |  |
|  |  jieba 分词 + 子串匹配 + 时间衰减              |  |
|  +----------------------------------------------+  |
|                                                    |
+--------------------------------------------------+
```

#### 必须理解的核心机制

| 机制 | 对应类 | 一句话理解 |
|------|--------|-----------|
| 短期记忆 | ConversationMemory | 维护当前对话的消息列表（LinkedHashMap），超预算淘汰最旧 |
| 长期记忆 | LongTermMemory | JSON 文件持久化，跨 Session 自动加载，支持 project/global 作用域 |
| 上下文压缩 | ConversationHistoryCompactor | 达到 90% Token 预算时，Map-Reduce 压缩旧消息，保留最近 3 轮完整 |
| 关键词检索 | MemoryRetriever | jieba 分词 + 子串匹配 + 时间衰减检索（注：BM25 + 余弦相似度在 rag 模块）|
| 预算管理 | TokenBudget | 根据模型窗口大小动态计算可用 Token |

#### 面试亮点

```text
记忆系统的核心设计是分层的：当前对话的逐条消息由短期记忆维护，
当 Token 占用达到 90% 时触发 Compactor，用 Map-Reduce 策略将早期消息压缩为摘要
(保留最近 3 轮完整消息以保证上下文连贯性)。
压缩过程中自动提取关键事实写入长期记忆 (JSON 文件持久化)，下次启动时自动加载。
检索基于 jieba 分词 + 子串匹配 + 时间衰减，长期记忆支持 project/global 双作用域。
（注：向量检索 + 余弦相似度在 rag/CodeRetriever 模块，用于代码语义搜索而非记忆检索）
```

---

### 2.4 ReAct + ToolRegistry (简历第 4 条 | 建议 2 天)

> 基于 ReAct 模式实现 Agent 核心循环，通过 ToolRegistry 动态注册 9 个内置工具 + 60+ MCP 外部工具，工具选择由 LLM Function Calling 驱动。

#### Day 1：ReAct 核心循环

| 优先级 | 文件 | 阅读重点 |
|--------|------|---------|
| 核心 | agent/Agent.java | 最重要的文件之一。理解 think-act-observe 循环结构 |
| 核心 | agent/Agent.java 中的 run() / executeTurn() | 循环的起止和退出条件 |
| 辅助 | 搜索 tool_calls 的解析逻辑 | LLM 返回的 tool_calls 如何被反序列化 |
| 辅助 | 搜索 ExecutorService 在 Agent 中的使用 | 多个 tool_calls 如何并行执行 |

**ReAct 循环伪代码：**

```
while (true) {
    1. 将消息历史 (system + user + assistant + tool) 发给 LLM
    2. LLM 返回响应
       +-- 有 text + stop -> 输出最终回答，结束循环
       +-- 有 tool_calls -> 继续
       +-- 有 text + tool_calls -> 先输出 text，再执行工具
    3. 解析 tool_calls -> 通过 ToolRegistry 执行
       +-- 多个 tool_calls -> ExecutorService 并行执行，按原始顺序返回
    4. 将工具结果追加到消息历史
    5. 回到步骤 1
}
```

#### Day 2：Tool 系统

| 优先级 | 文件 | 阅读重点 |
|--------|------|---------|
| 核心 | tool/ToolRegistry.java | 工具注册表：注册、查找、执行、Schema 管理 |
| 核心 | 搜索 ToolRegistry 的初始化位置 | 9 个内置工具如何注册 (read_file、write_file、grep_code 等) |
| 辅助 | tool/CodeSearchEngine.java | 具体工具的实现 |
| 辅助 | tool/ToolOutput.java | 工具执行返回值的模型 |
| 了解 | 搜索 mcp__ 注册逻辑 | MCP 工具如何动态注入 ToolRegistry |

**9 个内置工具：**

1. read_file - 读取文件
2. write_file - 写入文件
3. list_dir - 列出目录
4. glob_files - 全局模式匹配文件
5. grep_code - 正则搜索代码
6. execute_command - 执行命令
7. create_project - 创建项目
8. search_code - RAG 语义搜索
9. revert_turn - 回退上一步

#### 面试亮点

```text
我们在 ReAct 循环中实现了同一轮多个 tool_calls 的并行执行
——通过 ExecutorService 并行调用工具，结果按原始 tool_call 的 index 顺序组装返回。
这既保证了效率，又保证了 LLM 协议兼容 (LLM 依赖 index 来关联结果)。
ReAct、Plan-and-Execute、Multi-Agent 三种模式复用同一套调度器。
```

---

### 2.5 Agentic Search + RAG (简历第 5 条 | 建议 2 天)

> 基于 ripgrep + Glob + read_file 的组合实现 Agentic Search 的精确检索，单次搜索延迟控制在 200ms 内；并以 RAG search_code 工具作为语义检索兜底，提升仓库代码定位与分析效率。

#### Day 1：Agentic Search (精准检索)

| 优先级 | 文件 | 阅读重点 |
|--------|------|---------|
| 核心 | tool/CodeSearchEngine.java | 三层搜索策略：ripgrep -> Glob -> read_file |
| 辅助 | 搜索 ripgrep 的调用方式 | Java 中如何调用外部 ripgrep 命令 |
| 辅助 | 搜索 Glob 的实现 | 路径模式匹配的实现 |

**三层策略流程：**

```
用户搜索 -> ripgrep (正则搜索源码)
    +-- 匹配到 -> 返回结果 (<200ms)
    +-- 未匹配或结果不理想
        Glob 模式匹配 (按文件路径模式搜索)
        +-- 匹配到 -> 返回结果
        +-- 未匹配
            read_file 兜底 (读取文件内容验证)
```

#### Day 2：RAG 语义检索

| 优先级 | 文件 | 阅读重点 |
|--------|------|---------|
| 核心 | rag/CodeIndex.java | 代码块索引的构建和维护 |
| 核心 | rag/CodeRetriever.java | 检索器的实现：embedding -> 向量搜索 -> 排序 |
| 核心 | rag/VectorStore.java | SQLite 向量存储：JSON 数组持久化 + 内存余弦相似度计算 |
| 核心 | rag/EmbeddingClient.java | Embedding 调用：Ollama 本地 / OpenAI 兼容远程 API |
| 辅助 | rag/CodeChunker.java | 代码分块策略：文件级 -> 类级 -> 方法级 |
| 辅助 | rag/Analyzer.java | 代码分析器 |

**核心指标：** SQLite 向量检索单项目千行级代码块 < 100ms。

#### 双层策略的工作流

```
用户问："帮我找到那个处理 JWT 的类"

    步骤 1：Agentic Search (先试)
    ripgrep 搜索 "JWT" -> 匹配到文件 -> 快速返回 (<200ms)

    匹配失败时：
    步骤 2：RAG 兜底
    用户问题 -> Embedding -> SQLite 向量存储 -> 余弦相似度 -> Top-K 代码块
    |
    V
结果返回给 LLM -> LLM 阅读代码后回答用户
```

#### 分块策略

```
代码文件
    |
    V
文件级分块 (整个文件作为一个块)
    |
    V
类级分块 (每个 class/interface 独立一块)
    |
    V
方法级分块 (每个方法独立一块，包含签名和文档注释)
    |
    V
大块过长的 -> 文本按大小分段 (overlap 策略保证上下文不切断)
```

#### 面试亮点

```text
Search 系统采用双层策略：
第一层是 Agentic Search，用 ripgrep + Glob 实现毫秒级的精确匹配，
覆盖变量名、函数名、关键字等确定性场景；
第二层用 RAG 语义检索兜底，通过 code chunker 将代码按文件/类/方法三级分块后
Embedding 化，用 SQLite 做向量存储，余弦相似度召回，
应对自然语言描述的模糊查询。
200ms 延迟指标保证了 Agent 在 ReAct 循环中的体验——搜索不会成为瓶颈。
```

---

## 第 3 步：串联复盘 (建议 1 天)

### 3.1 核心交互链路

```
用户在终端输入一个问题
    |
    V
PromptAssembler 把 Memory (短期+长期) 注入到提示词
    |
    V
Agent (ReAct 模式) 调用 LLM
    |
    V
LLM 返回 tool_calls (比如 search_code)
    |
    V
ToolRegistry 执行 search_code
    +-- CodeSearchEngine (ripgrep 精准检索)
    +-- 兜底：RAG search_code (语义检索)
    |
    V
结果回到 Agent
    |
    V
Agent 可能触发 MCP 工具调用 -> McpServerManager -> MCP Server
    |
    V
HITL 审批拦截危险操作 -> HitlToolRegistry 弹确认框
    |
    V
最终结果汇集 -> 渲染输出 -> 写入 Memory 历史
```

### 3.2 跨模块问题 (自检清单)

学完第 2 步后，你应该能回答以下问题：

| 问题 | 涉及模块 |
|------|---------|
| 一个完整的用户请求，从输入到输出经历了哪些步骤？ | cli → prompt → agent → llm → tool → render |
| 长期记忆在什么时候被读取和写入？ | 启动时 load → 对话中 read → 对话结束时 extract + save |
| MCP 工具和内置工具有什么区别？ | 内置在 ToolRegistry 初始化时注册；MCP 在 McpServerManager 启动后动态注入 (mcp__ 前缀) |
| 三种 Agent 模式如何共享同一套 ToolRegistry？ | 通过依赖注入，Agent 构造函数接收 ToolRegistry 实例 |
| Compactor 在什么条件下触发？ | 当前消息的 Token 占用达到 TermBudget 的 90% |
| 一个 RAG 搜索请求的完整链路是什么？ | 用户意图 → Embedding → SQLite 余弦相似度 → Top-K 召回 |
| 并行 tool_calls 的结果如何保证与 LLM 协议兼容？ | 按原始 tool_call 的 index 顺序组装返回 |

### 3.3 模块依赖关系图

```
cli (入口)
  |
  +--> prompt (提示词装配)
  |      |
  |      +--> memory (短期+长期记忆注入)
  |
  +--> agent (执行模式)
  |      |
  |      +--> llm (LLM 调用)
  |      +--> tool/ToolRegistry (工具执行)
  |      |      |
  |      |      +--> tool/CodeSearchEngine (Agentic Search)
  |      |      +--> rag/ (RAG 语义检索)
  |      |      +--> mcp/McpServerManager (MCP 外部工具)
  |      |             |
  |      |             +--> mcp/transport/stdio (子进程)
  |      |             +--> mcp/transport/http (SSE)
  |      |             +--> mcp/protocol/ (协议模型)
  |      |
  |      +--> plan/ (DAG 任务管理)
  |      +--> hitl/ (安全审批)
  |      +--> memory/ (记忆读写)
  |
  +--> render/ + inline/ (输出渲染)
```

---

## 总体时间规划

```
第 1 天    俯瞰全局
           - Main.java -> CliCommandParser -> 三大模式入口
           - 理解 LLM -> Tool -> Render 的核心链路

第 2-4 天  MCP 集成
           - 协议层：protocol/ + jsonrpc/
           - 传输层：transport/stdio + transport/http
           - 集成层：McpServerManager + ToolRegistry + HITL

第 5-7 天  Multi-Agent 架构
           - Planner：plan/Planner.java + ExecutionPlan
           - 执行引擎：AgentOrchestrator + SubAgent
           - 调度容错：拓扑排序 + 并行执行 + SKIP 机制

第 8-9 天  三层记忆系统
           - 接口层：MemoryManager + ConversationHistory + LongTermMemory
           - 压缩：ConversationHistoryCompactor (90% 触发)
           - 检索：MemoryRetriever (BM25 + 余弦相似度)

第 10-11 天 ReAct + ToolRegistry
           - ReAct 循环：Agent.java (think-act-observe)
           - 工具系统：ToolRegistry (内置 + MCP 动态注册)

第 12-13 天 Agentic Search + RAG
           - 精准搜索：CodeSearchEngine (ripgrep + Glob + read_file)
           - 语义搜索：rag/ (Chunker + Embedding + VectorStore + Retriever)

第 14 天   串联复盘
           - 画一张完整系统架构图
           - 回答自检清单中的所有跨模块问题
```

---

## 推荐学习方法

1. **每读一个模块前**，先看本计划中的关键文件清单和核心问题，带着问题去读
2. **善用 IDE 搜索** —— 比如看到 MemoryManager.compressIfNeeded()，就搜一下谁调了它，追踪调用链
3. **每学完一个模块**，用自己的话写一段总结：核心设计 + 为什么这样做 + 面试怎么讲
4. **动手实验** —— 可以尝试给项目加一个小功能或修一个 Bug，检验理解
5. **结合简历条目** —— 每学完一个模块，回到简历那条表述，看它是不是涵盖了该模块最核心的亮点
6. **有问题随时问** —— 在对话中直接提出，一起深入源码分析

---

## 附录：关键文件速查表

| 模块 | 核心文件 | 辅助文件 |
|------|---------|---------|
| MCP | McpTool, McpTransport(接口), StdioTransport, StreamableHttpTransport, McpServerManager, McpClient | JsonRpcClient, SchemaSanitizer, McpResource, McpCapabilities, McpConfigLoader |
| Multi-Agent | AgentOrchestrator, Planner, ExecutionPlan, Task, SubAgent | PlanExecuteAgent, AgentBudget |
| Memory | MemoryManager, ConversationMemory, LongTermMemory, ConversationHistoryCompactor, MemoryRetriever, TokenBudget | (无) |
| ReAct+Tool | Agent, ToolRegistry, CodeSearchEngine, ToolOutput | (无) |
| RAG | CodeIndex, CodeRetriever, VectorStore, EmbeddingClient, CodeChunker | Analyzer |
| 通用 | Main, CliCommandParser, PromptAssembler, LlmClient, LlmClientFactory | InlineRenderer, PaiCliCompleter |

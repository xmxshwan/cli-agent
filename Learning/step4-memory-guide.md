# 第 4 步：三层记忆系统 —— 整体概览

> 搭配 `learningPlan.md` 第 2.3 节食用。
>
> 记忆系统实现三层架构：短期记忆管理当前对话、长期记忆用 JSON 文件持久化跨会话知识、Compactor 做边界感知的上下文压缩。

---

## 整体定位

记忆系统为 Agent 提供"记忆能力"——让 Agent 能记住当前对话的上下文，也能跨会话复用之前的经验。

### 三层记忆一览

| 层级 | 对应类 | 存储位置 | 生命周期 | 好比 |
|------|--------|---------|---------|------|
| ① **短期记忆** | `ConversationMemory` | 内存 LinkedHashMap | 当前会话（退出即丢） | 你脑子里的短期记忆 |
| ② **长期记忆** | `LongTermMemory` | 内存 + JSON 文件 | 跨会话（启动自动恢复） | 你记在笔记本上的事 |
| ③ **压缩器** | `ContextCompressor` + `ConversationHistoryCompactor` | — | Token 超阈值时触发 | 你记不住了就写摘要 |

---

## 核心文件清单

**源码位置：** `src/main/java/com/paicli/memory/`

| 文件 | 角色 | 行数 | 优先级 |
|------|------|------|--------|
| `MemoryManager.java` | **门面类**——统一管理三层记忆 | ~263 | ★★★ |
| `Memory.java` | 记忆存储的统一接口 | 54 | ★★☆ |
| `MemoryEntry.java` | 记忆条目的数据模型 | 61 | ★★☆ |
| `ConversationMemory.java` | **短期记忆**——当前对话上下文 | ~147 | ★★★ |
| `LongTermMemory.java` | **长期记忆**——跨会话事实（JSON 文件持久化） | ~256 | ★★★ |
| `ContextCompressor.java` | **压缩器 A**——压 ConversationMemory | ~315 | ★★☆ |
| `ConversationHistoryCompactor.java` | **压缩器 B**——压 Agent 的 message 列表 | ~170 | ★★★ |
| `MemoryRetriever.java` | 检索器——短期+长期混合检索 | ~141 | ★★☆ |
| `TokenBudget.java` | Token 预算管理 | ~151 | ★★☆ |
| `MemoryQueryTokenizer.java` | jieba 分词器 | ~65 | ★☆☆ |

---

## 数据模型：MemoryEntry

**源码：** `MemoryEntry.java:9-60`

```java
class MemoryEntry {
    id: String                              // "user-abc123" / "fact-def456"
    content: String                         // 记忆内容
    type: MemoryType                        // CONVERSATION / FACT / SUMMARY / TOOL_RESULT
    timestamp: Instant                      // 创建时间
    metadata: { source, scope, project }    // 来源、作用域、所属项目
    tokenCount: int                         // 预计算 Token 数

    static estimateTokens(text):            // 中文 ≈ 1.5 字/token，英文 ≈ 4 字符/token
}
```

### MemoryType 四种类型

| 类型 | 说明 | 示例 |
|------|------|------|
| `CONVERSATION` | 用户/助手的对话消息 | 用户说"帮我查 JWT" |
| `FACT` | 跨会话稳定事实 | "用户偏好 DeepSeek 模型" |
| `SUMMARY` | 压缩后生成的对话摘要 | "[历史对话摘要] 用户要求实现登录功能..." |
| `TOOL_RESULT` | 工具执行结果（截断 500 字符） | "[read_file] 文件内容...(已截断)" |

---

## 统一接口：Memory

**源码：** `Memory.java:13-53`

```java
interface Memory {
    void store(MemoryEntry);                    // 存
    Optional<MemoryEntry> retrieve(String id);  // 按 ID 查
    List<MemoryEntry> search(String query, int limit); // 关键词搜索
    List<MemoryEntry> getAll();                 // 全部
    boolean delete(String id);                  // 删
    void clear();                               // 清空
    int getTokenCount();                        // 当前 Token 总量
    int size();                                 // 条目数
}
```

`ConversationMemory` 和 `LongTermMemory` 都实现了这个接口——面向接口编程，`MemoryRetriever` 不关心具体实现。

---

## 三层记忆详解

### ① 短期记忆 (ConversationMemory)

**源码：** `ConversationMemory.java:14-147`

| 属性 | 值 |
|------|-----|
| 存储结构 | `LinkedHashMap<String, MemoryEntry>` |
| 淘汰策略 | Token 超预算时淘汰最旧（evictOldest → 暂存 compressedSummaries） |
| 检索方式 | jieba 分词 + 子串匹配 |

**store() 核心逻辑：**

```java
// 第 31-39 行
public void store(MemoryEntry entry) {
    entries.put(entry.getId(), entry);
    currentTokens += entry.getTokenCount();
    while (currentTokens > maxTokens && entries.size() > 1) {
        evictOldest();  // 淘汰最旧条目
    }
}
```

**淘汰后被压缩回注：**

```java
// 第 124-130 行
public void injectSummary(MemoryEntry summary) {
    compressedSummaries.clear();      // 清空旧的压缩摘要
    entries.put(summary.getId(), summary);  // 注入摘要
    currentTokens += summary.getTokenCount();
}
```

---

### ② 长期记忆 (LongTermMemory)

**源码：** `LongTermMemory.java:25-256`

| 属性 | 值 |
|------|-----|
| 存储结构 | `ConcurrentHashMap<String, MemoryEntry>` + JSON 文件 |
| 存储位置 | `~/.paicli/memory/long_term_memory.json`（默认） |
| 可配置 | `PAICLI_MEMORY_DIR` 环境变量 / `paicli.memory.dir` 系统属性 |
| 跨 Session | 构造时 `loadFromDisk()` 自动加载 |
| 持久化时机 | 每次 `store()` / `delete()` 立即 `saveToDisk()` |
| 去重 | 内容完全重复时跳过（第 59-63 行） |
| 作用域 | `project` → 只对当前项目可见；`global` → 所有项目可见 |

**跨 Session 自动加载：**

```java
// 第 35-53 行：构造时
public LongTermMemory() {
    this(resolveStorageDir());
}
private void loadFromDisk() {
    // 读取 JSON → 反序列化为 MemoryEntry → 放入 entries
    log.info("加载了 {} 条长期记忆", entries.size());
}
```

**即时持久化：**

```java
// 第 164-173 行
private void saveToDisk() {
    mapper.writeValue(storageFile, dataList);  // Jackson 序列化为 JSON
}
```

**项目作用域过滤（第 144-159 行）：**

```java
// global 作用域 → 所有项目可见
// project 作用域 → 仅 metadata.project 匹配的项目可见
```

---

### ③ 压缩器——两个互补的实现

#### 3a. ContextCompressor（压 ConversationMemory）

**源码：** `ContextCompressor.java:19-315`

| 属性 | 值 |
|------|-----|
| 压缩目标 | `ConversationMemory`（PaiCLI 的短期记忆条目） |
| 调用方 | `MemoryManager.compressIfNeeded()` |
| 触发条件 | 短期记忆 Token ≥ 预算 × 0.9（90%） |
| 压缩策略 | Map-Reduce（每 5 条一批 → LLM 摘要 → LLM 合并） |
| 保留 | 最近 3 轮完整消息 |
| 事实提取 | `extractFacts()` → 提取稳定事实写入 LongTermMemory |

**Map-Reduce 流程：**

```
旧消息列表（N 条）
    │
    ├── Map 阶段: 每 5 条一组 → LLM 摘要 → 多段摘要
    │   （第 195-225 行，LLM 调用失败时降级为截取前 200 字）
    │
    └── Reduce 阶段: 多段摘要 → LLM 合并为一段最终摘要
        （第 230-247 行，LLM 调用失败时降级为分号拼接）
    │
    ▼
清空短期记忆 → 注入摘要 MemoryEntry → 回注最近 3 轮消息
    （第 127-139 行）
```

**事实提取（第 148-190 行）：**

```java
public List<String> extractFacts(List<MemoryEntry> entries, LongTermMemory longTermMemory) {
    // 将对话发给 LLM → LLM 提取"稳定事实"
    // 过滤规则：
    //   ✗ 含"用户想/帮我/新建/创建"等临时性前缀 → 丢弃
    //   ✗ 含"可能/应该/猜测"等推测性词汇 → 丢弃
    //   ✓ 含"用户偏好/项目/技术栈/版本/配置"等长期线索 → 保留
    //   ✓ 含冒号（描述了某个属性）→ 保留
}
```

#### 3b. ConversationHistoryCompactor（压 Agent 的消息列表）

**源码：** `ConversationHistoryCompactor.java:32-169`

| 属性 | 值 |
|------|-----|
| 压缩目标 | Agent 的 `List<LlmClient.Message>`（即发给 LLM 的消息列表） |
| 调用方 | `Agent.run()` / `SubAgent.execute()` 调 LLM 前 |
| 触发条件 | message 列表 Token ≥ `triggerTokens`（由 ContextProfile 提供） |
| 压缩策略 | 保留最近 `retainRecentRounds`（默认 3）轮 → 之前全部 LLM 摘要 |
| 关键约束 | 分割点必须在 **user message 边界**（不切断 tool_call/result 配对） |
| 重建结果 | `system` + `[摘要]` + `[确认回复]` + `[尾部保留]` |

**核心逻辑（第 77-130 行）：**

```java
public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
    // 1. 估算当前 Token → 未超阈值则跳过
    int currentTokens = TokenBudget.estimateMessagesTokens(history);
    if (currentTokens < triggerTokens) return false;

    // 2. 找到所有 user message 的索引
    //    保留最近 retainRecentRounds 个 user 起算的尾部

    // 3. 分割点之前的消息调 LLM 摘要
    String summary = summarize(oldMsgs);

    // 4. 重建: system + [摘要] + [确认] + [尾部保留]
    rebuilt.add(systemMsg);
    rebuilt.add(user("[已压缩的历史对话摘要]\n" + summary));
    rebuilt.add(assistant("好的，我已了解之前的上下文，请继续。"));
    rebuilt.addAll(tailMsgs);

    // 5. 原地替换 history
    history.clear();
    history.addAll(rebuilt);
}
```

---

## 两个压缩器的对比

| | ContextCompressor | ConversationHistoryCompactor |
|---|---|---|
| **压缩目标** | `ConversationMemory`（短期记忆条目） | Agent 的 `List<LlmClient.Message>` |
| **谁调用** | `MemoryManager.compressIfNeeded()` | `Agent.run()` / `SubAgent.execute()` 调 LLM 前 |
| **触发条件** | 短期记忆 Token ≥ 预算 × 90% | message 列表 Token ≥ triggerTokens |
| **压缩方式** | Map（分片 LLM 摘要）→ Reduce（合并） | 保留最近 N 轮→之前全部 LLM 摘要 |
| **事实提取** | ✅ 提取稳定事实 → 写入 LongTermMemory | ❌ 无 |
| **分割约束** | 按条目数量（5 条一批） | 必须在 user message 边界（不切断 tool_call） |
| **结果处理** | 清空 → 注入摘要 → 回注近期 | 原地替换 history 列表 |

---

## MemoryRetriever 检索

**源码：** `MemoryRetriever.java:14-141`

```
retrieve(query, limit)
    │
    ├── 短期记忆检索:
    │    for each entry → computeRelevanceScore(entry, query)
    │
    ├── 长期记忆检索:
    │    for each entry → computeRelevanceScore × 1.2 加权
    │
    └── 合并 → 降序排序 → 取 top-N
```

**评分算法（第 109-137 行）：**

```java
computeRelevanceScore(entry, query):
    1. 精确匹配: content 包含整个 query → 分数 = 1.0
    2. 关键词匹配: jieba 分词后逐个 token 子串匹配 → 分数 = 匹配数 / 总数
    3. 时间衰减: max(0.5, 1.0 - ageHours/24)   // 24 小时内从 1.0 衰减到 0.5
```

**构建 system prompt 上下文（第 82-104 行）：**

```java
buildContextForQuery(query, maxTokens):
    → 仅从长期记忆检索（不混合短期记忆）
    → 不超过 maxTokens 限制
    → 格式化为 "## 相关长期记忆\n- [FACT] ...\n" 文本
    → 供 Agent 注入到 system prompt
```

---

## TokenBudget 预算管理

**源码：** `TokenBudget.java:15-151`

```
可用对话预算 = contextWindow - 500(sys) - 800(tools) - 2000(response)

模型举例:
  DeepSeek (1M)    → 可用 ≈ 996,700
  GLM-4   (200K)   → 可用 ≈ 196,700
  Step    (256K)   → 可用 ≈ 252,700
  Kimi    (256K)   → 可用 ≈ 252,700
```

`needsCompression(memory, ratio)`：
- 当 `memory.getTokenCount() >= memory.getMaxTokens() × ratio`
- 默认 `ratio = 0.9`（90% 占用触发压缩）

---

## 完整数据流：一个用户请求

```
用户输入 "帮我查一下 JWT 的处理逻辑"
    │
    ▼
MemoryManager.addUserMessage(content)          ← MemoryManager.java:77
    │
    ├── ① 包装为 MemoryEntry(CONVERSATION)
    │    存入 shortTermMemory.store(entry)       ← ConversationMemory.java:31
    │    → 超出 maxTokens？淘汰最旧
    │
    └── ② compressIfNeeded()                    ← MemoryManager.java:190
            │
            ├── TokenBudget.needsCompression()?   ← TokenBudget.java:68
            │   当前 Token ≥ 预算 × 90%？
            │
            └── 是 → ContextCompressor.compress() ← ContextCompressor.java:101
                        │
                        ├── Map: 旧消息分片 → LLM 摘要
                        ├── Reduce: 合并摘要
                        ├── extractFacts → 写入 LongTermMemory
                        └── 清空 → 注入摘要 → 回注近期
    │
    ▼
Agent 调 LLM 前
    │
    ├── ConversationHistoryCompactor.compactIfNeeded()  ← 第 77 行
    │   → message 列表 Token 超阈值？
    │      → 保留最近 3 轮 → 之前全部摘要
    │      → 原地替换 history
    │
    ├── MemoryRetriever.buildContextForQuery(query, maxTokens)
    │   → 仅从 LongTermMemory 检索                    ← 第 82 行
    │   → 格式化为 "## 相关长期记忆" → 注入 system prompt
    │
    └── MemoryManager.recordTokenUsage(input, output)  ← 第 177 行
        → TokenBudget 记录统计
```

---

## 架构总图

```
┌───────────────────────────────────────────────────────────────┐
│                        MemoryManager                           │
│                  三层记忆的统一门面                             │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────────────┐  ┌──────────────────────────┐       │
│  │ 短期记忆              │  │ 长期记忆                  │       │
│  │ ConversationMemory    │  │ LongTermMemory           │       │
│  │ LinkedHashMap         │  │ ConcurrentHashMap        │       │
│  │ (内存)                │  │ + JSON 文件 (~/.paicli/) │       │
│  │ Token 超预算→淘汰最旧  │  │ 跨 Session 自动加载       │       │
│  │                       │  │ project/global 双作用域  │       │
│  └──────────┬───────────┘  └────────────┬─────────────┘       │
│             │                           │                      │
│             ▼                           ▼                      │
│  ┌────────────────────────────────────────────────────────┐   │
│  │               ConversationHistoryCompactor               │   │
│  │          Map-Reduce 压缩（90% Token 触发）                │   │
│  │          保留最近 3 轮，提取事实→写入长期记忆             │   │
│  └────────────────────────┬───────────────────────────────┘   │
│                           │                                    │
│                           ▼                                    │
│  ┌────────────────────────────────────────────────────────┐   │
│  │                   MemoryRetriever                        │   │
│  │          jieba 分词 + 子串匹配 + 时间衰减               │   │
│  │          短期+长期混合 → 排序 → top-N                   │   │
│  └────────────────────────────────────────────────────────┘   │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

---

## 面试亮点

> 记忆系统的核心设计是分层的：当前对话的逐条消息由短期记忆维护（LinkedHashMap，超预算淘汰最旧），当 Token 占用达到 90% 时触发 Compactor，用 Map-Reduce 策略将早期消息压缩为摘要（保留最近 3 轮完整消息以保证上下文连贯性）。压缩过程中自动提取关键事实写入长期记忆（JSON 文件持久化，`~/.paicli/memory/`），下次启动时自动加载。ConversationHistoryCompactor 则在 Agent 调 LLM 前对消息列表做第二次压缩，分割点落在 user message 边界避免切断 tool_call 配对。检索基于 jieba 分词 + 子串匹配 + 时间衰减，长期记忆支持 project/global 双作用域隔离。

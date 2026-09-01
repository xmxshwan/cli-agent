# 第 6 步：Agentic Search + RAG —— 代码搜索的双层策略

> 搭配 `learningPlan.md` 第 2.5 节食用。
>
> 搜索系统采用双层策略：Agentic Search（精确检索）优先，RAG（语义检索）兜底。

---

## 整体定位

搜索系统是 Agent 的"眼睛"——让 LLM 能快速找到项目中的相关代码。

```
Agent 决定搜索代码
         │
         ├── grep_code / glob_files / read_file     ← Agentic Search（精确）
         │   └── 优先使用 ripgrep，不可用时回退 Java 扫描
         │      结果 < 200ms，精确匹配变量名/类名/函数名
         │
         └── search_code                             ← RAG 语义检索（兜底）
             └── 需要先 /index 构建索引
                 自然语言描述 → Embedding → SQLite 余弦相似度
```

### 使用场景

| 场景 | 该用哪个 | 为什么 |
|------|---------|--------|
| "找 JwtUtil 这个类在哪" | `glob_files` | 按文件名精确匹配，最快 |
| "搜索 JWT 关键字出现在哪些文件" | `grep_code` | 按内容正则/关键字匹配 |
| "读取 UserController.java 第 50-80 行" | `read_file` | 按行号范围读取 |
| "用户登录的逻辑是怎么实现的" | `search_code` | 自然语言描述，语义匹配 |
| "找到 handleLogin 方法" | `grep_code` | 精确符号定位 |

---

## 核心文件清单

| 文件 | 角色 | 行数 | 优先级 |
|------|------|------|--------|
| `tool/CodeSearchEngine.java` | 搜索接口 + 数据模型 | 32 | ★★☆ |
| `tool/RipgrepCodeSearchEngine.java` | **ripgrep 实现**——优先 | ~214 | ★★★ |
| `tool/JavaCodeSearchEngine.java` | Java 回退实现——兜底 | ~158 | ★★☆ |
| `rag/CodeIndex.java` | 索引管理器——构建/持久化 | ~178 | ★★★ |
| `rag/CodeChunker.java` | 代码分块器 | ~138 | ★★★ |
| `rag/CodeChunk.java` | 代码块数据模型 | 46 | ★★☆ |
| `rag/CodeRetriever.java` | **混合检索入口**——语义+关键词 | ~168 | ★★★ |
| `rag/VectorStore.java` | **SQLite 向量存储**——余弦相似度 | ~360 | ★★★ |
| `rag/EmbeddingClient.java` | Embedding API 客户端 | ~160 | ★★☆ |
| `rag/SearchResultFormatter.java` | 检索结果格式化 | ~116 | ★☆☆ |
| `rag/CodeAnalyzer.java` | Java 代码关系分析 | - | ★☆☆ |
| `rag/RagQueryTokenizer.java` | RAG 查询分词 | - | ★☆☆ |
| `tool/ToolRegistry.java` (相关部分) | `grep_code` / `search_code` 工具注册 | - | ★★★ |

---

## 一、Layer 1：Agentic Search（精确检索）

### 总策略：三层逐级尝试

```
Agent 调用 grep_code / glob_files
    │
    ├── grep_code → RipgrepCodeSearchEngine (优先)
    │                  │
    │                  ├── rg 可用? → 调用子进程 rg --json + 解析输出
    │                  │              第 34-80 行
    │                  │
    │                  └── rg 不可用? → JavaCodeSearchEngine (回退)
    │                                  第 202-204 行
    │                                  Files.walkFileTree + 正则匹配
    │
    ├── glob_files → Files.walkFileTree + PathMatcher
    │                 第 382-421 行
    │
    └── read_file → Files.readString / Files.readAllLines
                     第 350-380 行
```

### RipgrepCodeSearchEngine（优先）

**源码：** `RipgrepCodeSearchEngine.java:23-213`

优势：**毫秒级**搜索，支持正则、上下文行、JSON 结构输出。

```java
// 第 34-80 行：核心搜索方法
public CodeSearchResult search(CodeSearchRequest request) {
    if (!isRipgrepAvailable()) {
        return fallback(request);  // → JavaCodeSearchEngine
    }

    // 组装 rg 命令
    ProcessBuilder pb = new ProcessBuilder(command(request));
    // rg --json --color=never --line-number --max-filesize 2M
    //    [-i] [--fixed-strings] [-C <上下文行数>]
    //    [--glob !.git/**] [--glob <glob>] <query> <目录>

    process = pb.start();
    // 后台线程异步解析输出
    Future<ParsedRipgrepOutput> outputFuture =
        readerExecutor.submit(() -> parseOutput(process.getInputStream()));

    // 8 秒超时
    boolean finished = process.waitFor(8, TimeUnit.SECONDS);
    if (!finished) {
        process.destroyForcibly();
        return partial("rg 搜索超时 8 秒");
    }
}
```

**参数预算控制：**

```java
// ToolRegistry.java:430-435 定义的 grep_code 参数上限
int maxResults = min(maxResults, 200);       // 最多 200 条匹配
int headLimit = min(headLimit, 50);          // 单文件最多 50 条
int contextLines = min(contextLines, 5);     // 上下文最多 5 行
int maxChars = min(maxChars, 60_000);        // 结果字符串最多 60000 字符
```

**结果约束（防止输出撑爆 Token）：**

```java
// RipgrepCodeSearchEngine.java:107-113
if (matches.size() >= request.maxResults()) {
    partial = true;  // 标记结果不完整
    process.destroyForcibly();  // 强制终止 rg 进程
    break;
}

// 第 115-121 行：单文件 headLimit 限制
int currentFileMatches = perFileMatches.getOrDefault(file, 0);
if (currentFileMatches >= request.headLimit()) {
    partial = true;  // 该文件已超过上限，跳过后续匹配
    continue;
}
```

### JavaCodeSearchEngine（回退）

**源码：** `JavaCodeSearchEngine.java:19-157`

当 ripgrep 不可用时（Windows 未安装 rg、超时、出错），自动回退到纯 Java 实现：

```java
// 第 28-89 行
public CodeSearchResult search(CodeSearchRequest request) {
    // Files.walkFileTree 遍历文件
    // → 跳过 excluded dirs（.git, target, node_modules 等）
    // → glob 过滤
    // → 逐行正则匹配
    // → 收集匹配结果 + 上下文行

    // 相同的预算控制: maxResults / headLimit / maxChars
}
```

**回退链路：**

```
RipgrepCodeSearchEngine.search()
    │
    ├── rg 可用? → 用 rg
    ├── rg 超时? → fallback → JavaCodeSearchEngine
    ├── rg 异常? → fallback → JavaCodeSearchEngine
    └── 系统属性 paicli.search.disable.rg=true? → fallback → JavaCodeSearchEngine
```

### 数据模型

```java
// CodeSearchEngine.java:6-32
interface CodeSearchEngine {
    CodeSearchResult search(CodeSearchRequest request);
}

record CodeSearchRequest(String query, Path root, Path projectRoot,
    String glob, boolean regex, boolean caseSensitive,
    int contextLines, int maxResults, int headLimit) {}

record CodeSearchResult(String engine,               // "rg" 或 "java"
    List<GrepMatch> matches, boolean partial, String partialReason) {}

record GrepMatch(String file, int lineNumber, List<ContextLine> context) {}

record ContextLine(int lineNumber, String text) {}
```

---

## 二、Layer 2：RAG 语义检索（兜底）

### 总流程

```
用户使用 /index 命令 → CodeIndex.index(projectPath)
    │
    ├── collectFiles() → 遍历项目，收集代码文件
    ├── CodeChunker.chunkFile() → 分块
    ├── EmbeddingClient.embed() → 生成向量
    └── VectorStore.insertChunks() → SQLite 持久化
    │
Agent 调用 search_code("用户登录的逻辑")
    │
    ├── CodeRetriever.hybridSearch(query, topK)
    │   ├── semanticSearch(): Embedding → 余弦相似度
    │   └── keywordSearch(): SQLite LIKE
    │   └── 合并 → 排序 → 类型加分 → 文件去重
    │
    └── SearchResultFormatter.formatForTool() → 返回给 LLM
```

### 2.1 CodeIndex —— 索引构建

**源码：** `CodeIndex.java:15-177`

```java
public IndexResult index(String projectPath) {
    // 1. 遍历文件树，收集代码文件（第 129-174 行）
    List<Path> filesToIndex = collectFiles(root);
    // 支持的扩展名: .java .py .js .ts .go .rs .c .cpp .h .md
    //             .xml .properties .yaml .yml .json .sh .gradle .kt
    // 排除的目录: node_modules target build .git .idea .vscode dist out

    for (Path file : filesToIndex) {
        // 2. 分块（第 85 行）
        List<CodeChunk> chunks = chunker.chunkFile(file);

        // 3. 生成 Embedding（第 89 行）
        for (CodeChunk chunk : chunks) {
            float[] embedding = embeddingClient.embed(chunk.toEmbeddingText());
            entries.add(new VectorStore.CodeChunkEntry(chunk, embedding));
        }

        // 4. 分析依赖关系（仅 Java 文件，第 94 行）
        allRelations.addAll(analyzer.analyzeFile(file));
    }

    // 5. 持久化到 SQLite（第 105 行）
    try (VectorStore store = new VectorStore(root)) {
        store.clearProject();       // 清空旧索引
        store.insertChunks(entries);     // 写入分块+向量
        store.insertRelations(allRelations); // 写入关系图
    }
}
```

### 2.2 CodeChunker —— 分块策略

**源码：** `CodeChunker.java:23-137`

```
输入: 代码文件
    │
    ├── 非 Java 文件 → 按 MAX_CHUNK_CHARS=2000 分段
    │                   整个文件≤2000字符 → 1 个 file 级 chunk
    │                   超过 → 逐行切分，每段 ≤2000 字符
    │
    └── Java 文件 → AST 解析 (JavaParser)
        │
        ├── 类级别 chunk: [class:ClassName] 类签名+前5行
        │    type = "class"
        │
        ├── 方法级别 chunk: [method:ClassName.methodName] 完整方法体
        │    type = "method"
        │
        └── AST 解析失败 → 回退到按大小分段
```

```java
// CodeChunk.java:13-45
public record CodeChunk(String filePath, String chunkType, String name,
                        String content, int startLine, int endLine) {
    static CodeChunk fileChunk(filePath, content);    // type="file"
    static CodeChunk classChunk(filePath, className, content, ...);  // type="class"
    static CodeChunk methodChunk(filePath, methodName, content, ...); // type="method"

    public String toEmbeddingText() {
        return "[%s:%s] %s".formatted(chunkType, name, content);
        // 例如: "[method:JwtUtil.parseToken] public static String parseToken(String token) {...}"
    }
}
```

### 2.3 EmbeddingClient —— 向量生成

**源码：** `EmbeddingClient.java:14-159`

```java
public float[] embed(String text) throws IOException {
    // 截断超长文本（≤2000 字符，适配 8192 上下文）
    String input = text.length() > MAX_INPUT_CHARS
        ? text.substring(0, MAX_INPUT_CHARS) : text;

    return switch (provider) {
        case "ollama" -> embedOllama(input);      // localhost:11434/api/embeddings
        case "openai", "zhipu", "glm" -> embedOpenAICompatible(input);
        default -> embedOllama(input);             // 默认 Ollama
    };
}

// 配置方式
EMBEDDING_PROVIDER  // ollama（默认）| openai | zhipu | glm
EMBEDDING_MODEL     // nomic-embed-text:latest（默认）
EMBEDDING_BASE_URL  // http://localhost:11434（默认）
EMBEDDING_API_KEY   // 仅 openAI 兼容模式需要
```

### 2.4 VectorStore —— SQLite 向量存储

**源码：** `VectorStore.java:17-360`

```java
// 第 30-31 行：SQLite 连接
String dbPath = "~/.paicli/rag/codebase.db";
this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

// 第 35-81 行：建表
CREATE TABLE code_chunks (
    id INTEGER PRIMARY KEY,
    project_path TEXT, file_path TEXT, chunk_type TEXT,
    name TEXT, content TEXT,
    embedding_json TEXT,          // ← 向量以 JSON 数组存
    created_at TIMESTAMP
);

CREATE TABLE code_relations (
    id INTEGER PRIMARY KEY,
    project_path TEXT, from_file TEXT, from_name TEXT,
    to_file TEXT, to_name TEXT, relation_type TEXT
);
```

**核心方法——余弦相似度检索：**

```java
// 第 162-190 行
public List<SearchResult> search(float[] queryEmbedding, int topK) {
    // 1. 读出该项目的所有代码块
    SELECT * FROM code_chunks WHERE project_path = ?;

    // 2. 对每一条，在内存计算余弦相似度
    for each row:
        float[] storedEmbedding = jsonToEmbedding(row.embedding_json);
        double similarity = cosineSimilarity(queryEmbedding, storedEmbedding);

    // 3. 降序排序，取 topK
    candidates.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
    return candidates.subList(0, min(topK, candidates.size()));
}

// 第 303-319 行：余弦相似度实现
private double cosineSimilarity(float[] a, float[] b) {
    double dot = 0.0, normA = 0.0, normB = 0.0;
    for (int i = 0; i < a.length; i++) {
        dot += a[i] * b[i];
        normA += a[i] * a[i];
        normB += b[i] * b[i];
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
}
```

**关键词检索（兜底）：**

```java
// 第 195-221 行：SQLite LIKE 精确匹配
public List<SearchResult> searchByKeyword(String keyword) {
    SELECT file_path, chunk_type, name, content FROM code_chunks
    WHERE project_path = ? AND (name LIKE ? OR content LIKE ?);
    // 关键词结果 similarity 固定为 0.3（低于语义结果的 0-1.0 范围）
}
```

### 2.5 CodeRetriever —— 混合检索

**源码：** `CodeRetriever.java:18-167`

```java
// 第 50-82 行：混合检索（语义 + 关键词 + 排序）
public List<SearchResult> hybridSearch(String query, int topK) {
    // 1. 语义检索（×2 召回）
    for (result : semanticSearch(query, max(topK * 2, 10))) {
        merged.put(key, result);
    }

    // 2. 关键词检索
    for (keyword : RagQueryTokenizer.tokenize(query)) {
        for (result : keywordSearch(keyword)) {
            merged.put(key, boostKeywordMatch(result, keyword));
        }
    }

    // 3. 类型加分 + 排序 + 文件去重
    for (result : merged.values()) {
        double typeBoost = switch (result.chunkType()) {
            case "method" -> 0.15;     // 方法级最精确
            case "class"  -> 0.10;     // 类级次之
            default       -> 0.0;
        };
        similarity += typeBoost;
    }
    sort(descending similarity);
    return limitPerFile(sorted, topK, 2);  // 同一文件最多 2 个结果
}
```

**双重命中奖励（第 91-99 行）：**

```
关键词 + 语义 同时命中 → similarity + 0.1（只奖一次）
方法名关键词命中 → +0.3
文件路径关键词命中 → +0.1
内容关键词命中 → +0.1
```

### 2.6 SearchResultFormatter —— 结果展示

**源码：** `SearchResultFormatter.java:16-115`

两种格式化：

```java
// CLI 展示（给用户看）— 第 21 行
formatForCli(query, results) → 带摘要 + 代码片段

// 工具结果（给 LLM 看）— 第 41 行
formatForTool(query, results) → 带相似度 + 文件路径 + 片段

// 例如:
// 检索结果:
// 1. [method:parseToken] (相似度: 0.873) src/main/java/util/JwtUtil.java
//    public static String parseToken(String token) {
//      Claims claims = Jwts.parser()...
```

---

## 三、Agent 视角的两层搜索协作

```
Agent 收到 "帮我查一下 JWT 的处理逻辑"
    │
    ├── 第 1 步：grep_code("JWT")  ← Agentic Search
    │   → ripgrep 搜索 "JWT" 关键字
    │     → 找到 JwtUtil.java:35
    │     → read_file(JwtUtil.java) 读取内容
    │     → 直接给 LLM，快速回答
    │
    └── 第 2 步（如果 grep_code 没找到结果）：
        search_code("JWT token 解析的逻辑")  ← RAG 语义兜底
          → Embedding → SQLite 余弦相似度
          → 找到相关方法
          → 返回给 LLM
```

### 搜索工具的参数对比

```java
// grep_code — 精确搜索（ToolRegistry.java:423-447）
grep_code(query, glob?, regex?, case_sensitive?,
          context_lines?, max_results?, head_limit?, max_chars?)

// glob_files — 文件名匹配（ToolRegistry.java:382-421）
glob_files(pattern, path?, max_results?)

// read_file — 读取内容（ToolRegistry.java:350-380）
read_file(path, offset?, limit?)

// search_code — RAG 语义检索（ToolRegistry.java:577-612）
search_code(query, top_k?)
```

### 预算控制参数一览

| 参数 | 默认 | 上限 | 对应工具 |
|------|------|------|---------|
| `max_results` | 50 | 200 | grep_code |
| `head_limit`（单文件） | 20 | 50 | grep_code |
| `context_lines` | 0 | 5 | grep_code |
| `max_chars`（结果总字符） | 24000 | 60000 | grep_code |
| `max_results`（glob） | 50 | 200 | glob_files |
| `top_k`（RAG） | 5 | 30 | search_code |

---

## 四、完整数据流图

```
                             搜索请求
                                │
                    ┌───────────┴───────────┐
                    │                       │
                    ▼                       ▼
           grep_code / glob_files     search_code (需先 /index)
                    │                       │
                    ▼                       ▼
         ┌──────────────────┐     ┌────────────────────┐
         │  RipgrepCodeSearch  │     │  CodeRetriever     │
         │  (优先) / Java (兜底)│     │  hybridSearch()    │
         └────────┬─────────┘     └──────────┬─────────┘
                  │                          │
                  ▼                          ▼
          ┌───────────────┐         ┌────────────────────┐
          │  grep 结果     │         │ semanticSearch()    │
          │  List<GrepMatch>│         │   → EmbeddingClient │
          │  文件:行号+上下文 │         │   → VectorStore    │
          └───────────────┘         │     余弦相似度       │
                                    │ +keywordSearch()     │
                                    │   SQLite LIKE        │
                                    │ +merge + rank + dedup│
                                    └──────────┬─────────┘
                                               │
                                               ▼
                                    ┌────────────────────┐
                                    │ SearchResultFormatter│
                                    │ formatForTool()     │
                                    └──────────┬─────────┘
                                               │
                                               ▼
                                    返回给 LLM 阅读
```

---

## 五、索引构建流程

```
用户执行 /index
    │
    ▼
CodeIndex.index(projectPath)
    │
    ├── collectFiles(root)
    │   Files.walkFileTree
    │   → 跳过 .git / target / node_modules / .idea ...
    │   → 只收集代码文件（.java .py .js .ts .go ...）
    │
    ├── for each file:
    │   ├── CodeChunker.chunkFile(file)     → 分块
    │   │   ├── Java 文件 → AST 解析 → class 级 + method 级
    │   │   └── 其他文件 → 按 2000 字符分段
    │   │
    │   ├── EmbeddingClient.embed(text)     → 向量
    │   │   └── 配置: EMBEDDING_PROVIDER / MODEL / BASE_URL
    │   │
    │   └── CodeAnalyzer.analyzeFile(file)  → 关系（仅 Java）
    │       └── 类继承、方法调用等依赖关系
    │
    ├── VectorStore 持久化
    │   ├── store.clearProject()            → 清空旧索引
    │   ├── store.insertChunks(entries)     → 写入分块+向量
    │   └── store.insertRelations(relations)→ 写入关系图
    │
    └── 输出: "索引完成：153 个代码块，42 条关系"
```

---

## 六、关键源码行速查

| 功能 | 文件 | 行号 |
|------|------|------|
| grep_code 工具注册 | `ToolRegistry.java` `registerRagTools()` | 577 |
| grep_code 实现入口 | `ToolRegistry.java` `grepCode()` | 423 |
| search_code 工具注册 | `ToolRegistry.java` 第 578-612 行 | 578 |
| ripgrep 搜索 | `RipgrepCodeSearchEngine.java` `search()` | 34 |
| rg 不可用时回退 | `RipgrepCodeSearchEngine.java` `fallback()` | 202 |
| rg 超时 8 秒 | `RipgrepCodeSearchEngine.java` 第 55 行 | 55 |
| rg 结果预算限制 | `RipgrepCodeSearchEngine.java` 第 107-121 行 | 107 |
| Java 回退搜索 | `JavaCodeSearchEngine.java` `search()` | 29 |
| Java 实现二进制检测 | `JavaCodeSearchEngine.java` `isLikelyBinary()` | 127 |
| 索引入口 | `CodeIndex.java` `index()` | 57 |
| 文件遍历+过滤 | `CodeIndex.java` `collectFiles()` | 129 |
| 分块（AST 解析） | `CodeChunker.java` `chunkJavaFile()` | 80 |
| 分块（非 Java 分段） | `CodeChunker.java` `chunkLargeText()` | 50 |
| 生成 Embedding | `EmbeddingClient.java` `embed()` | 46 |
| 混合检索 | `CodeRetriever.java` `hybridSearch()` | 50 |
| 语义检索 | `CodeRetriever.java` `semanticSearch()` | 35 |
| 关键词检索 | `VectorStore.java` `searchByKeyword()` | 195 |
| 余弦相似度 | `VectorStore.java` `cosineSimilarity()` | 303 |
| SQLite 建表 | `VectorStore.java` `initTables()` | 35 |
| SQLite 连接 | `VectorStore.java` 第 31 行 | 31 |
| 双重命中奖励 | `CodeRetriever.java` `mergeResult()` | 84 |
| 类型加分 | `CodeRetriever.java` 第 70-78 行 | 70 |
| 同文件去重 | `CodeRetriever.java` `limitPerFile()` | 133 |
| 结果格式化（LLM） | `SearchResultFormatter.java` `formatForTool()` | 41 |
| 结果格式化（CLI） | `SearchResultFormatter.java` `formatForCli()` | 21 |

---

## 面试亮点

> 搜索系统采用**双层策略**：第一层是 Agentic Search，通过 `grep_code`（优先 ripgrep，8 秒超时回退 Java 扫描）实现毫秒级精确匹配，配合 `glob_files`（文件名检索）和 `read_file`（行范围读取），覆盖变量名、函数名、关键字等确定性场景，结果受 `maxResults`/`headLimit`/`maxChars` 三层预算约束避免撑爆上下文。
>
> 第二层用 RAG 语义检索兜底，通过 `CodeIndex` 将代码按文件/类/方法三级分块（AST 解析 Java，普通文件按 2000 字符分段），`EmbeddingClient` 将其向量化后存入 SQLite。`CodeRetriever.hybridSearch()` 采用语义 + 关键词双路召回：余弦相似度语义排序 + SQLite LIKE 关键词匹配，双重命中奖励 +0.1，方法级类型加分 +0.15，同文件最多保留 2 个结果，最终排序融合后返回。索引存储于 `~/.paicli/rag/codebase.db`，支持 `EMBEDDING_PROVIDER` 切换 Ollama/OpenAI 等后端。
>
> 关键设计理念是**快路径优先**——Agent 先用 grep_code/glob_files 精确搜索（通常 <200ms），只有精确搜索无结果时才 fallback 到 RAG，避免 Embedding 调用的延迟和成本。

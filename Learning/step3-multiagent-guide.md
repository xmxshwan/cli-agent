# 第 3 步：Multi-Agent 架构 —— 整体概览

> 搭配 `learningPlan.md` 第 2.2 节食用。
>
> Multi-Agent 模块实现 Planner → Worker → Reviewer 三种角色分工协作，将复杂任务拆解为 DAG 并并行执行。

---

## 整体架构：三种执行模式的定位

PaiCLI 支持三种执行模式，共享 ToolRegistry / MemoryManager：

| 模式 | 入口 | 触发 | 好比 |
|------|------|------|------|
| **ReAct**（默认） | `agent/Agent.java` | 默认模式 | 一个人边想边干 |
| **Plan-and-Execute** | `agent/PlanExecuteAgent.java` | `/plan` 命令 | 一个人先列计划再干 |
| **Multi-Agent** | `agent/AgentOrchestrator.java` | `/team` 命令 | **一个团队**：项目经理 + 程序员 + 测试 |

---

## 核心文件清单

| 文件 | 角色 | 行数 | 优先级 |
|------|------|------|--------|
| `agent/AgentOrchestrator.java` | 总控编排器 | ~655 | ★★★ |
| `agent/SubAgent.java` | 可配置角色的轻量 Agent | ~430 | ★★★ |
| `agent/AgentRole.java` | 角色枚举定义 | 27 | ★★☆ |
| `agent/AgentMessage.java` | 角色间通信消息 | 71 | ★★☆ |
| `plan/Planner.java` | LLM 规划器 | ~280 | ★★☆ |
| `plan/ExecutionPlan.java` | DAG 数据结构 + 拓扑排序 | ~338 | ★★★ |
| `plan/Task.java` | 任务节点模型 | ~129 | ★★☆ |

---

## 团队角色

**源码：** `agent/AgentRole.java:6-26`

```java
public enum AgentRole {
    PLANNER("规划者",  "负责分析用户任务，制定执行计划，将复杂任务拆解为可执行的子任务"),
    WORKER("执行者",   "负责执行具体任务步骤，调用工具完成文件操作、命令执行等操作"),
    REVIEWER("检查者", "负责检查执行结果的质量和正确性，提供改进建议");
}
```

| 角色 | 好比 | 负责 | 能否调工具 |
|------|------|------|-----------|
| **Planner** 📋 | 项目经理 | 拆解任务、制定 DAG 计划 | ❌ 只动口 |
| **Worker** 🛠️ | 程序员 | 写代码、读文件、跑命令 | ✅ 有工具 |
| **Reviewer** 🔍 | 测试/架构师 | 审查结果、提改进意见 | ❌ 只动口 |

---

## 核心类的职责

### AgentOrchestrator —— 总控编排器

**源码：** `agent/AgentOrchestrator.java:42-654`

| 职责 | 方法 | 行号 |
|------|------|------|
| 启动协作 | `run(userInput)` | 138-217 |
| 解析 LLM 输出的 JSON 计划 | `parsePlan(planJson)` | 222-282 |
| 找当前可执行步骤 | `getExecutableSteps(steps)` | 287-298 |
| 执行单步（Worker + Reviewer + 重试） | `runStep(...)` | 481-578 |
| 批量并行执行 | `runBatchParallel(...)` | 410-474 |
| 解析审查结果 | `parseReviewApproval(content)` | 305-338 |
| 解析审查反馈 | `parseReviewIssues(content)` | 343-379 |
| 汇总最终输出 | `buildFinalResult(steps)` | 619-653 |

### SubAgent —— 轻量 Agent

**源码：** `agent/SubAgent.java:40-431`

每个 SubAgent 拥有独立的 system prompt + 对话历史，但共享 LlmClient 和 ToolRegistry。

| 方法 | 行号 | 说明 |
|------|------|------|
| `execute(task, out)` | 161-241 | **核心**：内部 ReAct 循环，LLM → 工具调用 → 继续/返回 |
| `executeWithContext(task, context)` | 250-258 | Worker 专用：注入已完成的上游步骤结果后再执行 |
| `review(task, result)` | 267-271 | Reviewer 专用：审查执行结果 |
| `clearHistory()` | 276-280 | 步骤完成后清空对话历史（保留 system prompt） |
| `shouldUseTools()` | 304-306 | 只有 WORKER 能调工具 |

### ExecutionPlan —— DAG 数据结构

**源码：** `plan/ExecutionPlan.java:8-338`

| 方法 | 行号 | 说明 |
|------|------|------|
| `computeExecutionOrder()` | 94-108 | DFS 拓扑排序 + 环检测 |
| `getExecutableTasks()` | 85-89 | 找当前批次可执行的任务（依赖全完成） |
| `getExecutionBatches()` | 266-291 | 按依赖关系分批 |
| `visualize()` | 210-238 | ASCII 框可视化 |
| `summarize()` | 243-264 | 默认折叠展示 |

### Task —— 任务节点模型

**源码：** `plan/Task.java:8-129`

```java
class Task {
    id, description, type                     // 基本信息
    status: PENDING/RUNNING/COMPLETED/FAILED/SKIPPED
    dependencies, dependents                   // DAG 的边
    result, error, start/end time              // 执行信息
    isExecutable(Map<String, Task>)            // 所有依赖都 COMPLETED？
}
```

### AgentMessage —— 通信消息

**源码：** `agent/AgentMessage.java:14-70`

```java
record AgentMessage(fromAgent, fromRole, content, type)
    type: TASK / RESULT / FEEDBACK / APPROVAL / REJECTION / ERROR
```

---

## 核心协作流程图

```
用户输入："/team 实现一个用户登录功能"
    │
    ▼
AgentOrchestrator.run()
    │
    ├── 阶段 1：规划
    │   │
    │   ├── Planner.execute(task)           → SubAgent.java:161
    │   │   └── LLM 输出 JSON 格式 DAG 计划
    │   │
    │   └── AgentOrchestrator.parsePlan(json) → AgentOrchestrator.java:222
    │       └── 解析为 List<ExecutionStep>
    │
    ├── 阶段 2：按 DAG 批次执行
    │   │
    │   ├── getExecutableSteps() → 找当前批次 → AgentOrchestrator.java:287
    │   │
    │   ├── 单步（第 189-196 行）→ 串行 runStep()
    │   │   │
    │   │   └── runStep() → AgentOrchestrator.java:481
    │   │       ├── Worker.executeWithContext() → SubAgent.java:250
    │   │       ├── Reviewer.review()          → SubAgent.java:267
    │   │       └── 未通过? → 重试循环（最多 2 次）→ AgentOrchestrator.java:535
    │   │
    │   ├── 多步并行（第 198-201 行）→ runBatchParallel()
    │   │   │                            → AgentOrchestrator.java:410
    │   │   └── 线程池 + Worker 池 + 独立 ByteArrayOutputStream
    │   │       完成后按 step_id 顺序 flush
    │   │
    │   └── 失败步骤的下游自动跳过的隐式处理（第 206-209 行）
    │
    └── 阶段 3：汇总
        │
        └── buildFinalResult(steps) → AgentOrchestrator.java:619
            └── 输出步骤状态 + 简短预览
```

---

## 并行策略

| 批次大小 | 执行方式 | 源码位置 | 输出方式 |
|---------|---------|---------|---------|
| **单步** | 直接串行调用 `runStep()` | `AgentOrchestrator.java:189-196` | 流式输出到 stdout，实时打字效果 |
| **多步** | 线程池 + Worker 池并行 | `AgentOrchestrator.java:410-474` | 每步 ByteArrayOutputStream，批次结束按 step_id 顺序 flush |

并行核心机制（`AgentOrchestrator.java:412-473`）：

```
1. 线程池大小 = min(批次大小, Worker 池大小)
2. WorkerPool = BlockingQueue → 池化分配，同一 Worker 不会被两个步骤并发占用
3. 每个步骤创建独立的 Reviewer 实例 → 避免对话历史竞争
4. 每步写入自己的 ByteArrayOutputStream → 避免多线程写 stdout 交错
5. 所有步骤完成后按 step_id 顺序 flush → 用户看到稳定的执行顺序
```

---

## 审查 + 重试机制

```
Worker 执行完毕
    │
    ▼
Reviewer 审查: {"approved": true/false, "issues": [...], "summary": "..."}
    │              解析: AgentOrchestrator.java:305
    │              保守策略: 解析失败则默认拒绝 (第 306 行)
    │
    ├── approved = true   → ✅ 通过 (第 526-529 行)
    │
    └── approved = false  → ❌ 未通过
            │
            ▼
        重试循环 (第 535-570 行，MAX_RETRIES_PER_STEP = 2)
            │
            ├── Worker 带反馈重试 (第 541-542 行):
            │   注入 context: "之前的执行结果被审查拒绝，原因：\n" + issues
            │
            ├── Reviewer 再次审查 (第 558 行)
            │
            ├── 通过？ → ✅ 完成 (第 573-574 行)
            │
            └── 超过 2 次？ → ⚠️ 保留当前结果 (第 576 行)
                （不阻塞后续，下游会因依赖失败被标记 SKIP）
```

---

## 与 Plan-and-Execute 的区别

| 维度 | Plan-and-Execute (`/plan`) | Multi-Agent (`/team`) |
|------|---------------------------|----------------------|
| **入口** | `PlanExecuteAgent.java` | `AgentOrchestrator.java` |
| **Planner 实现** | `Planner.java` (独立的 LLM 调用) | SubAgent(PLANNER) (共享 LlmClient) |
| **执行者** | 同一个 Agent 串行执行 | Worker 池并行 + Reviewer 即时审查 |
| **审查** | 无 | Reviewer 每步审查 + 重试 |
| **适用场景** | 需要规划但步骤简单 | 复杂多步协作，质量要求高 |

---

## 自检清单

读完本文件后，你应该能回答：

| 问题 | 答案速查 |
|------|---------|
| Multi-Agent 有三种角色？ | PLANNER / WORKER / REVIEWER |
| 哪个角色能调工具？ | WORKER（`SubAgent.java:304-306`） |
| Planner 输出的计划是什么格式？ | JSON，含 `steps` 数组（id / description / dependencies） |
| 怎么决定哪些步骤可以同时执行？ | `getExecutableSteps()` 检查所有依赖是否 COMPLETED |
| 多步并行时如何避免输出交错？ | 每步写独立的 ByteArrayOutputStream，批次结束按 step_id 顺序 flush |
| Worker 执行完谁审查？ | `runStep()` 第 512 行调用 `reviewer.review()` |
| 审查不通过最多重试几次？ | `MAX_RETRIES_PER_STEP = 2`（第 45 行） |
| 审查解析失败怎么办？ | 保守策略：默认拒绝（第 306-308 行） |
| 某个步骤失败下游怎么办？ | 依赖检查不通过 → 状态保持 PENDING → 最终被跳过提示（第 206-209 行） |

---

## 后续深入

| 子主题 | 对应源码 | 建议 |
|--------|---------|------|
| SubAgent 内部 ReAct 循环 | `SubAgent.java:161-241` | 理解怎么实现角色化 LLM 调用 |
| Planner 规划提示词 | `Planner.java:42-64` | 看怎么让 LLM 输出结构化 DAG |
| 并发安全细节 | `AgentOrchestrator.java:410-474` | Worker 池 + 独立缓冲区 |
| DAG 拓扑排序算法 | `ExecutionPlan.java:94-135` | DFS + 环检测 |
| 审查结果解析策略 | `AgentOrchestrator.java:305-379` | JSON 解析 + 关键词兜底 |

# Argus → AIOps 告警诊断平台 改造方案

> 把现有 Argus(GitHub PR 智能代码审查)改造为**面向微服务运维的告警智能诊断平台**。
> 本目录是**方案文档**,供执行 Agent 按图施工。代码不在此处实现。

## 文档导航

| 文件 | 内容 |
|---|---|
| `README.md`(本文) | 总览、决策记录、简历话术 |
| `01-spec-review.md` | 原始 TECH_SPEC 体检报告(Linus 式),哪些抄、哪些砍、哪些会编译崩 |
| `02-architecture.md` | 目标架构、修正后的数据模型与接口定义(消除原 spec 矛盾) |
| `03-implementation-plan.md` | 分阶段可执行任务清单,精确到类名/方法签名,给执行 Agent 用 |
| `04-value-and-paths.md` | 价值主张(vs 直接用 Claude)、分级自治、五道安全闸、memory 闭环、发展路径 |
| `05-frontend-and-deployment.md` | Web 观测台设计、部署形态、Mac 如何访问服务器 |

## 一句话目标

```
外部告警 → HTTP 接入 → Redis 滑动窗口收敛 → SupervisorAgent 并行调度
3 个垂直 Agent(Log/Metric/Trace,Mock)→ RAG 检索历史故障案例
→ SSE 流式推送诊断报告 → MongoDB 持久化诊断记录
```

核心三链路:**多 Agent 协同诊断 · RAG 知识库 · SSE 流式输出**。其余都是配角。

> **技术栈锁定**:Java 版 `springVersion/`(Spring Boot 3 + WebFlux + LangChain4j)+ Vue3 前端。
> Python 版 `argus/`(根目录)本次改造**完全不动**。

> **三条差异化线(详见 `04`,已排进 `03` 的 Phase)**:
> - **C 统一基础设施**(Phase 1.5):旧 review Agent 与新诊断 Agent 共享 LLM/SSE/RAG/Scheduler,叙事从"告警 bot"升级为"多 Agent 应用平台"
> - **B 自愈状态机 + 分级自治**(Phase 7):按爆炸半径分级处置 + 五道安全闸;**改代码永远 PR+CI 不自动执行**
> - **Memory 闭环**(Phase 8,差异化最高):成功处置固化为经验 → 驱动审批门自动提权,系统越用越自治
>
> **交付节奏**:Phase 0~6 = 可独立演示的诊断 MVP(~5.5 天);7~9 = 差异化升级(总~11.5 天)。时间紧优先 Phase 8。

## 关键决策记录(已拍板)

| 决策 | 选择 | 理由 |
|---|---|---|
| LangChain4j 版本 | **升级到 1.x** | 用 `@Tool`/`AiServices` 现代 API |
| LLM 客户端 | **能删 PackyCode 就删** | Phase 0 先验证官方 `langchain4j-open-ai` 1.x 能否直连 DeepSeek(OpenAI 兼容协议,大概率可);能则删自定义客户端,少维护一坨;不能才退回适配 `ChatModel` |
| 旧 review 代码 | **保留,另开子包** | 新建 `com.argus.review.aiops.*`,旧 `com.argus.review` 业务不删;C 路径把它从负担变资产 |
| RabbitMQ | **砍掉** | HTTP POST 即可接入告警,MQ 是臆想的吞吐需求 |
| Redis 收敛 | **保留** | `SET NX EX` 一行实现,配置极简,简历价值高(分布式收敛);接口隔离,无 Redis 可降级内存 |
| MongoDB | **保留** | 原项目无持久层,诊断记录持久化是真需求 |
| Milvus | **保留** | dev 用 InMemory,prod 用 Milvus(沿用现有 `@Profile` 设计) |
| RAG 检索 | **纯向量,不上 BM25/Rerank** | demo 仅 3-5 篇文档,纯向量召回足够;混合检索是论文级过度设计 |
| Embedding | **复用现有本地 384 维 ONNX BGE** | 零远程依赖;不换 1024 维 BGE-M3(要 API key 或大模型) |
| Agent 调度 | **确定性并行,不用 LLM 当调度器** | 沿用现有 `OrchestratorAgent` 思路,可测、可控 |
| 自愈处置 | **分级自治,改代码走 PR+CI** | 自动改生产代码风险不对称;90% 故障靠重启/回滚/扩容/限流解决 |
| 前端 | **Vue3 单页,打包进 jar** | 观测台是展示层,公网 IP 直连演示;不前后端分离部署 |

## 可直接复用的现有资产(别重写)

| 现有类 | 新场景用途 |
|---|---|
| `infrastructure/config/LangChain4jConfig` | 官方 OpenAI 兼容模型配置,Phase 0 已改为 `OpenAiChatModel` / `OpenAiStreamingChatModel` |
| `domain/rag/RetrievalService` | RAG 检索,改 prompt + 返回结构化 chunk |
| `domain/rag/DocumentIngestionService` | 运维文档向量化入库 |
| `interfaces/web/ReviewController`(SSE 段) | `DiagnosisController` 的 SSE 写法模板 |
| `domain/agent/SecurityAgent` 的 `@AiService` 声明式风格 | Supervisor 的根因/修复 LLM 调用模板 |
| `domain/agent/OrchestratorAgent` 确定性路由 | `SupervisorAgent` 调度逻辑参考 |

## 你的简历能写什么(诚实版)

按本精简方案落地后,以下都是**真做了、能演示、能答辩**的点:

**项目级描述**
> 基于 Java 21 + Spring Boot 3 WebFlux + LangChain4j 构建微服务 AIOps 告警诊断平台,实现"告警接入→收敛→多 Agent 根因诊断→RAG 案例检索→流式报告"全链路。

**可写的技术亮点**
- **多 Agent 协同**:Supervisor 模式编排 Log/Metric/Trace 三个垂直诊断 Agent,基于 Reactor `Flux.merge` 并行调度,汇总根因。
- **RAG 运维知识库**:运维文档语义切分 + 本地 ONNX Embedding 向量化,Milvus 存储,向量检索 Top-K 历史故障案例辅助根因定位。
- **SSE 流式诊断**:WebFlux `Flux<ServerSentEvent>` 逐阶段推送诊断过程(start/tool_result/knowledge/root_cause/fix/done),前端实时渲染。
- **告警收敛去重**:Redis 滑动窗口(`SET NX EX`)按 `服务名:告警名` 维度抑制重复告警,降低告警风暴。
- **Java 21 虚拟线程**:阻塞型外部查询(日志/指标系统)跑在 Virtual Threads,响应式主链路不被阻塞。
- **诊断记录持久化**:MongoDB 存储完整诊断上下文(告警/工具结果/根因/修复建议),支持人工复核标记。
- **LangChain4j Tool Calling**:`@Tool` 声明 Agent 能力,`AiServices` 声明式编排 LLM 调用。

**❌ 不要写的(没真做,会被问穿)**
- RabbitMQ 高吞吐告警接入(砍了)
- BM25 + 向量混合检索 / bge-reranker 精排(没做)
- 真实接入 Prometheus/ELK/SkyWalking(全是 Mock)
- 多副本/集群/高可用(单机 demo)

> 答辩口径:外部系统用 Mock 是**刻意的工程取舍**——先保证诊断链路与 Agent 编排正确,接口已隔离,接真实数据源只换实现不动业务。这是加分项,不是减分项。

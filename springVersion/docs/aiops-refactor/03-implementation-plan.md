# 03 · 实施计划(执行 Agent 用)

> 按阶段顺序执行。每阶段有**完成判据**,做不到不进下一阶段。
> 红线见 `01-spec-review.md §5`。模型/接口契约见 `02-architecture.md`。价值与设计依据见 `04-value-and-paths.md`。
> 技术栈锁定:**Java 版 `springVersion/` + Vue3 前端**。Python 版 `argus/` 不动。
>
> **本计划已纳入 `04` 推荐的 C + B + Memory 三条线:**
> - **C 统一基础设施**:旧代码审查 Agent 与新诊断 Agent 共享 LLM/SSE/RAG/Agent 接口/虚拟线程(Phase 1.5)
> - **B 自愈状态机 + 分级自治**:诊断后按爆炸半径分级处置,五道安全闸(Phase 7)
> - **Memory 闭环**:成功处置固化为经验,驱动审批门自动提权(Phase 8)
>
> MVP 边界:Phase 0~6 是"能跑的诊断平台"(可独立交付/演示)。Phase 7~9 是差异化升级,按时间投入选做。

---

## Phase 0 · LangChain4j 1.x 升级(先做,否则全卡)

**目标:让现有 `com.argus.review` 项目在 LC4j 1.x 下编译 + 测试通过。** 独立提交,与新功能分开。

**策略(按用户决策):直接升级,让它崩,看崩在哪再决定——优先"删"而非"改"。**

任务:
- [x] `pom.xml`:`langchain4j.version` → `1.16.3`;同步 `langchain4j-bom`、`-open-ai`、`-milvus`、`-spring-boot-starter` 坐标
- [x] `mvn compile`,**先看崩在哪**
- [x] **优先方案 —— 删掉自定义客户端**:官方 `langchain4j-open-ai` 1.x 可替代自定义协议层。
      - 已删除 `PackyCodeChatModel` / `PackyCodeStreamingChatModel` / `OpenAiProtocolSupport`
      - `LangChain4jConfig` 已改用 `OpenAiChatModel.builder()` / `OpenAiStreamingChatModel.builder()`,`base-url=https://api.deepseek.com`
- [x] 适配 `RetrievalService`:`findRelevant` → `embeddingStore.search(EmbeddingSearchRequest)`
- [x] 校验所有 `@AiService` / `@SystemMessage` / `@V` import 路径
- [x] `mvn test` 通过

**完成判据:`mvn test` 绿,旧 review 功能不回归,DeepSeek 实际能出一次响应(跑 `verify-llm.sh` 或等价验证)。**

---

## Phase 1 · 模型 + 中间件依赖(0.5 天)

任务:
- [x] `pom.xml` 加:`spring-boot-starter-data-mongodb-reactive`、`spring-boot-starter-data-redis-reactive`
- [x] **不加** RabbitMQ
- [x] 新建 `com.argus.review.aiops.model` 包,落 `02-architecture §3` 全部模型:`AlertEvent` `DiagnosticContext` `ToolResult` `KnowledgeChunk` `DiagnosisEvent`
- [x] 新建 Mongo 记录模型与仓库:`AlertRecord` / `AlertRecordRepository` / `DiagnosisRecord` / `DiagnosisRecordRepository`
- [x] `application.yml` 加 redis/mongo 配置(localhost 默认值),Milvus 仍 `@Profile("prod")`

> Redis 只用于 `ReactiveStringRedisTemplate` 做收敛 key,不定义 Redis Repository。启动日志里 Redis repository 扫描跳过 Mongo 仓库是正常噪音。

**完成判据:编译通过,模型字段与契约一致(`DiagnosticContext` 有 `AlertEvent` 构造器)。**

---

## Phase 1.5 · 统一基础设施抽象(C,0.5 天)

> 把"旧 review Agent + 新诊断 Agent"共享的东西抽到中性包。**这是 C 路径的全部实质——共享基础设施层,不共享业务层。** 别过度抽象成"通用 Agent 大脑"。

任务:
- [x] **降级决策**:不新建 `shared.Agent` 抽象。旧 review Agent 和 diagnosis Agent 的业务契约不同,强抽只会制造假通用层。
- [ ] 新建 `com.argus.review.aiops.shared` 包,收口共享基础设施:
      - LLM:`ChatModel` / `StreamingChatModel` Bean(Phase 0 产物,review 和 diagnosis 共用)
      - RAG:`EmbeddingModel` / `EmbeddingStore` / 检索基类(`RetrievalService` 上提为通用)
      - Agent 接口:抽象出中性 `Agent` 契约(`CodeReviewAgent` 与 `DiagnosisAgent` 各自继承)—— **仅在两者确有共性时才抽,没共性就别硬抽**
      - 虚拟线程承载阻塞调用的统一 `Scheduler`/工具方法
- [x] `LangChain4jConfig` 暂不移动,但 review 与 diagnosis 已共享同一组 `ChatModel` / `StreamingChatModel` Bean
- [x] **不动** `com.argus.review` 的业务逻辑,只新增 aiops 侧依赖

**完成判据:review 与未来 diagnosis 引用同一套 LLM/RAG/Scheduler Bean;`mvn test` 绿,review 不回归。**

> ⚠️ 若评估下来两者共性太弱(强抽=过度设计),**可降级**:只共享 LLM/RAG/Scheduler 这些确定通用的,Agent 接口不强抽。在本 Phase 文档里记录决策即可。

---

## Phase 2 · 告警接入 + 收敛(1 天)

任务:
- [x] `AlertConverter`:Prometheus webhook JSON → `AlertEvent`,生成 `alertId`。钉钉格式留 TODO
- [x] `AlertController`:`POST /api/alerts`,标准化 → 收敛判断 → 存 Mongo → 返回 `{alertId, suppressed}`
- [x] `ConvergenceService` 接口 + `RedisConvergenceService`:`SET key value NX EX 300`,key 见契约;`release()` 删 key
- [x] 测试接口模拟 Prometheus 推送

验证:
- `GET /actuator/health`:Mongo/Redis `UP`
- 连续两次 `POST /api/alerts`:第一次 `suppressed=false`,第二次 `suppressed=true`

**完成判据(spec §9-1):同一 `service:alertName` 5 分钟内重复 POST,第 2 次起返回 `suppressed=true`。**

---

## Phase 3 · 多 Agent 骨架(2 天,核心)

任务:
- [x] `DiagnosisAgent` 接口(见契约 §4,继承 `shared` 的 `Agent` 若 Phase 1.5 抽了)
- [x] `LogQueryAgent`:Mock —— 读本地 `logs/app.log` 或返回固定 ERROR 日志样本;`@Tool` 声明 `queryRecentLogs(serviceName, minutes)`
- [x] `MetricQueryAgent`:Mock —— 返回固定 CPU/内存 JSON;按 `alertName` switch;**查询串参数化,不拼接**
- [x] `TraceQueryAgent`:Mock —— 返回固定慢调用链路样本
- [x] 每个 Agent `execute()` 真实测量 `latencyMs`(`System.nanoTime()` 差),跑虚拟线程/`boundedElastic`
- [x] **Tool calling 可靠性兜底(技术点,见 `04` §六-1)**:Agent 工具调用失败时返回 `ToolResult(success=false)` 而非抛异常中断;参数白名单校验
- [x] `RootCauseAnalyzer`:`@AiService` 声明式(照 `SecurityAgent.java`),两方法 `analyze` / `suggestFix`,系统 prompt 用 spec §6.5 SRE 专家版
- [x] `SupervisorAgent implements DiagnosisEngine`:确定性并行调度(骨架见 `02-architecture §6`)
- [x] 单测覆盖并行汇总 + 单 Agent 失败不拖垮整体

**完成判据(spec §9-4):一个告警触发 3 Agent 并行执行,结果汇总进 `DiagnosticContext`;单测覆盖调度顺序 + 单 Agent 失败不拖垮整体。**

---

## Phase 4 · RAG 集成(0.5 天)

任务:
- [ ] 准备 3-5 篇 Markdown 运维文档:JVM OOM、数据库慢查询、Redis 连接池耗尽(放 `docs/aiops-knowledge/`)
- [ ] **结构化切分(技术点,见 `04` §六-6)**:按"故障现象/排查步骤/根因/修复方案"语义块切,非傻按 token 切
- [ ] `DocumentIngestor`:包装现有 `DocumentIngestionService`,启动预加载入向量库
- [ ] `KnowledgeSearchService`:包装现有 `RetrievalService`,返回 `List<KnowledgeChunk>`(纯向量 Top-3,不上 BM25/Rerank)
- [ ] 接入 `SupervisorAgent` 的 knowledge 阶段

**完成判据(spec §9-3):查 "JVM OOM" 能召回对应文档。**

---

## Phase 5 · SSE 流式输出(1 天)

任务:
- [x] `DiagnosisController`:`GET /api/diagnosis/stream?alertId=xxx`,`produces=TEXT_EVENT_STREAM_VALUE`,返回 `Flux<ServerSentEvent<String>>`;从缓存/Mongo 取 `AlertEvent` 喂 `engine.diagnose()`
- [x] `DiagnosisEvent.type` → SSE `event:` 字段(start/tool_result/knowledge/root_cause/fix/done/error)
- [ ] 参考 `ReviewController` 的 SSE + `onErrorResume` 写法
- [ ] **流式错误恢复(技术点,见 `04` §六-7)**:LLM 中途超时 → emit `error` 事件优雅降级,不让前端连接挂死

**完成判据(spec §9-2):浏览器 Network 面板见 6-8 个 SSE event,逐阶段到达。**

---

## Phase 6 · 持久化 + 收尾(1 天)—— MVP 完成线

任务:
- [ ] `DiagnosisRecordRepository`(Reactive Mongo),`SupervisorAgent` done 前 save
- [ ] 单测:收敛逻辑、Agent 调度、RAG 检索三块
- [ ] README:启动方式(docker-compose 起 redis/mongo,Milvus 可选)、测试 curl 用例
- [ ] 清理临时文件

**完成判据(spec §9-5):一次诊断后 Mongo 见完整 `DiagnosisRecord`。**

> ✅ **到此为止 = 可独立交付的诊断平台 MVP。** 下面 7~9 是差异化升级,按时间选做。

---

## Phase 7 · 自愈状态机 + 分级自治(B,2 天)

> 设计依据见 `04` §二(分级表)、§三(五道闸)。**红线:改代码永远走 PR+CI,不自动执行。**

任务:
- [ ] `RemediationAction` 模型:动作类型 + 爆炸半径等级 + 目标服务 + 置信度
- [ ] 动作白名单(枚举):`RESTART` / `SCALE` / `ROLLBACK` / `RATE_LIMIT` / `CLEAR_CACHE` / `CODE_FIX`(后者只生成 PR)
- [ ] 自治状态机:`PROPOSED → APPROVED → EXECUTING → VERIFIED / ROLLED_BACK`(+ `REJECTED`)
- [ ] **分级策略门**:按 `04` §二表,只读/低危自动执行,`CODE_FIX` 走人工
- [ ] **五道安全闸**(`04` §三):①置信度门控 ②动作白名单+幂等 ③熔断器(同服务 5min ≥N 次停手报人)④金丝雀+自动回滚(执行后盯指标,未恢复则回滚)⑤全审计(每动作留痕:Agent/根因/置信度/结果)
- [ ] 执行端 **Mock**(打印/记录"已重启 xxx"),接口隔离,真实执行后续替换
- [ ] `CODE_FIX` 路径:复用旧 `FixAgent` 经验生成 unified diff → 提 PR(或先记录待提)

**完成判据:低危告警自动走完 `PROPOSED→VERIFIED`(Mock 执行);`CODE_FIX` 停在 `PROPOSED` 等人工;熔断器在连续失败时触发并报人;全程审计可查。**

---

## Phase 8 · Memory 闭环(Memory,2 天)—— 最大差异化

> 设计依据见 `04` §四。RAG=被动检索静态知识,Memory=主动积累动态经验。

任务:
- [ ] **短期记忆**:`DiagnosticContext` 工具结果累积(已有,确认完整传给 LLM)
- [ ] **长期-案例库自增长**:成功处置(Phase 7 `VERIFIED`)或人工确认的诊断 → 提炼成 RAG 文档写回向量库(只写确认的,防错误经验污染)
- [ ] **长期-实体记忆**:按 `serviceName` 存历史故障模式 + 基线指标(Mongo/Redis),诊断时带服务画像入 prompt
- [ ] **检索策略**:语义相似 + 实体匹配(serviceName)+ 时间衰减(旧模式降权)
- [ ] **自治提权(闭环高潮,接 Phase 7)**:同故障模式成功处置 ≥N 次且成功率高 → 自动降低该模式审批门等级(低危直接执行);新模式保持谨慎
- [ ] **遗忘**:故障模式过期/降权策略

**完成判据:同一服务同类告警第二次诊断时,prompt 含历史画像;某模式积累足够成功记录后,其审批门自动从"人工"降为"自动",有日志证明提权发生。**

---

## Phase 9 · Web 观测台(Vue3,1 天)

> 设计依据见 `05-frontend-and-deployment.md`。定位:大屏 + 演示 + 审批入口,**不是客户端 App**。

任务:
- [ ] Vue3 单页,放 `src/main/resources/static/`,**随 jar 打包**,不另起 Node 服务。开发期 `npm run dev` + vite proxy 转 `/api` 到 8080;发布期 `npm run build` 产物入 `static/`(或 `frontend-maven-plugin` 自动化)
- [ ] 告警列表:拉取 `GET /api/alerts`,展示收敛后告警
- [ ] **诊断流面板**:点告警 → `EventSource` 接 `/api/diagnosis/stream` → 按 event 类型逐阶段渲染
- [ ] 自治面板:动作的自治级别 + 置信度(依赖 Phase 7)
- [ ] 待审批区:高危动作(`CODE_FIX`)列表 + 批准/拒绝(依赖 Phase 7)
- [ ] 案例库视图:memory 沉淀的历史案例(依赖 Phase 8)
- [ ] `server.address: 0.0.0.0` 确认;加最小 token 校验防公网裸奔(见 `05` §六)

**完成判据:Mac 浏览器开 `http://<服务器IP>:8080`(公网 IP 直连,见 `05` §五)见告警列表,点击实时流式渲染诊断过程。**

> 注:自治/审批/案例库三块依赖 Phase 7、8。若只做到 MVP(Phase 6),Phase 9 仅实现告警列表 + 诊断流。

---

## 总验收
| # | 判据 | 来源 |
|---|---|---|
| 1 | 重复告警 5 分钟抑制 | spec §9-1 |
| 2 | SSE 6-8 event 流式 | spec §9-2 |
| 3 | RAG 召回 JVM OOM 文档 | spec §9-3 |
| 4 | 3 Agent 并行汇总 | spec §9-4 |
| 5 | Mongo 完整诊断记录 | spec §9-5 |
| 6 | 低危动作自动处置 + 熔断 + 审计 | Phase 7 |
| 7 | memory 提权:模式经验降低审批门 | Phase 8 |
| 8 | 浏览器观测台实时渲染诊断流 | Phase 9 |

## 工期
- spec 原计划:7 天(RabbitMQ + 混合检索 + Rerank,无前端,无自愈,无 memory)
- 本方案 MVP(Phase 0~6):**约 5.5 天**
- 本方案完整(含 C+B+Memory+前端,Phase 0~9):**约 11.5 天**
- 建议:先交付 MVP 跑通演示,再按面试时间投入加 7/8/9。**Phase 8(memory)差异化最高,时间紧时优先于 Phase 7。**

## 给执行 Agent 的纪律
- Phase 0 不过,不许碰后续
- 每个 Phase 完成判据不满足,不进下一阶段
- 新代码进 `com.argus.review.aiops.*`,旧 `com.argus.review` 业务不删(Phase 1.5 仅调整其依赖指向 `shared`)
- 外部系统全 Mock,但接口隔离 + 查询参数化,Mock 实现也不拼查询串
- **改代码类自愈永远走 PR+CI,严禁自动执行(安全红线)**
- 改完跑 `mvn test`,别口头声称通过

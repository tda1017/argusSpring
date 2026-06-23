# 01 · 原始 TECH_SPEC 体检报告

> 对 `~/Downloads/TECH_SPEC.md` 的逐条审查。结论:方向对,满身赘肉,几处会编译崩。
> **品味评分:🟡 凑合偏 🔴**

## 一、spec 与现实对账(spec 第 1 行就不准)

spec 称"保留全部现有技术栈 RabbitMQ + Milvus + WebFlux + SSE + RabbitMQ + Redis + MongoDB"。
实际 `pom.xml` 真相:

| spec 声称"保留" | 真实情况 |
|---|---|
| RabbitMQ | ❌ 无依赖 |
| Redis | ❌ 无依赖 |
| MongoDB | ❌ 无依赖 |
| Milvus | ✅ 有,但仅 `@Profile("prod")` 启用,默认走 InMemory |
| WebFlux | ✅ 有 |
| LangChain4j | ✅ 0.36.2,且用 `@AiService` 声明式,**非** spec 写的 `@Tool` 风格 |

**结论:这不是"保留技术栈改造",是新建 3 个中间件 + 重写业务层。** 按本方案砍 RabbitMQ,保留 Redis/Mongo/Milvus。

## 二、🔴 致命问题(照抄必崩,执行 Agent 必看)

### 2.1 LangChain4j API 版本错乱(spec §6.3)
spec 写:
```java
@Tool("查询...")
public String queryRecentLogs(@ToolParam(description="服务名") String serviceName, ...)
```
`@ToolParam(description=...)` 是 **LangChain4j 1.x** 注解。现项目是 0.36.2,且现有 Agent 用的是 `@AiService` + `@SystemMessage` + `@UserMessage` + `@V` 声明式接口风格(见 `SecurityAgent.java`)。

**决策:升级到 LangChain4j 1.x。** 但隐藏代价 spec 完全没提:
- 自定义 `PackyCodeChatModel`(实现 `dev.langchain4j.model.chat.ChatLanguageModel`)在 1.x 中接口改为 `ChatModel` + `ChatRequest`/`ChatResponse`,**必须重写适配,否则全项目编译失败**。
- `StreamingChatLanguageModel` → `StreamingChatModel`,回调签名变更。
- `embeddingStore.findRelevant(...)` 在 1.x 改为 `search(EmbeddingSearchRequest)`,`RetrievalService` 需改。
- 这是升级的真实工作量,执行 Agent 第一步就要处理,否则后面全卡。

### 2.2 数据模型自相矛盾(spec §4.2)
```java
public class DiagnosticContext {
    private AlertEvent alert;    // 字段声明,无构造器
}
// 但 §6.3 用:  new DiagnosticContext(alert)   ← 带参构造,矛盾
```
record / class、构造器、getter/setter 全靠脑补。`ToolResult.latencyMs` 在所有示例里恒传 `0` —— **死字段**。
→ 修正后的模型见 `02-architecture.md §3`。

### 2.3 PromQL / 查询串拼接注入(spec §6.2 MetricQueryAgent)
```java
queryMetric("...node_cpu_seconds_total{instance=\"" + instance + "\"}...")
```
`instance` 来自告警 label,直接拼进查询串。Mock 阶段无害,真接 Prometheus 即注入点。
→ 方案:Mock 实现里也用参数化/白名单,别养成拼串习惯。

## 三、🟡 过度设计(臆想需求,本方案已砍/降级)

| spec 项 | 问题 | 本方案处理 |
|---|---|---|
| RabbitMQ 接入(§3,§8.5) | HTTP POST 就够,MQ 是臆想吞吐 | **砍** |
| Redis 滑动窗口(§6.1) | 本身合理,但要接口隔离防绑死 | **留**,`ConvergenceService` 接口 + Redis 实现,可降级内存 |
| 混合检索 BM25+Rerank(§6.2.3) | demo 仅 3-5 篇文档,纯向量足够 | **降级**为纯向量检索 |
| BGE-M3 1024 维(§6.2.2) | 要 API key 或本地大模型 | **降级**,复用现有 384 维本地 ONNX |
| LLM 当 Supervisor 调度(§6.3) | 不可测黑箱 | **改**确定性并行调度 |

## 四、✅ 方向正确(值得做)

- 多 Agent Supervisor + 并行调度 —— 复用现有 6-Agent 编排经验
- SSE 流式输出 —— 现有 `ReviewController` + `PackyCodeStreamingChatModel` 直接迁移,项目最大资产
- RAG 检索历史案例 —— `RetrievalService` 改 prompt 即用
- 数据主链路(接入→收敛→Agent→RAG→报告→存储)—— 清晰

## 五、给执行 Agent 的红线

1. **第一件事**:升级 LangChain4j 到 1.x 并让现有项目编译通过(适配 `PackyCodeChatModel` 等),再写新功能。
2. 新代码进 `com.aioops.alert`,**不删** `com.argus.review`。
3. 不引入 RabbitMQ。Redis/Mongo/Milvus 按需引入,Milvus 仍 `@Profile("prod")`。
4. 所有外部系统(Prometheus/ELK/SkyWalking)Mock,但接口隔离,Mock 实现也不拼查询串。
5. 数据模型以 `02-architecture.md` 为准,不照抄 spec §4 的矛盾版本。

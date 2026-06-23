# 02 · 目标架构与数据模型(修正版)

> 修正 spec §4 的自相矛盾,给执行 Agent 一份可直接落地的契约。

## 1. 包结构(新建,不动旧代码)

```
com.argus.review.aiops
├── ingestion
│   ├── AlertController          // HTTP 接告警(替代 RabbitMQ),POST /api/alerts
│   └── AlertConverter           // 多源标准化(Prometheus/钉钉),Mock 起步
├── convergence
│   ├── ConvergenceService       // 接口:shouldSuppress / release
│   └── RedisConvergenceService  // Redis SET NX EX 实现(无 Redis 时可换内存实现)
├── knowledge
│   ├── DocumentIngestor         // 复用/包装现有 DocumentIngestionService
│   └── KnowledgeSearchService   // 复用/包装现有 RetrievalService,返回结构化 chunk
├── diagnosis
│   ├── DiagnosisEngine          // 接口:Flux<DiagnosisEvent> diagnose(AlertEvent)
│   ├── SupervisorAgent          // 实现 DiagnosisEngine,确定性并行调度
│   ├── DiagnosisAgent           // 工具 Agent 接口
│   ├── LogQueryAgent            // Mock 日志查询
│   ├── MetricQueryAgent         // Mock 指标查询
│   ├── TraceQueryAgent          // Mock 链路查询
│   └── RootCauseAnalyzer        // @AiService,LLM 根因+修复(复用现有声明式风格)
├── stream
│   └── DiagnosisController      // SSE 接口,GET /api/diagnosis/stream
├── persistence
│   ├── AlertRecord              // @Document MongoDB,按 alertId 保存待诊断告警
│   ├── AlertRecordRepository    // Spring Data Mongo,GET stream 按 alertId 查
│   ├── DiagnosisRecord          // @Document MongoDB
│   └── DiagnosisRecordRepository// Spring Data Mongo
└── model
    ├── AlertEvent               // record
    ├── DiagnosticContext        // 可变 class
    ├── ToolResult               // record
    └── DiagnosisEvent           // record,SSE 推送单元
```

> 新包必须放在 `com.argus.review` 子包下,否则 `ArgusReviewApplication` 默认组件扫描不到。
> 如果坚持用 `com.aioops.*`,启动类必须显式加 `@SpringBootApplication(scanBasePackages = {"com.argus.review", "com.aioops"})`。

## 2. 数据流

```
POST /api/alerts (AlertConverter 标准化)
   → ConvergenceService.shouldSuppress?  ── 是 → 丢弃,返回 suppressed
   → 否,AlertRecordRepository.save(alert),返回 alertId
GET /api/diagnosis/stream?alertId=xxx (SSE)
   → AlertRecordRepository.findByAlertId(alertId),不存在则 emit error
   → SupervisorAgent.diagnose(alert):
       emit start
       → Flux.merge(LogAgent, MetricAgent, TraceAgent) 并行   每个完成 emit tool_result
       → KnowledgeSearchService.search(描述)                  emit knowledge
       → RootCauseAnalyzer.analyze(ctx)                        emit root_cause
       → RootCauseAnalyzer.suggestFix(ctx)                     emit fix
       → DiagnosisRecordRepository.save(ctx)                   emit done
```

## 3. 数据模型(修正后,以此为准)

### 3.1 AlertEvent(record,沿用 spec §4.1,OK)
```java
public record AlertEvent(
    String alertId,                 // UUID,接入时生成
    String source,                  // "prometheus" / "dingtalk"
    String serviceName,
    String alertName,
    String severity,                // "warning" / "critical"
    String description,
    Map<String,String> labels,
    long firedAt                    // epoch millis
) {}
```

### 3.2 DiagnosticContext(可变 class,修正 spec §4.2 的构造器矛盾)
```java
public class DiagnosticContext {
    private final String diagnosisId;          // UUID,构造时生成
    private final AlertEvent alert;             // 构造入参
    private final List<ToolResult> toolResults = new ArrayList<>();
    private final List<KnowledgeChunk> retrievedKnowledge = new ArrayList<>();
    private String rootCause;
    private String fixSuggestion;

    public DiagnosticContext(AlertEvent alert) {   // ← spec §6.3 需要的构造器
        this.alert = alert;
        this.diagnosisId = UUID.randomUUID().toString();
    }
    // getter + addToolResult / addKnowledge / setRootCause / setFixSuggestion
}
```

### 3.3 ToolResult(record,去掉死字段或真填)
```java
public record ToolResult(
    String agentName,
    boolean success,
    String output,
    long latencyMs          // 真实测量:System.nanoTime() 差值,不再恒为 0
) {}
```

### 3.4 KnowledgeChunk(替代 spec 模糊的 RetrievedChunk)
```java
public record KnowledgeChunk(String content, double score, String source) {}
```

### 3.5 DiagnosisEvent(SSE 推送单元,spec 没定义,补上)
```java
public record DiagnosisEvent(String type, String content) {}
// type ∈ {start, tool_result, knowledge, root_cause, fix, done, error}
```

### 3.6 DiagnosisRecord(MongoDB)
```java
@Document(collection = "diagnosis_records")
public class DiagnosisRecord {
    @Id private String id;
    private String diagnosisId;
    private AlertEvent alert;
    private List<ToolResult> toolResults;
    private String rootCause;
    private String fixSuggestion;
    private List<String> relatedCaseIds;
    private long createdAt;
    private boolean humanVerified;
}
```

### 3.7 AlertRecord(MongoDB)
```java
@Document(collection = "alerts")
public class AlertRecord {
    @Id private String id;
    @Indexed(unique = true) private String alertId;
    private AlertEvent alert;
    private long createdAt;
    private boolean suppressed;
}
```

## 4. 核心接口(修正 spec §5 的注解误用)

```java
// 告警收敛 —— 普通接口,别用 @Component 注解接口
public interface ConvergenceService {
    boolean shouldSuppress(AlertEvent alert);   // true=抑制
    void release(AlertEvent alert);             // 告警恢复时删 key
    default String key(AlertEvent a) {
        return "alert:converge:" + a.serviceName() + ":" + a.alertName();
    }
}

// 诊断引擎 —— 返回结构化事件,不是裸 String
public interface DiagnosisEngine {
    Flux<DiagnosisEvent> diagnose(AlertEvent alert);
}

// 工具 Agent
public interface DiagnosisAgent {
    String name();
    Mono<ToolResult> execute(DiagnosticContext context);   // Mock 阻塞跑虚拟线程
}

// 告警存取 —— POST 保存,GET stream 按 alertId 取,别用裸缓存糊弄
public interface AlertStore {
    Mono<AlertEvent> save(AlertEvent alert, boolean suppressed);
    Mono<AlertEvent> findByAlertId(String alertId);
}
```

## 5. LangChain4j 1.x 适配清单(升级后必改)

| 旧(0.36) | 新(1.x) |
|---|---|
| `ChatLanguageModel` | `ChatModel` |
| `StreamingChatLanguageModel` | `StreamingChatModel` |
| `PackyCodeChatModel implements ChatLanguageModel` | Phase 0 已删除,改用官方 `OpenAiChatModel` |
| `embeddingStore.findRelevant(emb, k, minScore)` | `embeddingStore.search(EmbeddingSearchRequest.builder()...)` |
| `@AiService` 注解 | 1.x 仍支持,但校验 import 路径 |

> ⚠️ 执行 Agent:先做完此表让**旧项目编译通过**,再写 aiops 新代码。两件事别混在一个提交里。

## 6. SupervisorAgent 调度(确定性,不用 LLM 调度)

```java
public Flux<DiagnosisEvent> diagnose(AlertEvent alert) {
    DiagnosticContext ctx = new DiagnosticContext(alert);

    Flux<DiagnosisEvent> start = Flux.just(
        new DiagnosisEvent("start", "开始诊断: " + alert.alertName())
    );

    Flux<ToolResult> toolResults = Flux.merge(agents.stream()
        .map(a -> a.execute(ctx)
            .onErrorResume(e -> Mono.just(new ToolResult(
                a.name(), false, e.getMessage(), 0
            ))))
        .toList());

    Mono<List<ToolResult>> collectedTools = toolResults.collectList()
        .doOnNext(ctx::addAllToolResults);

    Flux<DiagnosisEvent> toolEvents = collectedTools.flatMapMany(results ->
        Flux.fromIterable(results)
            .map(r -> new DiagnosisEvent("tool_result", r.agentName() + ": " + r.output()))
    );

    Flux<DiagnosisEvent> rest = collectedTools.thenMany(Flux.concat(
        Mono.fromCallable(() -> knowledge.search(alert.description(), 3))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnNext(ctx::addAllKnowledge)
            .map(cs -> new DiagnosisEvent("knowledge", "检索到 " + cs.size() + " 条案例")).flux(),
        Mono.fromCallable(() -> analyzer.analyze(ctx))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnNext(ctx::setRootCause)
            .map(rc -> new DiagnosisEvent("root_cause", rc)).flux(),
        Mono.fromCallable(() -> analyzer.suggestFix(ctx))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnNext(ctx::setFixSuggestion)
            .map(fx -> new DiagnosisEvent("fix", fx)).flux(),
        repository.save(toRecord(ctx))
            .thenMany(Flux.just(new DiagnosisEvent("done", "诊断完成")))
    ));

    return Flux.concat(start, toolEvents, rest)
        .onErrorResume(e -> Flux.just(new DiagnosisEvent("error", e.getMessage())));
}
```
要点:
- 并行 Agent 不直接写 `DiagnosticContext` 的 `ArrayList`,先 `collectList()`,再一次性写入。
- Reactive Mongo 的 `save()` 必须在链路里返回并订阅,不能包进 `fromRunnable()`。
- 阻塞调用(Mock 查询、LLM)用 `.subscribeOn(Schedulers.boundedElastic())` 或 Java 21 虚拟线程承载,不阻塞 WebFlux event loop。

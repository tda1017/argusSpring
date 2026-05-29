# 简历描述与真实项目实现对比分析

> 分析日期：2026-04-27  
> 项目：`argus-review`（Spring Boot 版本）  
> 分析范围：简历中 "Argus — 多 Agent 协作的自动化代码审查与修复系统" 的三项核心描述

---

## 1. AI 工程底座：Java 21 + LangChain4j + @Tool 多 Agent 协同

| 简历描述 | 真实实现 | 差异分析 |
|---------|---------|---------|
| 基于 Java 21 + LangChain4j | **属实**。`pom.xml` 指定 `java.version=21`，LangChain4j 版本 `0.36.2`。 | 无差异。 |
| 利用 `@Tool` 落地多 Agent 协同 | **基本属实**。`GitDiffTool` 已注册为 Spring Bean，三个 `@AiService` Agent 显式绑定 `gitDiffTool`，自定义 PackyCode 模型已支持 OpenAI tools/tool_calls 协议。 | 仍需注意：当前工具只接 GitHub PR/Commit Diff，不支持 GitLab；工具调用已做本地协议测试，但真实 LLM 自主调用仍需在集成环境验证。 |
| 多 Agent **协同** | **概念夸大**。三个 Agent 通过 `CompletableFuture` + Virtual Thread **独立并行**执行，结果简单拼接聚合。 | 没有 Agent 间的消息传递、任务分解、结果互相引用或主从调度。这更像是"多 Agent 并行"，而非真正意义上的"协同"。 |
| 单次审查耗时 1 分钟以内 | **不能写**。DeepSeek 实测中 small/orchestrated P50 约 80.986s，medium/stream 总耗时单次约 156.418s。 | 1 分钟不是稳定完成指标。 |

---

## 2. RAG 向量检索：Milvus + Chunking + Embedding

| 简历描述 | 真实实现 | 差异分析 |
|---------|---------|---------|
| 集成 Milvus 向量数据库 | **已验证**。`LangChain4jConfig` 中确实配置了 `MilvusEmbeddingStore`，并已通过 `docker compose` + `prod` profile 复跑 `RagRetrievalEvaluationTest`。 | 默认/开发环境仍使用 `InMemoryEmbeddingStore`，但 Milvus 不再只是配置存在。 |
| 优化文本切块 (Chunking) | **有局限**。实现了 `MethodLevelDocumentSplitter`，按 Java 方法签名 + 花括号深度进行切分；启动时 `DocumentIngestionService` 会预加载 `docs/specs`。 | 该切分器仍偏 Java 源码，对 Markdown/TXT 规范文档只能算可用，不算针对性优化。 |
| Embedding 策略 | **属实**。使用本地 ONNX 模型 `BgeSmallEnV15QuantizedEmbeddingModel`（384 维），无需远程 API。 | 无差异。 |
| 私有规范检索命中率 90%+ | **部分可验证**。已有 20 条小规模标注数据，`docs/specs` 场景下 recall@3=1.0。 | 只能写“小规模评估 recall@3=1.0”，不能外推成生产命中率 90%+。 |

---

## 3. 流式响应与高并发：WebFlux + SSE + TTFT/吞吐

| 简历描述 | 真实实现 | 差异分析 |
|---------|---------|---------|
| WebFlux + SSE 异步非阻塞 | **半属实**。`ReviewController` 确实使用 `produces = TEXT_EVENT_STREAM_VALUE`，对外暴露 SSE 接口。 | **底层并非全链路非阻塞**：`PackyCodeStreamingChatModel` 内部使用 `HttpClient.send()`（同步阻塞 IO）获取 SSE 流，只是通过 Virtual Thread / `Flux.create` 包裹成响应式流对外暴露。同步审查接口 `/sync` 也依然存在。 |
| 首字响应延迟 TTFT 300ms 内 | **不能写**。small/stream 实测 TTFB P50 约 6.414s，medium/stream 单次约 12.632s。 | 只能写"SSE 降低首字等待"，不能写 300ms。 |
| 吞吐能力提升约 3 倍 | **不能写**。没有同步 Tomcat vs WebFlux 对比基准；当前 DeepSeek 实测吞吐只有约 0.01-0.02 req/s。 | "约 3 倍"属于虚假性能数字。 |

---

## 4. 其他重要差异

| 简历描述 | 真实实现 | 结论 |
|---------|---------|------|
| **自动化代码审查与修复系统** | 已有 `FixAgent` 和 `/api/v1/review/fix`，可基于调用方传入的 Diff 与审查报告生成 unified diff patch；但没有自动提交修改，也缺少真实仓库 `git apply` 验证。 | 可写"修复建议/patch 生成"，不能写"自动提交修复"或"生产可用修复系统"。 |
| **大模型微服务** | 无 Dockerfile、无 K8s 配置、无服务注册发现、无 CI/CD 流水线。本质是一个**单体 Spring Boot 应用**。 | "微服务"表述略显夸大。 |
| 对接 OpenAI gpt-5.4 | `application.yml` 中配置的模型名为 `gpt-5.4`，通过 `packycode` 中转站调用。 | 模型版本号应按第三方中转站兼容命名表述，避免写成 OpenAI 官方模型。 |

---

## 5. 总结评分

| 维度 | 真实度 |
|------|--------|
| 技术栈（Java 21 / LangChain4j / WebFlux / Milvus） | 基本属实 |
| 多 Agent 架构存在性 | 属实 |
| **多 Agent "协同"深度** | 夸大（实为并行聚合） |
| **`@Tool` 落地程度** | 已落地（Agent 绑定 + 模型协议支持） |
| **RAG 命中率 90%+** | 小规模有数据，`prod` profile + Milvus 复跑也为 1.0，但样本仍小 |
| **TTFT 300ms / 吞吐提升 3 倍** | 已被实测否定 |
| **"修复系统"** | 初版 patch 生成已落地，未生产验证 |
| **"微服务"** | 夸大（单体应用） |

---

## 6. 简历修改建议

若此描述用于求职简历或技术评审，建议做以下调整以降低面试中被追问的风险：

1. **"多 Agent 协同"** → 改为 **"多 Agent 并行审查"** 或 **"多维度 Agent 独立审查后聚合"**，避免面试官追问协同机制时无法自圆其说。
2. **"修复系统"** → 改为 **"审查与修复建议系统"**，因为当前只生成 patch，不自动提交或验证应用结果。
3. **删除虚假性能数字**：
   - "单次审查 1 分钟以内" → 删除
   - "TTFT 300ms"、"吞吐提升 3 倍" → 删除
   - 可写："SSE 将 small Diff 首字等待从约 40.9s 降至约 6.4s，但总耗时仍需优化"
   - "命中率 90%+" → 只能限定为"20 条小规模标注集 recall@3=1.0"
4. **`@Tool`** → 可保留，但表述应限定为"支持 LLM 通过工具自主获取 GitHub PR/Commit Diff"，不要写 GitLab。
5. **"微服务"** → 如尚未容器化/集群化部署，建议改为 **"基于 Spring Boot 的大模型服务"** 或 **"审查后端服务"**。
6. **模型版本** → 建议注明 `gpt-5.4` 为第三方中转站兼容命名，避免被误认为虚构技术栈。

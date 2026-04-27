# 简历描述与真实项目实现对比分析

> 分析日期：2026-04-26  
> 项目：`argus-review`（Spring Boot 版本）  
> 分析范围：简历中 "Argus — 多 Agent 协作的自动化代码审查与修复系统" 的三项核心描述

---

## 1. AI 工程底座：Java 21 + LangChain4j + @Tool 多 Agent 协同

| 简历描述 | 真实实现 | 差异分析 |
|---------|---------|---------|
| 基于 Java 21 + LangChain4j | **属实**。`pom.xml` 指定 `java.version=21`，LangChain4j 版本 `0.36.2`。 | 无差异。 |
| 利用 `@Tool` 落地多 Agent 协同 | **半属实**。项目中确实定义了 `GitDiffTool` 并标注了 `@Tool`，也定义了 3 个 `@AiService` Agent（Security/Style/Logic）。 | **关键差异**：`GitDiffTool` **没有任何 Agent 注册或调用**。代码 Diff 由上层 Controller/Service 直接传入，LLM 并未自主触发工具调用。`@Tool` 处于"定义了但未使用"的状态。 |
| 多 Agent **协同** | **概念夸大**。三个 Agent 通过 `CompletableFuture` + Virtual Thread **独立并行**执行，结果简单拼接聚合。 | 没有 Agent 间的消息传递、任务分解、结果互相引用或主从调度。这更像是"多 Agent 并行"，而非真正意义上的"协同"。 |
| 单次审查耗时 1 分钟以内 | **无依据**。代码中 HTTP timeout 设为 60s，这只是**超时阈值**，不是实测性能指标。项目中没有任何性能基准测试或压测脚本（JMeter/k6/Gatling）。 | 1 分钟是"请求最多等 1 分钟"，不是"保证在 1 分钟内完成"。 |

---

## 2. RAG 向量检索：Milvus + Chunking + Embedding

| 简历描述 | 真实实现 | 差异分析 |
|---------|---------|---------|
| 集成 Milvus 向量数据库 | **有条件属实**。`LangChain4jConfig` 中确实配置了 `MilvusEmbeddingStore`，但仅在 **`prod` profile** 下生效。 | 默认/开发环境使用的是 `InMemoryEmbeddingStore`，本地运行并不连接 Milvus。 |
| 优化文本切块 (Chunking) | **有局限**。实现了 `MethodLevelDocumentSplitter`，按 Java 方法签名 + 花括号深度进行切分。 | 该切分器是**面向 Java 源码**设计的，对 Markdown/TXT 格式的规范文档切分效果存疑。而 `docs/specs/` 下实际存放的是项目设计文档（`design.md`、`requirements.md`、`tasks.md`），并非真实业务私有规范。 |
| Embedding 策略 | **属实**。使用本地 ONNX 模型 `BgeSmallEnV15QuantizedEmbeddingModel`（384 维），无需远程 API。 | 无差异。 |
| 私有规范检索命中率 90%+ | **无法验证**。项目中没有任何检索准确率评估测试、没有标注数据集、没有命中率统计代码。 | "90%" 是一个**空口数字**，没有任何工程数据或测试支撑。 |

---

## 3. 流式响应与高并发：WebFlux + SSE + TTFT/吞吐

| 简历描述 | 真实实现 | 差异分析 |
|---------|---------|---------|
| WebFlux + SSE 异步非阻塞 | **半属实**。`ReviewController` 确实使用 `produces = TEXT_EVENT_STREAM_VALUE`，对外暴露 SSE 接口。 | **底层并非全链路非阻塞**：`PackyCodeStreamingChatModel` 内部使用 `HttpClient.send()`（同步阻塞 IO）获取 SSE 流，只是通过 Virtual Thread / `Flux.create` 包裹成响应式流对外暴露。同步审查接口 `/sync` 也依然存在。 |
| 首字响应延迟 TTFT 300ms 内 | **无依据**。项目中没有任何 latency 测试、百分位统计或 APM 埋点。 | 300ms 是未经测试的宣称数字。 |
| 吞吐能力提升约 3 倍 | **无依据**。没有对比基准（如同步 Tomcat vs WebFlux 的压测数据），没有吞吐测试脚本。 | "约 3 倍"属于无法验证的定性描述。 |

---

## 4. 其他重要差异

| 简历描述 | 真实实现 | 结论 |
|---------|---------|------|
| **自动化代码审查与修复系统** | 项目只有**审查（Review）+ 文本建议**，没有**自动修复（Auto-fix）**功能。没有生成代码 patch、没有自动提交修改。 | "修复系统"名不副实，准确说是"审查与建议系统"。 |
| **大模型微服务** | 无 Dockerfile、无 K8s 配置、无服务注册发现、无 CI/CD 流水线。本质是一个**单体 Spring Boot 应用**。 | "微服务"表述略显夸大。 |
| 对接 OpenAI gpt-5.4 | `application.yml` 中配置的模型名为 `gpt-5.4`，通过 `packycode` 中转站调用。但 **OpenAI 官方并无 gpt-5.4 这一版本**（当前官方最新为 GPT-4o/GPT-4.5/o3 系列）。 | 模型版本号存疑，建议核实并明确注明是第三方中转兼容命名。 |

---

## 5. 总结评分

| 维度 | 真实度 |
|------|--------|
| 技术栈（Java 21 / LangChain4j / WebFlux / Milvus） | 基本属实 |
| 多 Agent 架构存在性 | 属实 |
| **多 Agent "协同"深度** | 夸大（实为并行聚合） |
| **`@Tool` 落地程度** | 未落地（定义未使用） |
| **RAG 命中率 90%+** | 无依据 |
| **TTFT 300ms / 吞吐提升 3 倍** | 无依据 |
| **"修复系统"** | 无自动修复能力 |
| **"微服务"** | 夸大（单体应用） |

---

## 6. 简历修改建议

若此描述用于求职简历或技术评审，建议做以下调整以降低面试中被追问的风险：

1. **"多 Agent 协同"** → 改为 **"多 Agent 并行审查"** 或 **"多维度 Agent 独立审查后聚合"**，避免面试官追问协同机制时无法自圆其说。
2. **"修复系统"** → 改为 **"审查与建议系统"**，因为项目中确实没有 auto-fix 或 patch 生成能力。
3. **删除或弱化无法验证的性能数字**：
   - "单次审查 1 分钟以内" → 改为 "LLM 调用超时设为 60s"
   - "TTFT 300ms"、"吞吐提升 3 倍"、"命中率 90%+" → 建议删除，或补充标注"目标值"而非"实测值"
4. **`@Tool`** → 如要保留，需说明是"预定义工具，支持 LLM 自主调用 Diff 获取"，并最好在代码中真正将 `GitDiffTool` 注册到 Agent（LangChain4j 的 `@AiService` 支持 `tools = GitDiffTool.class`）。
5. **"微服务"** → 如尚未容器化/集群化部署，建议改为 **"基于 Spring Boot 的大模型服务"** 或 **"审查后端服务"**。
6. **模型版本** → 建议注明 `gpt-5.4` 为第三方中转站兼容命名，避免被误认为虚构技术栈。

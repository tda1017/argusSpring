# Argus Review — Roadmap

> 最后更新：2026-05-28  
> 基于：简历 vs 现实差距分析 + 代码审计

---

## 当前状态速览

| 模块 | 状态 | 说明 |
|------|------|------|
| 多 Agent 并行审查 | ✅ 已落地 | Security/Style/Logic 三路 VirtualThread 并行 |
| FixAgent 修复建议 | ✅ 已落地 | unified diff patch，`/fix` 端点 |
| Security → Fix 链式路由 | ✅ 已落地 | OrchestratorAgent 关键词路由 |
| RAG 检索增强 | ✅ 已落地 | InMemory 模式，20条小规模 recall@3=1.0 |
| GitHub Webhook 全链路 | ✅ 已落地 | HMAC 签名 → Diff 拉取 → 审查 → PR Comment |
| 自定义 LLM 适配 | ✅ 已落地 | SSE 解析 + Tool Calling 协议，601 行 |
| 容器化 + CI | ✅ 已落地 | Dockerfile + docker-compose + GitHub Actions |
| Milvus 生产对接 | ✅ 已验证 | `docker compose` 可拉起 Milvus，`prod` profile RAG recall@3=1.0 |
| 性能基准 | ✅ 已初测 | DeepSeek 小/中 Diff 已跑，数据不适合吹性能 |
| SSE 全链路异步 | ⚠️ 半完成 | 外层 Flux，底层 HttpClient 同步阻塞 |

---

## P0：堵住简历致命漏洞（面试被追问会穿帮）

### P0-1：跑真实 Benchmark

**问题**：简历写了 TTFT 300ms、吞吐 3 倍、审查 ≤1 分钟，全部无真实数据。

**状态**：已完成第一轮小/中 Diff 实测。结果证明原性能数字不能写。

**任务**：
1. [x] 确保 LLM API Key 可用，应用能正常启动
2. [x] 准备小/中/大 benchmark payload
3. [x] 跑 `small` 的 `sync`、`stream`、`orchestrated`
4. [x] 跑 `medium` 的 `sync`、`stream`
5. [x] 记录原始结果到 `docs/benchmark-results.md`
6. [x] 根据真实数据修改简历措辞，删除虚假性能数字

**产出**：`docs/benchmark-results.md` 已更新为真实数据

### P0-2：Milvus 真实对接验证

**状态**：已完成。

**结果**：
1. `docker-compose.yml` 已加入 Milvus standalone（etcd + minio + milvus）
2. `application.yml` / `LangChain4jConfig` 的 `prod` profile 已接入 Milvus
3. `scripts/evaluate-rag.sh` 复跑成功
4. `ARGUS_RAG_EVAL_PROFILE=prod` 下 recall@3 仍为 `1.0000（20/20）`

**产出**：`docs/rag-evaluation-results.md` 已更新

### P0-3：RAG 评估扩大样本

**问题**：20 条自引用样本不具说服力。

**目标**：至少 50 条来自真实代码规范的 query，recall@3 有统计意义。

**任务**：
1. 从主流 Java 编码规范（阿里巴巴 Java 规范、Google Java Style 等）提取 30+ 条规范描述
2. 将这些规范文档 ingest 到 RAG 系统
3. 构建对应查询（"XXX 场景应该怎么写"→ 期望命中哪条规范）
4. 跑 recall@3，结果归档到 `docs/rag-evaluation-results.md`
5. 根据结果决定简历写法：
   - recall@3 ≥ 0.85 → "规范检索命中率 85%+"
   - recall@3 < 0.85 → 只写 "RAG 增强审查上下文"，不写命中率

**产出**：`docs/rag-evaluation-dataset.csv` 扩容，`docs/rag-evaluation-results.md` 更新

---

## P1：修正简历措辞（不改代码）

### P1-1：术语纠正

| 当前措辞 | 改为 | 原因 |
|---------|------|------|
| 大模型**微服务** | 大模型**服务** / **应用** | 单体 Spring Boot，无服务拆分 |
| 多 Agent **协同** | 多 Agent **并行审查** | 三路独立并行 + 结果拼接，非协同 |
| TTFT 300ms | 删除 | 实测 small/stream TTFB P50 约 6.414s |
| 吞吐提升 3 倍 | 删除 | 无对比基准，当前吞吐约 0.01-0.02 req/s |
| RAG 命中率 90%+ | "小规模评估 recall@3=1.0" 或等 P0-3 | 样本量不够 |

### P1-2：更新简历文档

**任务**：
1. 更新 `docs/resume-argus-project.md` 所有版本措辞
2. 同步更新 `docs/resume-vs-reality-analysis.md` 标记已修正项
3. 确保"不建议这样写"列表与代码现状匹配

---

## P2：真正提升项目含金量（改代码加分）

### P2-1：SSE 全链路真异步

**问题**：`PackyCodeStreamingChatModel` 底层用 `HttpClient.send()`（同步阻塞），WebFlux + SSE 只是表面功夫。

**方案**：改用 `HttpClient.sendAsync()` + `BodyHandler.ofLines()` 真异步，或用 Reactor Netty `HttpClient`。

**收益**：简历"WebFlux + SSE 异步非阻塞"描述变为 100% 真实。

### P2-2：端到端实仓演示

**目标**：用真实测试仓库 + ngrok 走一遍完整闭环。

**任务**：
1. 创建 GitHub 测试仓库
2. 本地 ngrok 暴露 Webhook 端点
3. 提 PR → 自动触发审查 → PR Comment 回写
4. 录制截图/GIF 作为演示材料
5. 写入 `docs/demo.md`

**收益**：面试可直接展示真实效果。

### P2-3：Agent 协同深化

**问题**：当前"协同"仅 Security → Fix 一条链路，OrchestratorAgent 只做关键词匹配。

**方案**：
- 所有 Agent 输出结构化 JSON（不只是文本拼接）
- StyleAgent 发现问题 → FixAgent 也能修复
- Agent 间通过 `AgentMessage` 传递上下文，不只是字符串传参
- OrchestratorAgent 升级为多维度路由（安全/规范/逻辑 → 各自 Fix）

**收益**：简历可以真正写"多 Agent 协同"。

---

## 已完成

| 项 | 完成日期 |
|----|---------|
| P1（原）：FixAgent 自动修复建议 | 2026-05 |
| P2（原）：Agent 链式协同初版 | 2026-05 |
| P4（原）：RAG recall@3 小规模评估 | 2026-05 |
| P5（原）：容器化 + CI | 2026-05 |
| benchmark 脚本编写 | 2026-05 |
| P0-1 Benchmark 第一轮实测 | 2026-05 |

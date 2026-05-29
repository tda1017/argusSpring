# 性能基准结果

> 最后更新：2026-05-28  
> Provider：DeepSeek 官方接口  
> Model：`deepseek-v4-pro`  
> Profile：`local`，InMemory RAG，预加载 `docs/specs`

## 结论

当前**没有任何可以包装成高性能的数字**。

- 同步审查小 Diff P50 约 `40.899s`
- 流式审查小 Diff TTFB P50 约 `6.414s`，但总耗时 P50 约 `93.747s`
- 链式协同小 Diff P50 约 `80.986s`
- 中等 Diff 同步单次约 `55.866s`
- 中等 Diff 流式首字约 `12.632s`，总耗时约 `156.418s`

能写的只有：**SSE 可降低首字等待**。不能写吞吐提升、300ms TTFT、1 分钟稳定完成。

## 原始结果

| Payload | Mode | Requests | Concurrency | Failed | TTFB P50 | TTFB P95 | Total P50 | Total P95 |
|---------|------|----------|-------------|--------|----------|----------|-----------|-----------|
| small | sync | 3 | 1 | 0 | `40.899s` | `49.614s` | `40.899s` | `49.614s` |
| small | stream | 3 | 1 | 0 | `6.414s` | `7.160s` | `93.747s` | `96.974s` |
| small | orchestrated | 3 | 1 | 0 | `80.986s` | `107.864s` | `80.986s` | `107.865s` |
| medium | sync | 1 | 1 | 0 | `55.866s` | `55.866s` | `55.866s` | `55.866s` |
| medium | stream | 1 | 1 | 0 | `12.632s` | `12.632s` | `156.418s` | `156.418s` |

原始 CSV：

- `docs/benchmark-raw/small-sync.csv`
- `docs/benchmark-raw/small-stream.csv`
- `docs/benchmark-raw/small-orchestrated.csv`
- `docs/benchmark-raw/medium-sync.csv`
- `docs/benchmark-raw/medium-stream.csv`

## 执行命令

```bash
SPRING_PROFILES_ACTIVE=local mvn -q -Dmaven.repo.local=../.m2 spring-boot:run
```

```bash
ARGUS_BENCH_MODE=sync \
ARGUS_BENCH_REQUESTS=3 \
ARGUS_BENCH_CONCURRENCY=1 \
ARGUS_BENCH_MAX_TIME=180 \
ARGUS_BENCH_PAYLOAD_FILE=docs/benchmark-payloads/small.json \
ARGUS_BENCH_OUTPUT=docs/benchmark-raw/small-sync.csv \
scripts/benchmark-review.sh
```

## 修复记录

基准过程中发现并修掉两个问题：

- `orchestrated` 在 DeepSeek `thinking` 模式下 500：多轮 tool-call 需要回传 `reasoning_content`，当前协议层不支持，已默认禁用 `thinking`
- `FixAgent` 会误调 `fetchMrDiff`：修复阶段已经有 `codeDiff`，不该让 LLM 再拉外部 PR，已移除 FixAgent 工具绑定

## 简历约束

必须删除：

- `TTFT 300ms`
- `吞吐提升 3 倍`
- `单次审查 1 分钟以内`

可以保守写：

- `通过 SSE 流式输出将小 Diff 首字等待从约 40.9s 降至约 6.4s`
- `完成 sync / stream / orchestrated 三类接口的真实 LLM 基准测试，并据此修正性能表述`

# Argus Review — Spring Boot 版文档中心

> 多 Agent 并行的自动化代码审查系统  
> 最后更新：2026-05-28

## 快速导航

| 我需要 | 入口 |
|--------|------|
| 了解项目现状 | [project-status-summary.md](project-status-summary.md) |
| 写简历 / 准备面试 | [resume-argus-project.md](resume-argus-project.md) |
| 简历 vs 现实差距 | [resume-vs-reality-analysis.md](resume-vs-reality-analysis.md) |
| 下一步做什么 | [roadmap.md](roadmap.md) |
| 性能基准 | [benchmark-results.md](benchmark-results.md) |
| RAG 评估 | [rag-evaluation-results.md](rag-evaluation-results.md) |
| 需求规格 | [specs/requirements.md](specs/requirements.md) |
| 架构设计 | [specs/design.md](specs/design.md) |
| 任务进度 | [specs/tasks.md](specs/tasks.md) |

## 目录结构

```text
docs/
├── README.md                          ← 你在这里
├── project-status-summary.md          当前代码真实状态
├── resume-argus-project.md            简历写法模板（多版本 + STAR）
├── resume-vs-reality-analysis.md      简历描述 vs 代码实现差距分析
├── roadmap.md                         改进计划（P0/P1/P2 + 已完成项）
├── benchmark-results.md               性能基准结果与执行方式
├── rag-evaluation-results.md          RAG 检索评估结果
├── rag-evaluation-dataset.csv         RAG recall@3 标注数据集
└── specs/
    ├── requirements.md                需求文档
    ├── design.md                      设计文档（六角架构 + 分层）
    └── tasks.md                       实现任务清单
```

## 部署

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
docker compose up --build
```

默认 LLM Provider 为 DeepSeek 官方 OpenAI 兼容接口：

- Base URL: `https://api.deepseek.com`
- Model: `deepseek-v4-pro`
- 本地 Key: `src/main/resources/application-local.yml`，该文件已被 `.gitignore` 忽略
- 模板: `src/main/resources/application-local.example.yml`

## 建议阅读顺序

1. `project-status-summary.md` — 项目真实状态
2. `specs/design.md` — 架构决策
3. `roadmap.md` — 下一步方向
4. `resume-argus-project.md` — 准备简历或面试

## 相关

- [Python 版设计文档](../../docs/README.md) — 早期 Python 版本的架构设计，部分理念沿用到 Spring 版

# 开发指引

> 本文档面向实现代码的开发者/Agent。按照本文档的顺序实现，每个 Phase 都能交付一个可用的系统。

## 开发原则

1. **不用 LangChain / CrewAI / AutoGen** — 直接用 Anthropic/OpenAI SDK 的 function calling，自己写编排逻辑。
2. **先跑通再优化** — Phase 1 不需要 RAG、不需要 Reranker，先让 Review Agent 能分析 diff 就行。
3. **测试先行** — 每个工具、每个 Agent 都要有测试，用 fixtures (样本 diff/日志) 跑。
4. **结构化输出** — Agent 的输出必须是可解析的结构化数据，不是自由文本。

## Phase 1: Review Agent (独立可用)

### 目标
一个能接收 PR diff、输出结构化审查意见的 Agent。不需要 GitHub 集成，CLI 或 API 调用即可。

### Step 1.1: 项目骨架

```bash
# 初始化项目
cd argus
python -m venv .venv
source .venv/bin/activate
pip install fastapi uvicorn httpx anthropic pydantic
```

创建基础目录结构 (参考 architecture.md 的目录结构)。

配置文件:
```python
# src/config.py
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    anthropic_api_key: str = ""
    openai_api_key: str = ""
    github_app_id: int = 0
    github_private_key: str = ""
    github_webhook_secret: str = ""
    llm_provider: str = "anthropic"         # "anthropic" | "openai"
    llm_model: str = "claude-sonnet-4-20250514"
    qdrant_path: str = "./qdrant_data"

    class Config:
        env_file = ".env"
```

### Step 1.2: Agent 基类

参考 `architecture.md` 中的 Agent 伪代码，实现:

```python
# src/agents/base.py
class BaseAgent:
    """
    核心 tool-calling loop:
    1. 发送 messages + tools 给 LLM
    2. 如果 LLM 返回 tool_use，执行工具，把结果追加到 messages
    3. 重复，直到 LLM 返回 end_turn
    4. 解析最终输出为结构化数据
    """
```

关键实现细节:
- 支持 Anthropic 和 OpenAI 两种 provider (通过 config 切换)
- tool calling loop 最多 10 轮，防止死循环
- 每轮 tool call 结果追加到 messages 中
- 最终输出要求 LLM 返回 JSON，用 pydantic 解析验证

### Step 1.3: Diff 解析器

```python
# src/tools/diff_parser.py
def parse_unified_diff(diff: str) -> list[DiffFile]:
    """
    解析 unified diff 格式，返回结构化数据

    DiffFile:
      - path: str
      - hunks: list[DiffHunk]

    DiffHunk:
      - header: str (如 @@ -10,5 +10,7 @@)
      - added_lines: list[(int, str)]   # (行号, 内容)
      - removed_lines: list[(int, str)]
      - context_lines: list[(int, str)]
    """
```

需要测试用例: 在 `tests/fixtures/sample_diffs/` 下放几个真实的 diff 文件。

### Step 1.4: Review Agent 实现

参考 `review-agent.md`，实现:
- system prompt
- 工具注册 (Phase 1 只需要基础的 diff 分析，不需要 RAG 工具)
- 输入/输出模型 (ReviewInput / ReviewOutput)
- Diff 分块策略 (大 PR 按文件拆分)

### Step 1.5: CLI 入口

```python
# 能通过命令行测试
python -m argus review --repo owner/repo --pr 123
# 或者直接传 diff 文件
python -m argus review --diff-file ./test.diff
```

### Step 1.6: 测试

```python
# tests/test_review_agent.py
# 用 fixtures 中的样本 diff 测试
# mock LLM 调用，验证:
# 1. diff 解析正确
# 2. 工具调用正确
# 3. 输出格式正确
```

### Phase 1 交付检查清单

- [ ] Agent 基类实现，支持 tool calling loop
- [ ] Diff 解析器，覆盖常见 diff 格式
- [ ] Review Agent 能分析 diff 并输出结构化审查意见
- [ ] CLI 可用
- [ ] 基础测试通过

---

## Phase 2: CI/CD Agent

### 目标
一个能分析 CI 构建日志、输出根因分析和修复建议的 Agent。

### Step 2.1: 日志预处理器

参考 `cicd-agent.md` 的日志裁剪流水线:
```python
# src/tools/log_parser.py
def preprocess_log(raw_log: str, max_lines: int = 500) -> str
def parse_error_trace(log_text: str, language: str) -> list[ErrorTrace]
```

关键: `parse_error_trace` 是确定性工具 (正则匹配)，不需要 LLM。先支持 Python 和 JavaScript。

### Step 2.2: CI/CD Agent 实现

参考 `cicd-agent.md`，实现:
- system prompt
- 工具注册
- 日志裁剪 + 错误提取 → LLM 分析根因
- 输出 CICDOutput

### Step 2.3: 测试

在 `tests/fixtures/sample_logs/` 下放真实的 CI 失败日志:
- Python 测试失败
- JavaScript 构建失败
- 依赖安装失败
- 超时

### Phase 2 交付检查清单

- [ ] 日志预处理器 (去噪 + 裁剪 + 错误区域提取)
- [ ] parse_error_trace (Python + JavaScript)
- [ ] CI/CD Agent 能分析日志并输出结构化结果
- [ ] 测试覆盖各种失败类型

---

## Phase 3: Orchestrator + GitHub 集成

### 目标
串联 Review Agent 和 CI/CD Agent，通过 GitHub App 接入真实 repo。

### Step 3.1: GitHub App 设置

1. 在 GitHub 创建一个 GitHub App
2. 权限: Pull Requests (read/write), Checks (read), Contents (read)
3. 订阅事件: pull_request, check_run
4. 生成 private key，配到 .env

### Step 3.2: Webhook Gateway

```python
# src/gateway/webhook.py
@app.post("/webhook")
async def handle_webhook(request: Request):
    # 1. 验证 X-Hub-Signature-256
    # 2. 解析事件类型 (X-GitHub-Event header)
    # 3. 构造 GitHubEvent
    # 4. 交给 Orchestrator
```

### Step 3.3: Orchestrator 实现

参考 `orchestrator-agent.md`，这是纯 Python 逻辑，不需要 LLM。

### Step 3.4: 端到端测试

```bash
# 用 ngrok 暴露本地服务
ngrok http 8000

# 在 GitHub App 设置中填入 ngrok URL
# 在测试 repo 上创建 PR，观察 Argus 是否自动评论
```

### Phase 3 交付检查清单

- [ ] GitHub App 认证 (JWT + installation token)
- [ ] Webhook 签名验证
- [ ] Orchestrator 事件路由
- [ ] 并行派发 Agent
- [ ] 结果聚合 + 格式化
- [ ] 发送 PR comment
- [ ] 端到端测试通过

---

## Phase 4: RAG 集成 + 评测

### 目标
接入 RAG 子系统，提升审查质量。运行评测，收集量化指标。

### Step 4.1: RAG 子系统

参考 `rag-pipeline.md`:
1. 先实现 Dense 检索 (OpenAI embedding + Qdrant)
2. 再加 BM25 + RRF 融合
3. 最后加 Reranker

### Step 4.2: 评测

参考 `metrics.md`:
1. 构建评测数据集
2. 跑评测脚本
3. 收集指标

### Phase 4 交付检查清单

- [ ] RAG 子系统可用 (至少 Dense 检索)
- [ ] 代码库索引功能
- [ ] Review Agent 集成 RAG 工具
- [ ] 评测数据集 (>=30 个样本)
- [ ] 量化指标报告

---

## 技术栈速查

| 组件 | 选型 | 版本 |
|------|------|------|
| Python | 3.11+ | |
| Web 框架 | FastAPI | 0.110+ |
| HTTP 客户端 | httpx | 0.27+ |
| LLM SDK | anthropic | 0.40+ |
| LLM SDK (备选) | openai | 1.50+ |
| 数据校验 | pydantic | 2.0+ |
| 向量库 | qdrant-client | 1.12+ |
| Embedding | BAAI/bge-m3 或 OpenAI | |
| Reranker | BAAI/bge-reranker-v2-m3 | |
| AST 解析 | tree-sitter | 0.23+ |
| 测试 | pytest + pytest-asyncio | |

## 环境变量

```bash
# .env
ANTHROPIC_API_KEY=sk-ant-...
OPENAI_API_KEY=sk-...           # 备选
GITHUB_APP_ID=123456
GITHUB_PRIVATE_KEY="-----BEGIN RSA PRIVATE KEY-----\n..."
GITHUB_WEBHOOK_SECRET=your_secret
LLM_PROVIDER=anthropic          # "anthropic" | "openai"
LLM_MODEL=claude-sonnet-4-20250514
```

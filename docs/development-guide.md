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

### Step 1.7: 噪音过滤器

参考 `user-experience.md` 第二节，实现 ReviewPostFilter：
- 严重性阈值过滤（默认只发 critical + warning）
- 每文件上限 3 条
- 每 PR 上限 8 条
- 支持 argus.md 中自定义阈值

### Phase 1 交付检查清单

- [ ] Agent 基类实现，支持 tool calling loop
- [ ] Diff 解析器，覆盖常见 diff 格式
- [ ] Review Agent 能分析 diff 并输出结构化审查意见
- [ ] 噪音过滤器（严重性阈值 + 数量上限）
- [ ] CLI 可用（`git diff | argus review`）
- [ ] --verbose 模式显示 trace 信息
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

## Phase 3: Conversation Agent + REPL

### 目标
交互式入口。用户输入 `argus` 进入持续会话，支持自然语言交互、斜杠命令、多轮对话、流式输出。ConversationAgent 作为顶层对话 Agent，将 Review Agent 和 CI/CD Agent 作为工具调用。

### Step 3.1: REPL 骨架

使用 prompt_toolkit 构建 REPL 基础框架：

```python
# src/repl/session.py
from prompt_toolkit import PromptSession
from prompt_toolkit.history import FileHistory

async def start_repl():
    session = PromptSession(
        history=FileHistory(".argus_history"),
        message="argus> ",
    )
    context = await detect_context()  # git/PR/CI 自动检测
    print_welcome(context)

    while True:
        try:
            user_input = await session.prompt_async()
            await process_input(user_input, context)
        except KeyboardInterrupt:
            continue
        except EOFError:
            break
```

交付:
- prompt_toolkit 基础 REPL
- 输入历史持久化 (.argus_history)
- Ctrl+C 中断当前操作，Ctrl+D 退出

### Step 3.2: 自动上下文检测

```python
# src/repl/context.py
async def detect_context() -> Session:
    """启动时自动检测 git/PR/CI 上下文"""
    # 1. git repo root, branch
    # 2. 通过 gh CLI 检测关联 PR
    # 3. 获取 CI 状态
    # 4. 检查 argus.md 是否存在
```

交付:
- git 仓库检测
- 当前分支、PR 关联检测
- CI 状态检测
- 欢迎信息展示检测结果

### Step 3.3: ConversationAgent 实现

参考 `conversation-agent.md`：

```python
# src/agents/conversation.py
class ConversationAgent(BaseAgent):
    """
    LLM 驱动的对话编排器
    - system prompt: 交互式代码审查助手
    - tools: review, diagnose, fix, get_git_context, get_pr_info, get_ci_status
    - 多轮对话: 维护 session.messages
    """
```

交付:
- ConversationAgent 类实现
- system prompt 定义
- 工具注册（review、diagnose、fix、get_git_context、get_pr_info、get_ci_status）
- 多轮对话上下文管理

### Step 3.4: 流式输出

```python
# src/repl/renderer.py
# 使用 rich 库渲染:
# - Markdown 格式化
# - 代码块语法高亮
# - 工具调用 spinner
# - 审查结果颜色区分
```

交付:
- LLM streaming API 集成
- rich 终端渲染
- 工具调用期间显示 spinner

### Step 3.5: 斜杠命令

```python
# src/repl/commands.py
SLASH_COMMANDS = {
    "/review": handle_review,      # 审查当前分支变更
    "/diagnose": handle_diagnose,  # 分析 CI 失败
    "/fix": handle_fix,            # 生成修复补丁
    "/context": handle_context,    # 显示上下文
    "/clear": handle_clear,        # 清除对话
    "/help": handle_help,          # 显示帮助
    "/exit": handle_exit,          # 退出
}
```

交付:
- 所有斜杠命令实现
- `/review --staged` 等参数支持
- Tab 补全

### Phase 3 交付检查清单

- [ ] REPL 基础框架 (prompt_toolkit)
- [ ] 自动上下文检测 (git/PR/CI)
- [ ] ConversationAgent 实现 (system prompt + tools + 多轮对话)
- [ ] 流式输出 (streaming + rich 渲染)
- [ ] 斜杠命令 (/review, /diagnose, /fix, /context, /clear, /help, /exit)
- [ ] 测试 (conversation agent + REPL 集成)

---

## Phase 4: Fix Agent

### 目标
一个能基于 Review Agent 或 CI/CD Agent 的诊断结果，自动生成修复补丁的 Agent。

### Step 4.1: Fix Agent 实现

参考 `fix-agent.md`：
- system prompt：精确修复，最小变更
- 工具：get_file_content、search_codebase、apply_patch
- 输入：诊断结果（ReviewOutput 或 CICDOutput）+ 文件上下文
- 输出：FixOutput（patch 列表）

### Step 4.2: Patch 生成与应用

```python
# Fix Agent 输出结构
@dataclass
class FilePatch:
    path: str              # 文件路径
    original: str          # 原始内容片段
    patched: str           # 修复后内容
    explanation: str       # 修复说明

@dataclass
class FixOutput:
    patches: list[FilePatch]
    summary: str           # 修复摘要
    confidence: float      # 0-1 置信度
```

### Step 4.3: 验证流水线

参考 `verification-pipeline.md`，在 patch 生成后、apply 前加入确定性验证：

```python
# verification/pipeline.py
# Stage 1: ast.parse() 语法校验
# Stage 2: 范围检查（行数/文件数/新增import限制）
# Stage 3: AST 级公开 API 签名对比
# Stage 4: ruff + mypy 静态分析
# Stage 5: 隔离 worktree 中跑 pytest
```

交付:
- 5 个验证阶段实现
- 信任分级逻辑（auto_merge / suggest / comment_only / reject）
- 隔离 worktree 执行环境
- 修复类型白名单（允许/禁止自动修复的类型列表）

### Step 4.4: REPL 集成

- `/fix` 命令触发修复流程
- 展示 diff 预览，用户确认后 apply
- 支持逐个 patch 确认或全部 apply

### Step 4.5: 测试

- 基于 fixtures 中的已知问题测试修复质量
- 验证 patch 可正确 apply
- 验证不引入新问题

### Phase 4 交付检查清单

- [ ] Fix Agent 实现 (system prompt + tools)
- [ ] Patch 生成 (结构化 FilePatch 输出)
- [ ] 验证流水线 5 个 Stage (语法/范围/接口/lint/测试)
- [ ] 信任分级 (auto_merge/suggest/comment_only/reject)
- [ ] 隔离 worktree 执行环境
- [ ] REPL 中的确认流程 (预览 → 确认 → apply)
- [ ] CLI one-shot 模式支持 (`argus fix`)
- [ ] 测试

---

## Phase 5: Orchestrator + GitHub 集成

### 目标
串联所有 Agent，通过 GitHub App 接入真实 repo。

### Step 5.1: GitHub App 设置

1. 在 GitHub 创建一个 GitHub App
2. 权限: Pull Requests (read/write), Checks (read), Contents (read)
3. 订阅事件: pull_request, check_run
4. 生成 private key，配到 .env

### Step 5.2: Webhook Gateway

```python
# src/gateway/webhook.py
@app.post("/webhook")
async def handle_webhook(request: Request):
    # 1. 验证 X-Hub-Signature-256
    # 2. 解析事件类型 (X-GitHub-Event header)
    # 3. 构造 GitHubEvent
    # 4. 交给 Orchestrator
```

### Step 5.3: Orchestrator 实现

参考 `orchestrator-agent.md`，这是纯 Python 逻辑，不需要 LLM。

### Step 5.4: 端到端测试

```bash
# 用 ngrok 暴露本地服务
ngrok http 8000

# 在 GitHub App 设置中填入 ngrok URL
# 在测试 repo 上创建 PR，观察 Argus 是否自动评论
```

### Step 5.5: 增量审查 + 反馈

参考 `user-experience.md`：
- ReviewStateStore：记录已审查的 commit 和已发出的评论
- 增量 diff 获取：只审查新 commit 引入的变更
- 去重：同一问题不重复评论
- 自动 resolve：用户修了代码后标记评论为已解决
- Emoji 反馈收集：thumbsup/thumbsdown

### Phase 5 交付检查清单

- [ ] GitHub App 认证 (JWT + installation token)
- [ ] Webhook 签名验证
- [ ] Orchestrator 事件路由
- [ ] 并行派发 Agent
- [ ] 结果聚合 + 格式化
- [ ] 发送 PR comment（带 trace 折叠 + 反馈提示）
- [ ] 增量审查（不重复评论）
- [ ] 端到端测试通过

---

## Phase 6: RAG 集成 + 评测

### 目标
接入 RAG 子系统，提升审查质量。运行评测，收集量化指标。

### Step 6.1: RAG 子系统

参考 `rag-pipeline.md`:
1. 先实现 Dense 检索 (OpenAI embedding + Qdrant)
2. 再加 BM25 + RRF 融合
3. 最后加 Reranker

### Step 6.2: 评测

参考 `metrics.md`:
1. 构建评测数据集
2. 跑评测脚本
3. 收集指标

### Phase 6 交付检查清单

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
| REPL | prompt_toolkit | 3.0+ |
| 终端渲染 | rich | 13.0+ |
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

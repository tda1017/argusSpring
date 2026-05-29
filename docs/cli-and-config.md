# CLI 模式与项目规范文件设计

## 设计理念

参考 Claude Code 的 `CLAUDE.md` 和 Codex 的项目规范机制：用户在项目根目录放一个 `argus.md` (或 `.argus/config.md`) 文件，Argus 自动读取并作为审查/分析的上下文注入。

这让 Argus 变成一个**项目感知的工具**，而不是一个通用的"AI 审代码"。

## argus.md 规范文件

### 文件位置

按优先级查找:
1. `./argus.md` (项目根目录)
2. `./.argus/config.md`
3. `./ARGUS.md`

### 文件格式

```markdown
# Argus 项目规范

## 代码规范
- 使用 TypeScript strict mode
- 错误处理统一使用 Result 模式，不用 try/catch
- 所有 API 响应必须有明确的类型定义
- 数据库查询必须使用参数化查询

## 审查重点
- 检查是否有 N+1 查询
- 检查敏感信息是否硬编码
- 检查新增的 API 是否有权限校验

## 忽略规则
- 忽略 generated/ 目录下的文件
- 忽略 *.test.ts 中的 any 类型
- 忽略 migration 文件的格式

## CI/CD 上下文
- 构建工具: pnpm + turbo
- 常见失败原因: turbo cache 失效导致类型错误
- 如果 eslint 报错，优先检查是否是新增了 lint 规则
```

### 解析实现

```python
# src/config/project_rules.py
from pathlib import Path

ARGUS_FILE_NAMES = ["argus.md", ".argus/config.md", "ARGUS.md"]

def find_project_rules(repo_root: Path) -> str | None:
    """查找并读取项目规范文件"""
    for name in ARGUS_FILE_NAMES:
        path = repo_root / name
        if path.exists():
            return path.read_text()
    return None

def inject_rules_to_prompt(system_prompt: str, rules: str | None) -> str:
    """将项目规范注入 Agent 的 system prompt"""
    if not rules:
        return system_prompt
    return system_prompt + f"\n\n## Project-Specific Rules\n\n{rules}"
```

### 在 Agent 中的使用

Review Agent 和 CI/CD Agent 在初始化时自动读取 `argus.md`，注入到 system prompt 中:

```python
class ReviewAgent(BaseAgent):
    async def run(self, input: ReviewInput) -> ReviewOutput:
        # 自动加载项目规范
        rules = find_project_rules(input.repo_root)
        system = inject_rules_to_prompt(self.system_prompt, rules)

        # 规范内容也作为 RAG 的一部分索引
        if rules:
            self.rag.index_document("argus.md", rules, doc_type="rules")

        # ... 正常审查流程
```

## CLI 设计

### 入口模式

Argus 有两种使用模式：

| 模式 | 命令 | 说明 |
|------|------|------|
| **交互模式 (默认)** | `argus` | 启动 REPL，持续会话 |
| **One-shot 模式** | `argus review`、`argus diagnose` 等 | 执行单次任务后退出 |

**自动检测**：当 stdin 是管道时（如 `git diff | argus review`），自动进入 one-shot 模式；当 stdin 是终端时，`argus` 不带子命令启动 REPL。

### 交互模式 (REPL)

默认入口。输入 `argus` 进入持续会话，支持自然语言 + 斜杠命令。

```bash
# 启动交互模式
argus

# 指定 Provider
argus --provider openai --model gpt-4o
```

#### 启动流程

```
$ argus

  🔍 Argus — Interactive Code Review Assistant
  ─────────────────────────────────────────────
  📁 Repo:   myproject (main)
  🌿 Branch: feature/add-auth
  🔗 PR:     #42 — Add OAuth login
  🔴 CI:     2 checks failed

  Type naturally or use /help for commands.

argus>
```

REPL 启动时自动检测并显示：
- git 仓库和当前分支
- 关联的 PR（如果有）
- CI 构建状态（如果有）
- argus.md 是否存在

#### 交互方式

**自然语言：**

```
argus> 帮我看看这个 PR 的代码质量
⏳ 正在获取 PR #42 的 diff...
⏳ 正在审查代码变更...

📄 src/auth/oauth.py

  🔴 [Critical] Line 23: 硬编码的 client_secret
     OAuth client secret 直接写在代码里，应该用环境变量
     建议: client_secret = os.environ["OAUTH_CLIENT_SECRET"]

  🟡 [Warning] Line 45: 缺少 state 参数验证
     OAuth 回调没有验证 state 参数，可能被 CSRF 攻击

总结: 1 critical, 1 warning
```

**斜杠命令：**

```
argus> /review
# 等价于 "审查当前分支的代码变更"

argus> /review --staged
# 只审查暂存区的变更

argus> /diagnose
# 分析当前 PR 的 CI 失败

argus> /fix
# 基于最近一次审查结果生成修复补丁

argus> /context
# 显示当前检测到的上下文信息

argus> /clear
# 清除对话历史

argus> /help
# 显示帮助信息

argus> /exit
# 退出 REPL
```

**多轮对话：**

```
argus> 这个项目的测试覆盖率怎么样？
目前检测到 tests/ 目录下有 13 个测试文件...

argus> 帮我看看 auth 模块的测试有没有遗漏
⏳ 正在分析 src/auth/ 和 tests/test_auth.py...

以下场景没有被测试覆盖：
1. OAuth token 过期后的刷新流程
2. 无效 state 参数的拒绝
3. ...

argus> 帮我修一下第一个问题
⏳ 正在生成修复补丁...

--- a/tests/test_auth.py
+++ b/tests/test_auth.py
@@ -45,0 +46,15 @@
+async def test_oauth_token_refresh():
+    """Test OAuth token refresh when expired"""
+    ...

是否应用此补丁？[y/n]
```

#### 流式输出

LLM 响应实时流式输出到终端，使用 rich 库渲染：
- Markdown 格式化（代码块、表格、列表）
- 工具调用时显示 spinner
- 审查结果用颜色区分严重性

### One-shot 模式

保留所有原有子命令，向后兼容：

```bash
# 代码审查
argus review --repo owner/repo --pr 123
argus review --diff-file ./changes.diff
git diff --staged | argus review

# CI 日志分析
argus diagnose --repo owner/repo --run 456
argus diagnose --log-file ./build.log
gh run view --log-failed | argus diagnose

# 自动修复
argus fix --diff-file ./changes.diff         # 基于 diff 生成修复
argus fix --diagnosis ./diagnosis.json       # 基于诊断结果修复

# 索引项目（为 RAG 做准备）
argus index --path ./my-project

# 初始化规范文件
argus init  # 在当前目录生成 argus.md 模板

# 交互式配置 Provider
argus config

# 启动 Webhook 服务
argus serve --port 8000
```

### CLI 实现

```python
# src/cli.py
import click
import sys

@click.group(invoke_without_command=True)
@click.pass_context
def cli(ctx):
    """Argus - Interactive AI Code Review & CI/CD Assistant"""
    if ctx.invoked_subcommand is None:
        # 无子命令 → 启动 REPL
        from argus.repl.session import start_repl
        asyncio.run(start_repl())

@cli.command()
@click.option("--repo", help="GitHub repo (owner/repo)")
@click.option("--pr", type=int, help="PR number")
@click.option("--diff-file", type=click.Path(exists=True), help="Local diff file")
def review(repo, pr, diff_file):
    """审查代码变更"""
    if diff_file:
        diff = Path(diff_file).read_text()
    elif repo and pr:
        diff = fetch_pr_diff(repo, pr)
    else:
        # 从 stdin 读取 (支持 git diff | argus review)
        diff = click.get_text_stream("stdin").read()

    rules = find_project_rules(Path.cwd())
    agent = ReviewAgent(config=load_config())
    result = asyncio.run(agent.run(ReviewInput(diff=diff, rules=rules)))
    click.echo(format_review_output(result))

@cli.command()
@click.option("--repo", help="GitHub repo")
@click.option("--run", "run_id", type=int, help="GitHub Actions run ID")
@click.option("--log-file", type=click.Path(exists=True), help="Local log file")
def diagnose(repo, run_id, log_file):
    """分析 CI 构建失败"""
    if log_file:
        log = Path(log_file).read_text()
    elif repo and run_id:
        log = fetch_build_log(repo, run_id)
    else:
        log = click.get_text_stream("stdin").read()

    agent = CICDAgent(config=load_config())
    result = asyncio.run(agent.run(CICDInput(log=log)))
    click.echo(format_cicd_output(result))

@cli.command()
@click.option("--diff-file", type=click.Path(exists=True), help="Diff file to fix")
@click.option("--diagnosis", type=click.Path(exists=True), help="Diagnosis JSON file")
def fix(diff_file, diagnosis):
    """基于审查或诊断结果生成修复补丁"""
    agent = FixAgent(config=load_config())
    # ... 生成修复补丁并输出

@cli.command()
def init():
    """在当前目录创建 argus.md 模板"""
    template = """# Argus 项目规范

## 代码规范
- (在这里写你的编码规范)

## 审查重点
- (在这里写希望 Argus 重点关注的问题)

## 忽略规则
- (在这里写不需要审查的文件/目录)

## CI/CD 上下文
- (在这里写构建工具、常见失败原因等)
"""
    Path("argus.md").write_text(template)
    click.echo("Created argus.md - edit it to configure Argus for your project.")

@cli.command()
@click.option("--port", default=8000)
def serve(port):
    """启动 Webhook 服务 (GitHub App 模式)"""
    import uvicorn
    uvicorn.run("argus.main:app", host="0.0.0.0", port=port, reload=True)
```

### 输出格式

One-shot 模式输出示例:

```
$ argus review --diff-file ./fix-auth.diff

🔍 Argus Code Review
━━━━━━━━━━━━━━━━━━━━

📄 src/auth/login.py

  🔴 [Critical] Line 42: SQL Injection Risk
     password 参数直接拼接到 SQL 查询中
     建议: 使用参数化查询 cursor.execute("SELECT ...", (password,))

  🟡 [Warning] Line 67: Missing rate limiting
     登录接口没有频率限制，可能被暴力破解
     建议: 添加 rate limiter (如 slowapi)

  🔵 [Suggestion] Line 15: Unused import
     `from hashlib import sha256` 未被使用

━━━━━━━━━━━━━━━━━━━━
总结: 1 critical, 1 warning, 1 suggestion
建议: Request Changes
```

## 管道支持

支持 Unix 管道，可以和 git 配合:

```bash
# 审查暂存区的改动
git diff --staged | argus review

# 审查和 main 分支的差异
git diff main...HEAD | argus review

# 分析最近一次失败的 CI
gh run view --log-failed | argus diagnose
```

这种管道设计让 Argus 可以嵌入到任何工作流中，不强制依赖 GitHub App。

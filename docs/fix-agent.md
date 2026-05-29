# Fix Agent 详细设计

## 定位

Fix Agent 是 Argus 的**自动修复 Agent**。它接收 Review Agent 或 CI/CD Agent 的诊断结果，读取相关代码上下文，生成精确的修复补丁。

Fix Agent 不独立发起分析——它依赖前置的诊断结果来确定"修什么"。它的职责是"怎么修"。

## 核心职责

```
1. 接收诊断结果（审查意见 或 CI 失败分析）
2. 读取需要修改的文件完整内容
3. 检索相关代码上下文（理解代码风格和模式）
4. LLM 生成修复补丁
5. 输出结构化的 patch（文件路径 + 原始内容 + 修复内容）
```

## System Prompt

```text
你是一个精确的代码修复助手。你的任务是基于代码审查意见或 CI/CD 诊断结果，生成最小化的修复补丁。

修复原则：
1. 最小变更：只修改需要修复的部分，不要顺手重构无关代码
2. 风格一致：遵循现有代码的命名规范、缩进风格、注释习惯
3. 不引入新问题：修复不能破坏现有功能
4. 一个 patch 一个问题：每个修复补丁只解决一个具体问题
5. 附带说明：解释每个修改的原因

输出格式：
- 按文件分组输出修复补丁
- 每个补丁包含：文件路径、原始代码片段、修复后代码片段、修复说明
- 如果某个问题无法自动修复（如需要架构调整），明确说明原因
```

## 输入/输出模型

### 输入

```python
@dataclass
class FixInput:
    diagnosis: ReviewOutput | CICDOutput    # 前置诊断结果
    file_contents: dict[str, str]           # 相关文件的完整内容 {path: content}
    repo_root: str                          # 仓库根目录
    rules: str | None                       # argus.md 项目规范
```

### 输出

```python
@dataclass
class FilePatch:
    path: str              # 文件路径
    original: str          # 原始内容片段（用于定位和验证）
    patched: str           # 修复后内容
    explanation: str       # 修复说明
    issue_ref: str         # 对应的诊断条目标识

@dataclass
class FixOutput:
    patches: list[FilePatch]
    summary: str           # 修复摘要
    unfixable: list[str]   # 无法自动修复的问题及原因
    confidence: float      # 0-1 整体置信度
```

## 工具

Fix Agent 使用以下工具（通过 Function Calling）：

| 工具 | 说明 |
|------|------|
| `get_file_content` | 获取仓库中指定文件的完整内容 |
| `search_codebase` | 语义搜索代码库，理解相关模式和约定 |

Fix Agent 的输出本身就是 patch，不需要 `apply_patch` 工具——patch 的应用由 REPL 层或 Orchestrator 层负责。

## 修复流程

```
                  诊断结果 (ReviewOutput / CICDOutput)
                           │
                           ▼
                  ┌─────────────────┐
                  │ 提取需修复的文件  │
                  │ 路径列表         │
                  └────────┬────────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │ get_file_content │  读取完整文件
                  │ (每个文件)       │
                  └────────┬────────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │ search_codebase  │  检索相关上下文
                  │ (可选)           │  (理解代码风格)
                  └────────┬────────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │  LLM 生成补丁    │
                  │  (按问题逐个)    │
                  └────────┬────────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │  输出 FixOutput  │
                  │  (patches 列表)  │
                  └─────────────────┘
```

## 使用场景

### 场景 1: REPL 交互式修复

```
argus> /review
📄 src/auth/login.py
  🔴 [Critical] Line 42: SQL Injection Risk
  🟡 [Warning] Line 67: Missing rate limiting

argus> 帮我修一下 SQL 注入的问题
⏳ 正在读取 src/auth/login.py...
⏳ 正在生成修复补丁...

--- a/src/auth/login.py
+++ b/src/auth/login.py
@@ -40,3 +40,3 @@
-    query = f"SELECT * FROM users WHERE password = '{password}'"
-    cursor.execute(query)
+    cursor.execute("SELECT * FROM users WHERE password = %s", (password,))

修复说明: 将字符串拼接改为参数化查询，防止 SQL 注入。

是否应用此补丁？[y/n]
```

### 场景 2: CI 失败自动修复

```
argus> /diagnose
🔧 CI/CD Analysis
  根因: tests/test_api.py::test_create_user 中断言使用了旧的字段名
  建议: 将 response["username"] 改为 response["user_name"]

argus> /fix
⏳ 正在生成修复补丁...

--- a/tests/test_api.py
+++ b/tests/test_api.py
@@ -23,1 +23,1 @@
-    assert response["username"] == "test"
+    assert response["user_name"] == "test"

是否应用此补丁？[y/n]
```

### 场景 3: Webhook 模式自动修复（未来）

Orchestrator 可配置为在审查发现 Critical 问题时自动触发 Fix Agent，将修复以 PR suggestion 的形式提交。

## 设计决策

### 为什么 Fix Agent 是独立的 Agent？

1. **单一职责**：Review Agent 负责"发现问题"，Fix Agent 负责"解决问题"。合并会让 Review Agent 的 prompt 过于复杂。
2. **可选性**：不是所有场景都需要自动修复。独立 Agent 意味着用户可以只 review 不 fix。
3. **不同的上下文需求**：Review Agent 看的是 diff，Fix Agent 需要完整的文件内容。

### 为什么不让用户直接使用 Fix Agent？

Fix Agent 必须有前置的诊断结果作为输入。直接用 Fix Agent 没有意义——你得先知道问题是什么，才能修复。在 REPL 中，用户通过 `帮我修一下` 或 `/fix` 触发，ConversationAgent 会自动把最近的诊断结果传给 Fix Agent。

### 置信度

Fix Agent 输出的 `confidence` 字段表示修复的可靠性：
- `> 0.8`：高置信度，修复很可能正确（如简单的拼写修正、参数化查询替换）
- `0.5 - 0.8`：中置信度，建议用户仔细检查
- `< 0.5`：低置信度，问题可能需要人工判断

REPL 在展示补丁时会根据置信度给出不同的提示。

## 错误处理

| 场景 | 处理方式 |
|------|---------|
| 文件读取失败 | 跳过该文件，在 `unfixable` 中说明 |
| 诊断结果缺少文件路径 | LLM 尝试从诊断描述中推断，推断失败则标记为 unfixable |
| 生成的 patch 无法 apply | 返回 patch 但标记 confidence 为低 |
| 问题需要跨文件重构 | 在 `unfixable` 中说明，建议人工处理 |

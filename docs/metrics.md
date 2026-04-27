# 量化指标与评测方案

## 为什么需要提前定义指标

简历上写"基于 LLM 实现了代码审查"，面试官不知道好不好。写"在 10 个开源项目上测试，发现 73% 的真实问题"，面试官立刻知道你做的是真东西。

**指标不是做完了再凑的，是提前设计好、边开发边收集的。**

## 指标体系

### 一、Review Agent 指标

#### 1.1 审查准确率 (Precision)

```
Precision = 有效审查意见数 / 总审查意见数
```

- **有效审查意见**: 指出了真实存在的问题，或给出了有价值的改进建议
- **无效审查意见**: 误报、无意义的风格挑剔、与上下文不符的建议
- **目标**: Precision >= 70%

#### 1.2 审查覆盖率 (Recall)

```
Recall = Agent 发现的问题数 / PR 中实际存在的问题总数
```

- 需要人工标注"实际存在的问题"作为 ground truth
- 可以用已经被人类 reviewer 指出的问题作为基准
- **目标**: Recall >= 50% (不追求太高，宁缺毋滥)

#### 1.3 严重性准确率

```
Agent 标记为 critical 的问题中，真正是 critical 的比例
```

- **目标**: critical 的 Precision >= 80%
- 这个指标很重要——如果 critical 误报多，用户会失去信任

#### 1.4 评测方法

```python
# 构建评测数据集
# 从知名开源项目中收集"有实质性 review 意见的 PR"
EVAL_DATASET = [
    {
        "repo": "fastapi/fastapi",
        "pr": 1234,
        "human_issues": [
            {"file": "...", "line": 42, "description": "missing null check"},
            {"file": "...", "line": 87, "description": "SQL injection risk"},
        ]
    },
    # ... 50-100 个样本
]

def evaluate_review_agent(agent, dataset):
    results = []
    for sample in dataset:
        diff = fetch_pr_diff(sample["repo"], sample["pr"])
        agent_output = agent.run(diff)

        # 匹配 agent 发现的问题和人类标注的问题
        matches = match_issues(agent_output.issues, sample["human_issues"])
        results.append({
            "precision": matches["true_positive"] / len(agent_output.issues),
            "recall": matches["true_positive"] / len(sample["human_issues"]),
        })

    return aggregate(results)
```

### 二、CI/CD Agent 指标

#### 2.1 根因定位准确率

```
根因定位正确的次数 / 总分析次数
```

- "正确" = Agent 指出的根因与实际根因一致
- **目标**: >= 75%

#### 2.2 修复建议可用率

```
修复建议直接可用或稍作修改即可用的次数 / 总建议次数
```

- **目标**: >= 60%

#### 2.3 评测方法

```python
# 从开源项目收集"CI 失败 → 后续 commit 修复"的 pair
CICD_EVAL_DATASET = [
    {
        "repo": "...",
        "failed_run_id": 12345,
        "actual_root_cause": "Missing dependency in requirements.txt",
        "actual_fix_commit": "abc123",  # 人类的修复 commit
    },
    # ... 30-50 个样本
]

def evaluate_cicd_agent(agent, dataset):
    for sample in dataset:
        log = fetch_build_log(sample["repo"], sample["failed_run_id"])
        output = agent.run(log)

        # 人工判断: 根因是否匹配、修复建议是否可用
        # 这一步需要人工标注，无法完全自动化
```

### 三、系统级指标

#### 3.1 端到端响应时间

```
从 Webhook 到达 → PR comment 发出的总时间
```

- **目标**: < 30s (P95)
- 分解: Webhook 处理 < 1s, Agent 推理 < 25s, GitHub API < 4s

#### 3.2 Token 消耗

```
每次审查/分析消耗的平均 token 数
```

- **目标**: 平均 < 8000 tokens/次 (控制成本)
- 监控 input tokens 和 output tokens 分布

#### 3.3 错误率

```
Agent 调用失败(超时/异常)的次数 / 总调用次数
```

- **目标**: < 5%

## 评测数据集构建指南

### Review Agent 数据集

**来源**: GitHub 上活跃的、有高质量 code review 文化的开源项目

推荐项目:
- `fastapi/fastapi` — Python, 活跃的 review
- `vercel/next.js` — JavaScript/TypeScript
- `golang/go` — Go
- `rust-lang/rust` — Rust

**采集方法**:
```bash
# 用 GitHub API 获取有 review 意见的 PR
gh api repos/fastapi/fastapi/pulls \
  --jq '.[] | select(.review_comments > 0) | {number, title, review_comments}' \
  -X GET -F state=closed -F per_page=100
```

**标注标准**:
- 每个 PR 人工阅读 review 意见
- 标注每条意见的类型: bug / security / performance / style
- 标注每条意见是否是"真实问题"

### CI/CD Agent 数据集

**采集方法**:
```bash
# 找到"CI 失败后被修复"的 PR
# 即: 同一个 PR 先有 failed check，后来 checks 通过了
gh api repos/owner/repo/pulls/123/commits
# 找到 fix commit，对比 fix 前后的变更
```

## 简历上怎么写这些指标

不需要每个指标都写，挑最有说服力的 2-3 个:

```
示例 1 (偏准确率):
"在 50+ 开源 PR 上评测，代码审查准确率 73%，关键问题检出率 81%"

示例 2 (偏性能):
"端到端响应时间 P95 < 25s，单次审查平均消耗 6K tokens"

示例 3 (偏综合):
"在 10 个开源项目上测试，Review Agent 审查准确率 70%+，CI/CD Agent 根因定位准确率 78%，端到端延迟 < 30s"
```

**注意: 数字必须是真实测量的，不能编。面试官会追问细节。**

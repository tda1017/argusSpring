# RAG 检索评估结果

> 指标：recall@3  
> 数据集：`docs/rag-evaluation-dataset.csv`  
> 最后更新：2026-05-28

## 结论

已建立 20 条标注数据和可重复执行脚本。

当前小规模评估结果：**recall@3 = 1.0000（20/20）**。

这次已经在 **`prod` profile + Milvus** 下复跑，结果仍然是 **recall@3 = 1.0000（20/20）**。

默认测试不跑 RAG 评估，避免拖慢普通 `mvn test`。需要评估时手动执行：

```bash
scripts/evaluate-rag.sh
```

如果要切 Milvus：

```bash
ARGUS_RAG_EVAL_PROFILE=prod scripts/evaluate-rag.sh
```

## 数据集

| 项目 | 数量 |
|------|------|
| 查询样本 | 20 |
| 期望来源 | `docs/specs/requirements.md` / `docs/specs/design.md` / `docs/specs/tasks.md` |

## 结果归档

本次执行结果：

```text
rag_eval summary total=20 hits=20 recall@3=1.0000
```

## 简历约束

可以写“小规模标注集 recall@3=1.0”，并注明 **prod profile + Milvus 复跑也为 1.0**。

不能写“生产 RAG 命中率 90%+”，数据集太小，且只覆盖 `docs/specs`。

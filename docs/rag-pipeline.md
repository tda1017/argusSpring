# RAG 子系统设计

## 定位

RAG 是 Review Agent 和 CI/CD Agent 的**共享基础设施**。它为 Agent 提供项目感知能力——让 Agent 不只是看 diff，而是理解整个项目的上下文。

## 索引内容

| 数据源 | 用途 | 索引时机 |
|--------|------|---------|
| 项目代码库 | Review Agent 检索相关代码 | 首次连接 repo 时全量索引，后续增量更新 |
| 项目文档 | README, CONTRIBUTING, 架构文档等 | 同上 |
| 历史 PR | 过去的 review 意见和代码模式 | 定期同步 |
| 错误模式库 | CI/CD Agent 检索历史失败 | CI 失败时自动入库 |
| Lint/Config | .eslintrc, pyproject.toml 等配置规则 | 同代码库 |

## 检索架构

```
查询
  │
  ▼
┌──────────────────────────────────┐
│         Hybrid Retriever         │
│                                  │
│  ┌────────────┐  ┌────────────┐  │
│  │   Dense     │  │   BM25     │  │
│  │  (BGE-M3)  │  │  (关键词)   │  │
│  └─────┬──────┘  └─────┬──────┘  │
│        │               │         │
│        └───────┬───────┘         │
│                ▼                 │
│         RRF 融合排序              │
│                │                 │
│                ▼                 │
│        BGE-Reranker              │
│        (交叉编码器精排)            │
│                │                 │
│                ▼                 │
│         Top-K 结果               │
└──────────────────────────────────┘
```

### 为什么用混合检索

- **Dense (向量)**: 擅长语义相似匹配，"获取用户信息" 能匹配到 `fetchUserProfile()`
- **BM25 (关键词)**: 擅长精确匹配，搜 `NullPointerException` 不会被语义模型"理解"成别的东西
- **RRF 融合**: Reciprocal Rank Fusion，简单有效地合并两路结果
- **Reranker 精排**: 交叉编码器对 query-document pair 做更精细的相关性判断

### RRF 实现

```python
def reciprocal_rank_fusion(
    *ranked_lists: list[str],
    k: int = 60
) -> list[tuple[str, float]]:
    """
    RRF 融合多路检索结果
    k=60 是论文推荐值，对排名差异做平滑
    """
    scores: dict[str, float] = defaultdict(float)
    for ranked_list in ranked_lists:
        for rank, doc_id in enumerate(ranked_list):
            scores[doc_id] += 1.0 / (k + rank + 1)
    return sorted(scores.items(), key=lambda x: x[1], reverse=True)
```

## 代码索引策略

### 代码分块

代码不能按固定长度切分（会切断函数），需要按语义单元分块：

```python
def chunk_code_file(file_path: str, content: str) -> list[CodeChunk]:
    """按语义单元切分代码文件"""
    # 根据语言选择解析策略
    lang = detect_language(file_path)

    if lang in ("python", "javascript", "typescript", "java", "go"):
        # 用 tree-sitter 解析 AST，按函数/类切分
        return chunk_by_ast(content, lang)
    else:
        # fallback: 按空行分段，每段不超过 50 行
        return chunk_by_blank_lines(content, max_lines=50)

def chunk_by_ast(content: str, lang: str) -> list[CodeChunk]:
    """基于 AST 的语义分块"""
    tree = parse_ast(content, lang)
    chunks = []
    for node in tree.root_node.children:
        if node.type in ("function_definition", "class_definition", "method_definition"):
            chunks.append(CodeChunk(
                content=content[node.start_byte:node.end_byte],
                metadata={
                    "type": node.type,
                    "name": extract_name(node),
                    "start_line": node.start_point[0],
                    "end_line": node.end_point[0],
                }
            ))
    return chunks
```

### Embedding

- **模型**: BGE-M3（支持中英双语，Dense + Sparse 双编码）
- **维度**: 1024
- **推理**: 本地部署，CUDA fp16 加速
- **备选**: 如果不需要本地部署，用 OpenAI text-embedding-3-small（性价比高）

```python
class Embedder:
    def __init__(self, model_name: str = "BAAI/bge-m3", device: str = "cuda"):
        from FlagEmbedding import BGEM3FlagModel
        self.model = BGEM3FlagModel(model_name, use_fp16=True, device=device)

    def encode(self, texts: list[str]) -> dict:
        """返回 dense 和 sparse 两种表示"""
        return self.model.encode(
            texts,
            return_dense=True,
            return_sparse=True,
        )
```

### 向量存储

- **选择**: Qdrant
- **理由**: 支持 Dense + Sparse 混合检索（正好匹配 BGE-M3 的双编码输出），Python SDK 好用，本地模式不需要部署服务

```python
from qdrant_client import QdrantClient
from qdrant_client.models import VectorParams, SparseVectorParams

client = QdrantClient(path="./qdrant_data")  # 本地模式

client.create_collection(
    collection_name="codebase",
    vectors_config={
        "dense": VectorParams(size=1024, distance="Cosine"),
    },
    sparse_vectors_config={
        "sparse": SparseVectorParams(),
    },
)
```

## 索引管理

### 首次索引

```python
async def index_repository(repo: str, github: GitHubClient):
    """首次索引整个仓库"""
    files = await github.get_repo_tree(repo)

    # 过滤：只索引代码和文档文件
    indexable = [f for f in files if should_index(f.path)]

    for file in indexable:
        content = await github.get_file_content(repo, file.path)
        chunks = chunk_code_file(file.path, content)
        embeddings = embedder.encode([c.content for c in chunks])
        # 写入 Qdrant
        store_chunks(chunks, embeddings, metadata={"repo": repo, "file": file.path})
```

### 增量更新

```python
async def update_index_on_push(repo: str, changed_files: list[str]):
    """Push 事件触发增量更新"""
    for file_path in changed_files:
        # 删除旧的 chunks
        delete_chunks_by_file(repo, file_path)
        # 重新索引该文件
        content = await github.get_file_content(repo, file_path)
        chunks = chunk_code_file(file_path, content)
        embeddings = embedder.encode([c.content for c in chunks])
        store_chunks(chunks, embeddings, metadata={"repo": repo, "file": file_path})
```

## Retriever 接口

```python
class HybridRetriever:
    """供 Agent 工具调用的统一检索接口"""

    async def search(
        self,
        query: str,
        repo: str,
        top_k: int = 5,
        filter_type: str | None = None,  # "code" | "doc" | "error"
    ) -> list[RetrievalResult]:

        # 1. Dense + Sparse 双路检索
        query_embedding = self.embedder.encode([query])
        dense_results = self.qdrant.search(collection="codebase", vector=query_embedding["dense"], limit=top_k * 3)
        sparse_results = self.qdrant.search(collection="codebase", sparse_vector=query_embedding["sparse"], limit=top_k * 3)

        # 2. RRF 融合
        fused = reciprocal_rank_fusion(
            [r.id for r in dense_results],
            [r.id for r in sparse_results],
        )

        # 3. Reranker 精排
        candidates = [get_doc_by_id(doc_id) for doc_id, _ in fused[:top_k * 2]]
        reranked = self.reranker.rerank(query, candidates, top_k=top_k)

        return reranked
```

## 实现优先级

```
P0 (Phase 1 就需要):
  - Embedder 封装 (先支持 OpenAI embedding，降低部署门槛)
  - Qdrant 本地模式存储
  - 基础 Dense 检索
  - 代码文件分块 (先用 blank-line 分块，不依赖 tree-sitter)

P1 (Phase 2):
  - BM25 路 + RRF 融合
  - BGE-Reranker 精排
  - 基于 tree-sitter 的 AST 分块
  - 增量索引

P2 (Phase 3):
  - BGE-M3 本地部署 + CUDA 加速
  - 历史 PR / 错误模式库索引
  - 索引自动更新 (Webhook 触发)
```

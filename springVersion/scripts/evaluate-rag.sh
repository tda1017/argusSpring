#!/usr/bin/env bash
set -euo pipefail

DATASET="${ARGUS_RAG_EVAL_DATASET:-docs/rag-evaluation-dataset.csv}"
MIN_RECALL="${ARGUS_RAG_EVAL_MIN_RECALL:-0.0}"
PROFILE="${ARGUS_RAG_EVAL_PROFILE:-}"

ARGS=(
  -q
  -Dmaven.repo.local=../.m2
  -Dtest=RagRetrievalEvaluationTest
  -Dargus.rag.eval.enabled=true
  -Dargus.rag.eval.dataset="$DATASET"
  -Dargus.rag.eval.min-recall="$MIN_RECALL"
)

if [ -n "$PROFILE" ]; then
  ARGS+=("-Dspring.profiles.active=$PROFILE")
fi

mvn "${ARGS[@]}" test

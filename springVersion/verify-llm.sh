#!/bin/bash
# LLM 核心链路快速验证脚本
# 优先使用 DEEPSEEK_API_KEY，兼容旧的 OPENAI_API_KEY

set -e

API_KEY="${DEEPSEEK_API_KEY:-${OPENAI_API_KEY:-}}"

if [ -z "$API_KEY" ]; then
    echo "ERROR: 请先设置 DEEPSEEK_API_KEY"
    exit 1
fi

echo "========================================"
echo "Argus LLM 链路验证"
echo "Base URL: https://api.deepseek.com"
echo "Model:    deepseek-v4-pro"
echo "========================================"

export DEEPSEEK_API_KEY="$API_KEY"

# 运行集成测试
mvn test -Dtest=LlmLinkVerificationTest -DfailIfNoTests=false "$@"

echo ""
echo "========================================"
echo "验证完成，核心链路正常"
echo "========================================"

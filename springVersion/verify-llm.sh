#!/bin/bash
# LLM 核心链路快速验证脚本
# 自动读取本地 codex 配置中的 API Key 并注入环境变量

set -e

API_KEY=$(cat ~/.codex/auth.json | grep -o '"OPENAI_API_KEY": "[^"]*"' | cut -d'"' -f4)

if [ -z "$API_KEY" ]; then
    echo "ERROR: 无法从 ~/.codex/auth.json 读取 API Key"
    exit 1
fi

echo "========================================"
echo "Argus LLM 链路验证"
echo "Base URL: https://www.packyapi.com/v1"
echo "Model:    gpt-5.4"
echo "========================================"

export OPENAI_API_KEY="$API_KEY"

# 运行集成测试
mvn test -Dtest=LlmLinkVerificationTest -DfailIfNoTests=false "$@"

echo ""
echo "========================================"
echo "验证完成，核心链路正常"
echo "========================================"

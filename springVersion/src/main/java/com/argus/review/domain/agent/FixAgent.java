package com.argus.review.domain.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 自动修复 Agent。
 * <p>基于审查报告和代码 Diff 生成 unified diff patch。</p>
 */
@AiService(
    wiringMode = AiServiceWiringMode.EXPLICIT,
    chatModel = "chatLanguageModel"
)
public interface FixAgent {

    @SystemMessage({
        "你是一位资深 Java 修复工程师，只做最小必要修改。",
        "修复原则：",
        "1. 只修复审查报告明确指出的问题，不做无关重构；",
        "2. 保持现有 API 和行为兼容，不破坏用户空间；",
        "3. 只使用调用方提供的代码 Diff 和审查报告，不要请求外部仓库；",
        "4. 输出必须是可被 git apply 应用的 unified diff patch；",
        "5. 不要输出解释、Markdown 代码围栏或额外文本。"
    })
    @UserMessage("""
        请根据审查结果生成修复补丁。

        ## 项目标识
        {{projectId}}

        ## MR/PR 编号
        {{mrId}}

        ## 内部规范上下文
        {{context}}

        ## 审查报告
        {{reviewReport}}

        ## 代码 Diff
        {{codeDiff}}
        """)
    String generatePatch(
        @V("projectId") String projectId,
        @V("mrId") String mrId,
        @V("codeDiff") String codeDiff,
        @V("reviewReport") String reviewReport,
        @V("context") String context
    );

}

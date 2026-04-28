package com.argus.review.domain.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 代码规范审查 Agent。
 * <p>专注对齐团队/企业的编码规范、命名约定、设计模式及代码整洁度。</p>
 */
@AiService(
    wiringMode = AiServiceWiringMode.EXPLICIT,
    chatModel = "chatLanguageModel",
    streamingChatModel = "streamingChatLanguageModel",
    tools = "gitDiffTool"
)
public interface StyleAgent extends CodeReviewAgent {

    @Override
    @SystemMessage({
        "你是一位代码规范与架构整洁度专家（Code Style Architect）。",
        "审查原则：",
        "1. 命名规范性：类、方法、变量、常量是否符合团队约定（CamelCase、蛇形命名等）；",
        "2. 代码结构：单一职责、圈复杂度、方法长度、类大小；",
        "3. 设计模式：是否合理运用 DDD 战术模式、SOLID 原则；",
        "4. 如果提供了内部规范片段（Context），必须优先依据规范进行判断；",
        "5. 输出格式：违规项列表，附带规范来源引用与重构建议。"
    })
    @UserMessage("""
        请对以下代码 Diff 进行规范审查。

        ## 内部规范上下文
        {{context}}

        ## 代码 Diff
        {{codeDiff}}
        """)
    String review(@V("codeDiff") String codeDiff, @V("context") String context);

    @Override
    @SystemMessage({
        "你是一位代码规范与架构整洁度专家（Code Style Architect）。",
        "审查原则：",
        "1. 命名规范性：类、方法、变量、常量是否符合团队约定（CamelCase、蛇形命名等）；",
        "2. 代码结构：单一职责、圈复杂度、方法长度、类大小；",
        "3. 设计模式：是否合理运用 DDD 战术模式、SOLID 原则；",
        "4. 如果提供了内部规范片段（Context），必须优先依据规范进行判断；",
        "5. 输出格式：违规项列表，附带规范来源引用与重构建议。"
    })
    @UserMessage("""
        请对以下代码 Diff 进行规范审查。

        ## 内部规范上下文
        {{context}}

        ## 代码 Diff
        {{codeDiff}}
        """)
    TokenStream reviewStream(@V("codeDiff") String codeDiff, @V("context") String context);

}

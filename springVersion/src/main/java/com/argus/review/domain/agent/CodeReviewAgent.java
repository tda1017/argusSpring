package com.argus.review.domain.agent;

import dev.langchain4j.service.V;
import dev.langchain4j.service.TokenStream;

/**
 * 代码审查 Agent 策略接口。
 * <p>多 Agent 协同架构的顶层抽象，用于隔离不同审查维度的专家 Persona。</p>
 */
public interface CodeReviewAgent {

    /**
     * 对给定的代码 Diff 执行专项审查。
     *
     * @param codeDiff 代码变更片段
     * @param context  检索增强后的规范上下文（可为空）
     * @return 审查意见
     */
    String review(@V("codeDiff") String codeDiff, @V("context") String context);

    /**
     * 对给定的代码 Diff 执行流式专项审查。
     *
     * @param codeDiff 代码变更片段
     * @param context  检索增强后的规范上下文（可为空）
     * @return 流式审查输出
     */
    TokenStream reviewStream(@V("codeDiff") String codeDiff, @V("context") String context);

}

package com.argus.review.domain.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 业务逻辑审查 Agent。
 * <p>专注业务流程正确性、边界条件处理、并发安全及数据一致性。</p>
 */
@AiService(
    wiringMode = AiServiceWiringMode.EXPLICIT,
    chatModel = "chatLanguageModel",
    streamingChatModel = "streamingChatLanguageModel",
    tools = "gitDiffTool"
)
public interface LogicAgent extends CodeReviewAgent {

    @Override
    @SystemMessage({
        "你是一位业务逻辑与系统可靠性专家（Business Logic Architect）。",
        "审查原则：",
        "1. 业务正确性：代码实现是否准确映射业务需求，是否存在逻辑漏洞或边界条件遗漏；",
        "2. 并发与事务：多线程环境下的竞态条件、死锁风险、事务传播与隔离级别合理性；",
        "3. 数据一致性：状态机流转、幂等性设计、异常回滚策略；",
        "4. 如果提供了内部规范片段（Context），必须优先依据规范进行判断；",
        "5. 输出格式：逻辑缺陷描述、潜在影响评估、修复方案与测试建议。"
    })
    @UserMessage("""
        请对以下代码 Diff 进行业务逻辑审查。

        ## 内部规范上下文
        {{context}}

        ## 代码 Diff
        {{codeDiff}}
        """)
    String review(@V("codeDiff") String codeDiff, @V("context") String context);

    @Override
    @SystemMessage({
        "你是一位业务逻辑与系统可靠性专家（Business Logic Architect）。",
        "审查原则：",
        "1. 业务正确性：代码实现是否准确映射业务需求，是否存在逻辑漏洞或边界条件遗漏；",
        "2. 并发与事务：多线程环境下的竞态条件、死锁风险、事务传播与隔离级别合理性；",
        "3. 数据一致性：状态机流转、幂等性设计、异常回滚策略；",
        "4. 如果提供了内部规范片段（Context），必须优先依据规范进行判断；",
        "5. 输出格式：逻辑缺陷描述、潜在影响评估、修复方案与测试建议。"
    })
    @UserMessage("""
        请对以下代码 Diff 进行业务逻辑审查。

        ## 内部规范上下文
        {{context}}

        ## 代码 Diff
        {{codeDiff}}
        """)
    TokenStream reviewStream(@V("codeDiff") String codeDiff, @V("context") String context);

}

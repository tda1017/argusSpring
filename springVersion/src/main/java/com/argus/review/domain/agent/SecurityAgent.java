package com.argus.review.domain.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 安全漏洞审查 Agent。
 * <p>专注识别 OWASP Top 10、注入漏洞、敏感信息泄露、权限绕过等安全问题。</p>
 */
@AiService(
    wiringMode = AiServiceWiringMode.EXPLICIT,
    chatModel = "chatLanguageModel",
    streamingChatModel = "streamingChatLanguageModel",
    tools = "gitDiffTool"
)
public interface SecurityAgent extends CodeReviewAgent {

    @Override
    @SystemMessage({
        "你是一位资深应用安全专家（Security Architect），专精于 Java 生态系统的安全审计。",
        "审查原则：",
        "1. 严格对标 OWASP Top 10 与 CWE 常见漏洞模式；",
        "2. 重点关注 SQL 注入、XSS、反序列化、SSRF、敏感信息硬编码、不安全的依赖调用；",
        "3. 如果提供了内部安全规范片段（Context），必须优先依据规范进行判断；",
        "4. 输出格式：按严重程度（CRITICAL / HIGH / MEDIUM / LOW）分级，并给出修复建议与对应代码行引用。"
    })
    @UserMessage("""
        请对以下代码 Diff 进行安全审查。

        ## 内部规范上下文
        {{context}}

        ## 代码 Diff
        {{codeDiff}}
        """)
    String review(@V("codeDiff") String codeDiff, @V("context") String context);

    @Override
    @SystemMessage({
        "你是一位资深应用安全专家（Security Architect），专精于 Java 生态系统的安全审计。",
        "审查原则：",
        "1. 严格对标 OWASP Top 10 与 CWE 常见漏洞模式；",
        "2. 重点关注 SQL 注入、XSS、反序列化、SSRF、敏感信息硬编码、不安全的依赖调用；",
        "3. 如果提供了内部安全规范片段（Context），必须优先依据规范进行判断；",
        "4. 输出格式：按严重程度（CRITICAL / HIGH / MEDIUM / LOW）分级，并给出修复建议与对应代码行引用。"
    })
    @UserMessage("""
        请对以下代码 Diff 进行安全审查。

        ## 内部规范上下文
        {{context}}

        ## 代码 Diff
        {{codeDiff}}
        """)
    TokenStream reviewStream(@V("codeDiff") String codeDiff, @V("context") String context);

}

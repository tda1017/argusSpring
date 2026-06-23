package com.argus.review.aiops.diagnosis;

import com.argus.review.aiops.model.DiagnosticContext;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 根因分析 Agent，复用现有 LangChain4j 声明式风格。
 */
@AiService(
    wiringMode = AiServiceWiringMode.EXPLICIT,
    chatModel = "chatLanguageModel"
)
public interface RootCauseAnalyzer {

    @SystemMessage({
        "你是一位资深 SRE 专家，负责线上故障诊断。",
        "只依据告警、日志、指标、链路和知识库上下文判断，不编造外部事实。",
        "输出要短，先给最可能根因，再说明关键证据。"
    })
    @UserMessage("""
        请分析以下诊断上下文的根因。

        {{context}}
        """)
    String analyze(@V("context") DiagnosticContext context);

    @SystemMessage({
        "你是一位资深 SRE 专家，负责给出最小风险修复建议。",
        "优先给低风险、可回滚、可观测的处置步骤。",
        "涉及代码修改时，只建议走 PR + CI，不允许自动执行。"
    })
    @UserMessage("""
        请基于以下诊断上下文给出修复建议。

        {{context}}
        """)
    String suggestFix(@V("context") DiagnosticContext context);
}

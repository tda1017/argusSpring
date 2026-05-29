package com.argus.review.domain.agent;

/**
 * Agent 间传递的最小消息。
 *
 * @param from    发送方
 * @param to      接收方
 * @param intent  消息意图
 * @param payload 消息内容
 */
public record AgentMessage(
    AgentRole from,
    AgentRole to,
    String intent,
    String payload
) {}

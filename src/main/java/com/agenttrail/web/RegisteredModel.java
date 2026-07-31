package com.agenttrail.web;

import com.agenttrail.loop.model.ThinkingMode;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 一个可供 {@link AgentLoopExecutorFactory} 装配的模型：模型标识 + 对应的 {@link ChatModel}，
 * 以及这个模型交付思考过程的方式——不同模型这一点不一样（issue #20），装配时必须按模型分别
 * 指定，不能假设所有模型共用同一种 {@link ThinkingMode}。
 */
public record RegisteredModel(String id, ChatModel chatModel, ThinkingMode thinkingMode) {
}

package com.wish.agentsession;

import com.wish.agent.JanusAgent;
import com.wish.llm.LLMChatClient;

/** Cached Janus agent + memory for a shell conversation id (same model). */
public record JanusAgentSession(LLMChatClient chatClient, JanusAgent agent) implements CachedAgentSession {}

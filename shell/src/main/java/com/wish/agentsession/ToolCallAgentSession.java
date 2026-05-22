package com.wish.agentsession;

import com.wish.agent.ToolCallAgent;
import com.wish.llm.LLMChatClient;

/** Cached tool-call agent + memory for a shell conversation id (same model). */
public record ToolCallAgentSession(LLMChatClient chatClient, ToolCallAgent agent) implements CachedAgentSession {}

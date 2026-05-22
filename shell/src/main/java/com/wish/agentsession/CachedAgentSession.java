package com.wish.agentsession;

import com.wish.agent.BaseAgent;
import com.wish.llm.LLMChatClient;

/** In-process cached agent and its chat client for a shell conversation. */
public interface CachedAgentSession {

    LLMChatClient chatClient();

    BaseAgent agent();
}

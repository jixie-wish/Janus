package com.wish.service;

import com.wish.agent.ToolCallAgent;
import com.wish.llm.LLMChatClient;

/** Cached agent + memory for a shell conversation id (same model). */
record AgentSession(LLMChatClient chatClient, ToolCallAgent agent) {}

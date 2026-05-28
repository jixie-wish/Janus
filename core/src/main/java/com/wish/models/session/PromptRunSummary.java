package com.wish.models.session;

/** Two-message snapshot of one prompt run for session memory. */
public record PromptRunSummary(String userRequest, String agentResult) {}

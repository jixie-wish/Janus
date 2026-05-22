package com.wish.service;

import com.wish.agentsession.CachedAgentSession;
import com.wish.agentsession.JanusAgentSession;
import com.wish.agent.JanusAgent;
import com.wish.llm.LLMChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

@Slf4j
@Service
public class JanusService extends ToolCallService {

    private final Path workspaceRoot;

    public JanusService(
            ApplicationContext applicationContext,
            @Value("${janus.agent.max-steps:10}") int maxSteps,
            @Value("${janus.workspace.root:workspace}") String workspaceRoot) {
        super(applicationContext, maxSteps);
        this.workspaceRoot = resolveWorkspaceRoot(workspaceRoot);
    }

    @Override
    protected void logStartup() {
        super.logStartup();
        log.info("Janus workspace root: {}", workspaceRoot);
    }

    @Override
    protected CachedAgentSession createSession(ChatModel chatModel) {
        LLMChatClient llmChatClient = new LLMChatClient(chatModel, Collections.emptyList());
        JanusAgent agent = new JanusAgent(llmChatClient, maxSteps, workspaceRoot, mcpTools);
        return new JanusAgentSession(llmChatClient, agent);
    }

    private static Path resolveWorkspaceRoot(String configured) {
        Path root = Path.of(configured).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create workspace directory: " + root, e);
        }
        return root;
    }
}

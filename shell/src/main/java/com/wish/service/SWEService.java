package com.wish.service;

import com.wish.agent.SWEAgent;
import com.wish.agent.base.ToolCallAgent;
import com.wish.llm.LLMChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Slf4j
@Service
public class SWEService extends ToolCallService {

    private final Path workspaceRoot;

    public SWEService(
            ApplicationContext applicationContext,
            @Value("${janus.agent.swe.max-steps:10}") int maxSteps,
            @Value("${janus.agent.swe.workspace-root:workspace/swe}") String workspaceRoot) {
        super(applicationContext, maxSteps);
        this.workspaceRoot = WorkspaceSupport.resolveWorkspaceRoot(workspaceRoot);
    }

    @Override
    protected void logStartup() {
        super.logStartup();
        log.info("SWE workspace root: {}", workspaceRoot);
    }

    @Override
    protected ToolCallAgent createAgent(LLMChatClient chatClient) {
        return new SWEAgent(chatClient, maxSteps, workspaceRoot, mcpTools);
    }
}

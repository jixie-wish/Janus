package com.wish.service;

import com.wish.agent.DataAnalysisAgent;
import com.wish.agent.base.ToolCallAgent;
import com.wish.llm.LLMChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Slf4j
@Service
public class DAService extends ToolCallService {

    private final Path workspaceRoot;

    public DAService(
            ApplicationContext applicationContext,
            @Value("${janus.agent.da.max-steps:10}") int maxSteps,
            @Value("${janus.agent.da.workspace-root:workspace/da}") String workspaceRoot) {
        super(applicationContext, maxSteps);
        this.workspaceRoot = WorkspaceSupport.resolveWorkspaceRoot(workspaceRoot);
    }

    @Override
    protected void logStartup() {
        super.logStartup();
        log.info("Data analysis workspace root: {}", workspaceRoot);
    }

    @Override
    protected ToolCallAgent createAgent(LLMChatClient chatClient) {
        return new DataAnalysisAgent(chatClient, maxSteps, workspaceRoot, mcpTools);
    }

}

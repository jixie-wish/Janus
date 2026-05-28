package com.wish.service;

import com.wish.agent.JanusAgent;
import com.wish.agent.base.ToolCallAgent;
import com.wish.llm.LLMChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Slf4j
@Service
public class JanusService extends ToolCallService {

    private final Path workspaceRoot;

    public JanusService(
            ApplicationContext applicationContext,
            @Value("${janus.agent.janus.max-steps:10}") int maxSteps,
            @Value("${janus.agent.janus.workspace-root:workspace/janus}") String workspaceRoot,
            @Value("${janus.agent.default.skills.dir:.agent/default/skills}") String defaultSkillsDir,
            @Value("${janus.agent.janus.skills.dir:.agent/janus/skills}") String agentSkillsDir) {
        super(applicationContext, maxSteps, defaultSkillsDir, agentSkillsDir);
        this.workspaceRoot = WorkspaceSupport.resolveWorkspaceRoot(workspaceRoot);
    }

    @Override
    protected void logStartup() {
        super.logStartup();
        log.info("Janus workspace root: {}", workspaceRoot);
    }

    @Override
    protected ToolCallAgent createAgent(LLMChatClient chatClient) {
        return new JanusAgent(chatClient, maxSteps, workspaceRoot, mcpTools, skillTools);
    }

}

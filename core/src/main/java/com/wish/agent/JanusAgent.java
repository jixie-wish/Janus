package com.wish.agent;

import com.wish.llm.LLMChatClient;
import com.wish.tools.AskHuman;
import com.wish.tools.PythonExecuteTool;
import com.wish.tools.StrReplaceEditor;
import com.wish.tools.TerminateTool;
import com.wish.tools.plan.PlanTool;

import java.nio.file.Path;
import java.util.List;

/**
 * Janus general-purpose agent aligned with OpenManus {@code app.agent.manus.Manus}
 * (local tools + optional MCP; browser automation not ported).
 */
public class JanusAgent extends ToolCallAgent {

    private static final String NAME = "Janus";
    private static final String DESCRIPTION =
            "A versatile agent that can solve various tasks using multiple tools including MCP-based tools";

    private static final String SYSTEM_PROMPT =
            "You are Janus, an all-capable AI assistant, aimed at solving any task presented by the user. "
                    + "You have various tools at your disposal that you can call upon to efficiently complete complex requests. "
                    + "Whether it's programming, information retrieval, file processing, web browsing, or human interaction "
                    + "(only for extreme cases), you can handle it all. "
                    + "The initial directory is: %s";

    private static final String NEXT_STEP_PROMPT = """
            Based on user needs, proactively select the most appropriate tool or combination of tools. For complex tasks, you can break down the problem and use different tools step by step to solve it. After using each tool, clearly explain the execution results and suggest the next steps.

            If you want to stop the interaction at any point, use the `terminate` tool/function call.
            """;

    private static List<Object> builtinTools() {
        return List.of(
                new PlanTool(),
                new PythonExecuteTool(),
                new StrReplaceEditor(),
                new AskHuman(),
                new TerminateTool());
    }

    public JanusAgent(LLMChatClient llmChatClient, int maxSteps, Path workspaceRoot) {
        this(llmChatClient, maxSteps, workspaceRoot, List.of());
    }

    public JanusAgent(LLMChatClient llmChatClient, int maxSteps, Path workspaceRoot, List<Object> mcpTools) {
        super(
                NAME,
                DESCRIPTION,
                SYSTEM_PROMPT.formatted(workspaceRoot.toAbsolutePath().normalize()),
                NEXT_STEP_PROMPT,
                llmChatClient,
                maxSteps,
                mcpTools,
                builtinTools());
    }
}

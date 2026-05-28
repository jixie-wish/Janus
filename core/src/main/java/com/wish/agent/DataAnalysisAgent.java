package com.wish.agent;

import com.wish.agent.base.ToolCallAgent;
import com.wish.llm.LLMChatClient;
import com.wish.tools.TerminateTool;
import com.wish.tools.analysis.ChartLlmConfig;
import com.wish.tools.analysis.DataAnalysisPythonExecuteTool;
import com.wish.tools.analysis.DataVisualizationTool;
import com.wish.tools.analysis.VisualizationPrepareTool;

import java.nio.file.Path;
import java.util.List;

public class DataAnalysisAgent extends ToolCallAgent {
    private static final String NAME = "DataAnalysis";
    private static final String DESCRIPTION = "An analytical agent that utilizes python and data visualization tools to solve diverse data analysis tasks";
    private static final String SYSTEM_PROMPT = """
            You are an AI agent designed to data analysis / visualization task. You have various tools at your disposal that you can call upon to efficiently complete complex requests.
            # Note:
            1. The workspace directory is: %s; Read / write file in workspace
            2. Generate analysis conclusion report in the end
            """;

    private static final String NEXT_STEP_PROMPT = """
            Based on user needs, break down the problem and use different tools step by step to solve it.
            # Note
            1. Each step select the most appropriate tool proactively (ONLY ONE).
            2. After using each tool, clearly explain the execution results and suggest the next steps.
            3. When observation with Error, review and fix it.
            """;

    private static final String SESSION_SUMMARY_SYSTEM_PROMPT = """
            You summarize one completed data-analysis task for long-term session memory.
            Use the same language as the user's request. Structure the summary clearly:
            - Current task goal
            - Data sources and operations performed
            - Key findings and metrics
            - Outputs produced (charts, files, paths when known)
            - Unresolved issues or suggested next steps, if any
            Keep it concise. Do not include raw logs or stack traces.
            """;

    public DataAnalysisAgent(LLMChatClient llmChatClient, int maxSteps, Path workspaceRoot) {
        this(llmChatClient, maxSteps, workspaceRoot, List.of());
    }

    private static List<Object> builtinTools(Path workspaceRoot, LLMChatClient llmChatClient) {
        ChartLlmConfig chartLlm = ChartLlmConfig.from(llmChatClient);
        return List.of(
                new DataAnalysisPythonExecuteTool(),
                new VisualizationPrepareTool(),
                new DataVisualizationTool(workspaceRoot, chartLlm),
                new TerminateTool());
    }

    public DataAnalysisAgent(LLMChatClient llmChatClient, int maxSteps, Path workspaceRoot, List<Object> mcpTools) {
        this(llmChatClient, maxSteps, workspaceRoot, mcpTools, List.of());
    }

    public DataAnalysisAgent(
            LLMChatClient llmChatClient,
            int maxSteps,
            Path workspaceRoot,
            List<Object> mcpTools,
            List<Object> skillsTools) {
        super(
                NAME,
                DESCRIPTION,
                SYSTEM_PROMPT.formatted(workspaceRoot.toAbsolutePath().normalize()),
                NEXT_STEP_PROMPT,
                SESSION_SUMMARY_SYSTEM_PROMPT,
                llmChatClient,
                maxSteps,
                mcpTools,
                skillsTools,
                builtinTools(workspaceRoot, llmChatClient));
    }
}

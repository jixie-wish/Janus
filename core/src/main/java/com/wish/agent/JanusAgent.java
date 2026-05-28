package com.wish.agent;

import com.wish.agent.base.ToolCallAgent;
import com.wish.llm.LLMChatClient;
import com.wish.tools.AskHuman;
import com.wish.tools.CreateChatCompletionTool;
import com.wish.tools.PythonExecuteTool;
import com.wish.tools.StrReplaceEditor;
import com.wish.tools.TerminateTool;
import com.wish.tools.plan.PlanTool;

import java.nio.file.Path;
import java.util.List;

/** Janus general-purpose agent (local tools + optional MCP). */
public class JanusAgent extends ToolCallAgent {

    private static final String NAME = "Janus";
    private static final String DESCRIPTION =
            "A versatile agent that can solve various tasks using multiple tools including MCP-based tools";

    private static final String SYSTEM_PROMPT =
            "You are Janus, an all-capable AI assistant, aimed at solving any task presented by the user. "
                    + "You have various tools at your disposal that you can call upon to efficiently complete complex requests. "
                    + "Whether it's programming, information retrieval, file processing, web browsing, or human interaction "
                    + "(only for extreme cases), you can handle it all. "
                    + "The initial directory is: %s. "
                    + "Deliver the final user-visible answer via create_chat_completion, then call terminate. "
                    + "Do not put the final answer only in assistant message text.";

    private static final String NEXT_STEP_PROMPT = """
            Select the single best action for this turn:
            - Use tools (python_execute, plan, etc.) when needed to gather facts or perform work.
            - When the user request is fully answered, call create_chat_completion(response=...) with the complete answer.
            - After create_chat_completion appears in the conversation, call terminate(status="success") only.
            - Do not repeat follow-up offers or assistant-only summaries after answering.
            """;

    private static final String SESSION_SUMMARY_SYSTEM_PROMPT = """
            You summarize one completed general assistant task for long-term session memory.
            Use the same language as the user's request. Structure the summary clearly:
            - Current task goal
            - Steps completed (tools used and main results)
            - Key findings or conclusions
            - Unresolved issues, if any
            - Suggested next steps, if any
            Keep it concise. Do not include raw logs or stack traces.
            """;

    private static List<Object> builtinTools() {
        return List.of(
                new PlanTool(),
                new PythonExecuteTool(),
                new StrReplaceEditor(),
                new AskHuman(),
                new CreateChatCompletionTool(),
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
                SESSION_SUMMARY_SYSTEM_PROMPT,
                llmChatClient,
                maxSteps,
                mcpTools,
                builtinTools());
    }
}

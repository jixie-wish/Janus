package com.wish.agent;

import com.wish.agent.base.ToolCallAgent;
import com.wish.llm.LLMChatClient;
import com.wish.tools.BashTool;
import com.wish.tools.StrReplaceEditor;
import com.wish.tools.TerminateTool;

import java.nio.file.Path;
import java.util.List;

public class SWEAgent extends ToolCallAgent {
    private static final int VIEW_WINDOW_LINES = 4;

    private static final String NAME = "SWE";
    private static final String DESCRIPTION =
            "an autonomous AI programmer that interacts directly with the computer to solve tasks.";

    private static final String SYSTEM_PROMPT = """
            SETTING: You are an autonomous programmer, and you're working directly in the command line with a special interface.
                        
            The special interface consists of a file editor that shows you %d lines of a file at a time.
            In addition to typical bash commands, you can also use specific commands to help you navigate and edit files.
            To call a command, you need to invoke it with a function call/tool call.
                        
            Please note that THE EDIT COMMAND REQUIRES PROPER INDENTATION.
            If you'd like to add the line '        print(x)' you must fully write that out, with all those spaces before the code! Indentation is important and code that is not indented correctly will fail and require fixing before it can be run.
                        
            RESPONSE FORMAT:
            Your shell prompt is formatted as follows:
            (Open file: <path>)
            (Current directory: <cwd>)
            bash-$
                        
            First, you should _always_ include a general thought about what you're going to do next.
            Then, for every response, you must include exactly _ONE_ tool call/function call.
                        
            Remember, you should always include a _SINGLE_ tool call/function call and then wait for a response from the shell before continuing with more discussion and commands. Everything you include in the DISCUSSION section will be saved for future reference.
            If you'd like to issue two commands at once, PLEASE DO NOT DO THAT! Please instead first submit just the first tool call, and then after receiving a response you'll be able to issue the second tool call.
            Note that the environment does NOT support interactive session commands (e.g. python, vim), so please do not invoke them.
            
            The workspace directory is: %s
            """;

    private static final String NEXT_STEP_PROMPT = "";

    public SWEAgent(LLMChatClient llmChatClient, int maxSteps, Path workspaceRoot) {
        this(llmChatClient, maxSteps, workspaceRoot, List.of());
    }

    private static List<Object> builtinTools(Path workspaceRoot) {
        Path workspace = workspaceRoot.toAbsolutePath().normalize();
        return List.of(new BashTool(workspace), new StrReplaceEditor(), new TerminateTool());
    }

    public SWEAgent(LLMChatClient llmChatClient, int maxSteps, Path workspaceRoot, List<Object> mcpTools) {
        super(
                NAME,
                DESCRIPTION,
                SYSTEM_PROMPT.formatted(
                        VIEW_WINDOW_LINES, workspaceRoot.toAbsolutePath().normalize()),
                NEXT_STEP_PROMPT,
                llmChatClient,
                maxSteps,
                mcpTools,
                builtinTools(workspaceRoot));
    }
}

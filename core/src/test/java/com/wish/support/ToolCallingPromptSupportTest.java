package com.wish.support;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallingPromptSupportTest {

    @Test
    void forToolExecutionFillsEmptyToolContext() {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        Prompt original = new Prompt(List.of(new UserMessage("hi")), options);

        Prompt execution = ToolCallingPromptSupport.forToolExecution(original);

        assertTrue(execution.getOptions() instanceof ToolCallingChatOptions);
        ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) execution.getOptions();
        assertFalse(toolOptions.getToolContext().isEmpty());
        assertTrue(toolOptions.getToolContext().containsKey("janus"));
    }
}

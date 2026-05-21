package com.wish.commands;

import com.wish.service.ToolCallService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

@Component
@CommandGroup(name = "tool call agent", prefix = "tool-call")
@Slf4j
public class ToolCallCommand {

    private final ToolCallService toolCallService;

    public ToolCallCommand(ToolCallService toolCallService) {
        this.toolCallService = toolCallService;
    }

    @Command(name = "request", description = "Run tool-call agent with prompt and model")
    public String request(
            @Option(longName = "prompt", shortName = 'p', description = "User prompt", required = true) String prompt,
            @Option(longName = "model", shortName = 'm', description = "Model: sensenova", defaultValue = "sensenova") String model) {
        return toolCallService.run(prompt, model);
    }
}

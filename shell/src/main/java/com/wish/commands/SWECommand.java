package com.wish.commands;

import com.wish.service.SWEService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@CommandGroup(name = "swe agent", prefix = "swe")
@Slf4j
public class SWECommand {

    private final SWEService sweService;

    public SWECommand(SWEService sweService) {
        this.sweService = sweService;
    }

    @Command(name = "request", description = "Run SWE agent with prompt and model")
    public String request(
            @Option(longName = "prompt", shortName = 'p', description = "User prompt", required = true) String prompt,
            @Option(longName = "model", shortName = 'm', description = "Model: sensenova", defaultValue = "sensenova") String model,
            @Option(
                            longName = "conversation-id",
                            shortName = 'c',
                            description = "Reuse in-process memory across requests (same shell process)")
                    String conversationId) {
        return sweService.run(prompt, model, conversationId);
    }

    @Command(name = "clear-session", description = "Drop cached memory and bash session for a conversation id")
    public void clearSession(
            @Option(longName = "conversation-id", shortName = 'c', description = "Session id", required = true)
                    String conversationId,
            @Option(longName = "model", shortName = 'm', description = "Model: sensenova", defaultValue = "sensenova")
                    String model) {
        sweService.clearSession(conversationId, model);
    }

    @Command(name = "list-models", description = "List all supported models")
    public Set<String> listModels() {
        return sweService.allModels();
    }
}

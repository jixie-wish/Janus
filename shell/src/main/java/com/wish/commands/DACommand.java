package com.wish.commands;

import com.wish.service.DAService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@CommandGroup(name = "data analysis agent", prefix = "da")
@Slf4j
public class DACommand {

    private final DAService daService;

    public DACommand(DAService daService) {
        this.daService = daService;
    }

    @Command(name = "request", description = "Run data analysis agent with prompt and model")
    public String request(
            @Option(longName = "prompt", shortName = 'p', description = "User prompt", required = true) String prompt,
            @Option(longName = "model", shortName = 'm', description = "Model: sensenova", defaultValue = "sensenova") String model,
            @Option(
                            longName = "conversation-id",
                            shortName = 'c',
                            description = "Reuse in-process memory across requests (same shell process)")
                    String conversationId) {
        return daService.run(prompt, model, conversationId);
    }

    @Command(name = "clear-session", description = "Drop cached memory for a conversation id")
    public void clearSession(
            @Option(longName = "conversation-id", shortName = 'c', description = "Session id", required = true)
                    String conversationId,
            @Option(longName = "model", shortName = 'm', description = "Model: sensenova", defaultValue = "sensenova")
                    String model) {
        daService.clearSession(conversationId, model);
    }

    @Command(name = "list-models", description = "List all supported models")
    public Set<String> listModels() {
        return daService.allModels();
    }
}

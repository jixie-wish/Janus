package com.wish.commands;

import com.wish.service.JanusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@CommandGroup(name = "janus agent", prefix = "janus")
@Slf4j
public class JanusCommand {

    private final JanusService janusService;

    public JanusCommand(JanusService janusService) {
        this.janusService = janusService;
    }

    @Command(name = "request", description = "Run Janus agent with prompt and model")
    public String request(
            @Option(longName = "prompt", shortName = 'p', description = "User prompt", required = true) String prompt,
            @Option(longName = "model", shortName = 'm', description = "Model: sensenova", defaultValue = "sensenova") String model,
            @Option(
                            longName = "conversation-id",
                            shortName = 'c',
                            description = "Reuse in-process memory across requests (same shell process)")
                    String conversationId) {
        return janusService.run(prompt, model, conversationId);
    }

    @Command(name = "clear-session", description = "Drop cached memory for a conversation id")
    public void clearSession(
            @Option(longName = "conversation-id", shortName = 'c', description = "Session id", required = true)
                    String conversationId,
            @Option(longName = "model", shortName = 'm', description = "Model: sensenova", defaultValue = "sensenova")
                    String model) {
        janusService.clearSession(conversationId, model);
    }

    @Command(name = "list-models", description = "List all supported models")
    public Set<String> listModels() {
        return janusService.allModels();
    }
}

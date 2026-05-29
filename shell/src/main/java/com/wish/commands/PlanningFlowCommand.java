package com.wish.commands;

import com.wish.service.PlanningFlowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@CommandGroup(name = "planning flow", prefix = "plan")
@Slf4j
public class PlanningFlowCommand {

    private final PlanningFlowService planningFlowService;

    public PlanningFlowCommand(PlanningFlowService planningFlowService) {
        this.planningFlowService = planningFlowService;
    }

    @Command(name = "request", description = "Run planning flow with prompt and model")
    public String request(
            @Option(longName = "prompt", shortName = 'p', description = "User prompt", required = true) String prompt,
            @Option(longName = "model", shortName = 'm', description = "Model: sensenova", defaultValue = "sensenova")
                    String model,
            @Option(
                            longName = "conversation-id",
                            shortName = 'c',
                            description = "Reuse in-process memory across requests (same shell process)")
                    String conversationId) {
        return planningFlowService.run(prompt, model, conversationId);
    }

    @Command(name = "clear-session", description = "Drop cached memory for a conversation id")
    public void clearSession(
            @Option(longName = "conversation-id", shortName = 'c', description = "Session id", required = true)
                    String conversationId,
            @Option(longName = "model", shortName = 'm', description = "Model: sensenova", defaultValue = "sensenova")
                    String model) {
        planningFlowService.clearSession(conversationId, model);
    }

    @Command(name = "list-models", description = "List all supported models")
    public Set<String> listModels() {
        return planningFlowService.allModels();
    }
}

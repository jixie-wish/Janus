package com.wish.tools.analysis;

import com.wish.llm.LLMChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

/** LLM credentials passed to the chart visualization Node subprocess. */
public record ChartLlmConfig(String baseUrl, String model, String apiKey) {

    public static ChartLlmConfig from(LLMChatClient client) {
        String apiKey = firstNonBlank(System.getenv("SPRING_AI_OPENAI_API_KEY"), System.getenv("OPENAI_API_KEY"));
        String baseUrl = firstNonBlank(
                System.getenv("SPRING_AI_OPENAI_BASE_URL"),
                System.getProperty("spring.ai.openai.base-url"),
                "https://api.openai.com/v1");
        String model = "gpt-4o";
        if (client != null) {
            ChatModel chatModel = client.getChatModel();
            ChatOptions options = chatModel != null ? chatModel.getDefaultOptions() : null;
            if (options != null && options.getModel() != null && !options.getModel().isBlank()) {
                model = options.getModel();
            }
        }
        return new ChartLlmConfig(baseUrl, model, apiKey != null ? apiKey : "");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

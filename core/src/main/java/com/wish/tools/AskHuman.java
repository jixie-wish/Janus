package com.wish.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.Console;
import java.util.Scanner;

/** Tool that asks for direct user input from terminal. */
public class AskHuman {

    @Tool(name = "ask_human", description = "Use this tool to ask human for help.")
    public String askHuman(
            @ToolParam(description = "The question you want to ask human.", required = true) String inquire) {
        Console console = System.console();
        if (console != null) {
            return console.readLine("Bot: %s%n%nYou: ".formatted(inquire), "").strip();
        }
        System.out.printf("Bot: %s%n%nYou: ", inquire);
        System.out.flush();
        try (Scanner scanner = new Scanner(System.in)) {
            if (scanner.hasNextLine()) {
                return scanner.nextLine().strip();
            }
            return "";
        }
    }
}

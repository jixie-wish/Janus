package com.wish.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Tool for running Python snippets with timeout protection. */
@Slf4j
public class PythonExecuteTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 5;

    @Tool(
            name = "python_execute",
            description = "Executes Python code string. Note: Only print outputs are visible, function return values "
                    + "are not captured. Use print statements to see results.")
    public String execute(
            @ToolParam(description = "The Python code to execute.", required = true) String code) {
        return execute(code, DEFAULT_TIMEOUT_SECONDS);
    }

    public String execute(String code, int timeoutSeconds) {
        List<String> command = resolvePythonCommand();
        if (command == null) {
            return formatResult("Python interpreter not found (tried python3, python)", false);
        }
        List<String> fullCommand = new ArrayList<>(command);
        fullCommand.add("-c");
        fullCommand.add(code);

        ProcessBuilder builder = new ProcessBuilder(fullCommand);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return formatResult("Execution timeout after " + timeoutSeconds + " seconds", false);
            }
            String output = readStream(process.getInputStream());
            if (process.exitValue() != 0 && output.isBlank()) {
                return formatResult("Process exited with code " + process.exitValue(), false);
            }
            return formatResult(output, process.exitValue() == 0);
        } catch (Exception e) {
            log.warn("python_execute failed: {}", e.getMessage());
            return formatResult(e.getMessage(), false);
        }
    }

    private static List<String> resolvePythonCommand() {
        if (commandExists("python3")) {
            return List.of("python3");
        }
        if (commandExists("python")) {
            return List.of("python");
        }
        return null;
    }

    private static boolean commandExists(String name) {
        ProcessBuilder probe = new ProcessBuilder(name, "--version");
        probe.redirectErrorStream(true);
        try {
            Process p = probe.start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readStream(java.io.InputStream stream) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String formatResult(String observation, boolean success) {
        return "{observation=%s, success=%s}".formatted(observation, success);
    }
}

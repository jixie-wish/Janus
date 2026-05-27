package com.wish.tools;

import com.wish.support.AgentRunSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Tool for executing commands in a persistent bash session. */
@Slf4j
public class BashTool {

    private static final String DESCRIPTION =
            """
            Execute a bash command in the terminal.
            * Long running commands: For commands that may run indefinitely, it should be run in the background and the output should be redirected to a file, e.g. command = `python3 app.py > server.log 2>&1 &`.
            * Interactive: If a bash command returns exit code `-1`, this means the process is not yet finished. The assistant must then send a second call to terminal with an empty `command` (which will retrieve any additional logs), or it can send additional text (set `command` to the text) to STDIN of the running process, or it can send command=`ctrl+c` to interrupt the process.
            * Timeout: If a command execution result says "Command timed out. Sending SIGINT to the process", the assistant should retry running the command in the background.
            """;

    private static final String SENTINEL = "<<exit>>";
    private static final double OUTPUT_DELAY_SECONDS = 0.2;
    private static final double TIMEOUT_SECONDS = 120.0;
    private static final String DEFAULT_CONVERSATION_ID = "__default__";

    private final Path workingDirectory;
    private final Map<String, SessionHolder> sessions = new ConcurrentHashMap<>();

    public BashTool() {
        this(null);
    }

    public BashTool(Path workingDirectory) {
        this.workingDirectory = workingDirectory != null ? workingDirectory.toAbsolutePath().normalize() : null;
    }

    @Tool(name = "bash", description = DESCRIPTION)
    public String execute(
            @ToolParam(
                            description =
                                    "The bash command to execute. Can be empty to view additional logs when previous exit code is `-1`. Can be `ctrl+c` to interrupt the currently running process.",
                            required = true)
                    String command,
            @ToolParam(description = "Restart the bash session.", required = false) Boolean restart)
            throws IOException {
        String conversationId = resolveConversationId();
        SessionHolder holder = sessions.computeIfAbsent(conversationId, ignored -> new SessionHolder());
        holder.lock.lock();
        try {
            if (Boolean.TRUE.equals(restart)) {
                stopSession(holder);
                startSession(holder);
                return "tool has been restarted.";
            }
            if (command == null) {
                throw new IllegalArgumentException("no command provided.");
            }
            ensureSession(holder);
            if ("ctrl+c".equals(command)) {
                holder.session.sendControlC();
                return formatCliResult(holder.session.drainOutput(), holder.session.drainError(), null);
            }
            try {
                CliOutput result = holder.session.run(command);
                return formatCliResult(result.output(), result.error(), null);
            } catch (BashSessionException e) {
                return formatCliResult(null, e.getMessage(), e.getSystem());
            }
        } finally {
            holder.lock.unlock();
        }
    }

    public void clearConversationSession(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        SessionHolder holder = sessions.remove(conversationId);
        if (holder == null) {
            return;
        }
        holder.lock.lock();
        try {
            stopSession(holder);
        } finally {
            holder.lock.unlock();
        }
    }

    private String resolveConversationId() {
        String conversationId = AgentRunSupport.currentConversationId();
        return conversationId == null || conversationId.isBlank() ? DEFAULT_CONVERSATION_ID : conversationId;
    }

    private void ensureSession(SessionHolder holder) throws IOException {
        if (holder.session == null || !holder.session.isAlive()) {
            startSession(holder);
        }
    }

    private void startSession(SessionHolder holder) throws IOException {
        holder.session = new BashSession(workingDirectory);
        holder.session.start();
    }

    private void stopSession(SessionHolder holder) {
        if (holder.session != null) {
            holder.session.stop();
            holder.session = null;
        }
    }

    private record CliOutput(String output, String error) {}

    private static final class SessionHolder {
        private final ReentrantLock lock = new ReentrantLock();
        private BashSession session;
    }

    private static String formatCliResult(String output, String error, String system) {
        if (system != null && !system.isBlank()) {
            return system;
        }
        if (error != null && !error.isBlank()) {
            return "Error: " + error;
        }
        return output != null ? output : "";
    }

    private static final class BashSessionException extends RuntimeException {
        private final String system;

        BashSessionException(String message, String system) {
            super(message);
            this.system = system;
        }

        String getSystem() {
            return system;
        }
    }

    private static final class BashSession {
        private final Path workingDirectory;
        private Process process;
        private BufferedWriter stdin;
        private InputStream stdout;
        private InputStream stderr;
        private final StringBuilder stdoutBuffer = new StringBuilder();
        private final StringBuilder stderrBuffer = new StringBuilder();
        private boolean timedOut;

        BashSession(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
        }

        void start() throws IOException {
            ProcessBuilder builder = new ProcessBuilder("/bin/bash");
            if (workingDirectory != null && Files.isDirectory(workingDirectory)) {
                builder.directory(workingDirectory.toFile());
            }
            process = builder.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdout = process.getInputStream();
            stderr = process.getErrorStream();
            timedOut = false;
        }

        boolean isAlive() {
            return process != null && process.isAlive() && !timedOut;
        }

        void stop() {
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }

        CliOutput run(String command) {
            if (!isAlive()) {
                if (process != null && !process.isAlive()) {
                    throw new BashSessionException(
                            "bash has exited with returncode " + process.exitValue(), "tool must be restarted");
                }
                throw new BashSessionException(
                        "timed out: bash has not returned in " + (int) TIMEOUT_SECONDS + " seconds and must be restarted",
                        null);
            }
            try {
                stdin.write(command);
                stdin.write("; echo '");
                stdin.write(SENTINEL);
                stdin.write("'\n");
                stdin.flush();
            } catch (IOException e) {
                throw new BashSessionException(e.getMessage(), null);
            }

            long deadline = System.nanoTime() + (long) (TIMEOUT_SECONDS * 1_000_000_000L);
            try {
                while (System.nanoTime() < deadline) {
                    Thread.sleep((long) (OUTPUT_DELAY_SECONDS * 1000));
                    readAvailable(stdout, stdoutBuffer);
                    readAvailable(stderr, stderrBuffer);
                    int sentinelIndex = stdoutBuffer.indexOf(SENTINEL);
                    if (sentinelIndex >= 0) {
                        String output = stripTrailingNewline(stdoutBuffer.substring(0, sentinelIndex));
                        String error = stripTrailingNewline(stderrBuffer.toString());
                        stdoutBuffer.setLength(0);
                        stderrBuffer.setLength(0);
                        return new CliOutput(output, error);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BashSessionException(e.getMessage(), null);
            }
            timedOut = true;
            throw new BashSessionException(
                    "timed out: bash has not returned in " + (int) TIMEOUT_SECONDS + " seconds and must be restarted",
                    null);
        }

        void sendControlC() throws IOException {
            stdin.write('\u0003');
            stdin.flush();
            sleepBriefly();
            readAvailable(stdout, stdoutBuffer);
            readAvailable(stderr, stderrBuffer);
        }

        String drainOutput() {
            readAvailable(stdout, stdoutBuffer);
            String output = stdoutBuffer.toString();
            stdoutBuffer.setLength(0);
            return stripTrailingNewline(output);
        }

        String drainError() {
            readAvailable(stderr, stderrBuffer);
            String error = stderrBuffer.toString();
            stderrBuffer.setLength(0);
            return stripTrailingNewline(error);
        }

        private static void readAvailable(InputStream stream, StringBuilder buffer) {
            try {
                while (stream.available() > 0) {
                    byte[] chunk = stream.readNBytes(stream.available());
                    buffer.append(new String(chunk, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // best-effort drain
            }
        }

        private static void sleepBriefly() {
            try {
                Thread.sleep((long) (OUTPUT_DELAY_SECONDS * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static String stripTrailingNewline(String text) {
            if (text == null || text.isEmpty()) {
                return text != null ? text : "";
            }
            if (text.endsWith("\n")) {
                return text.substring(0, text.length() - 1);
            }
            return text;
        }
    }
}

package com.wish.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Local filesystem editing tool (view/create/replace/insert/undo). */
public class StrReplaceEditor {

    private static final int SNIPPET_LINES = 4;
    private static final int MAX_RESPONSE_LEN = 16_000;
    private static final String TRUNCATED_MESSAGE =
            "<response clipped><NOTE>To save on context only part of this file has been shown to you. "
                    + "You should retry this tool after you have searched inside the file with `grep -n` "
                    + "in order to find the line numbers of what you are looking for.</NOTE>";

    private static final String DESCRIPTION = """
            Custom editing tool for viewing, creating and editing files
            * State is persistent across command calls and discussions with the user
            * If `path` is a file, `view` displays the result of applying `cat -n`. If `path` is a directory, `view` lists non-hidden files and directories up to 2 levels deep
            * The `create` command cannot be used if the specified `path` already exists as a file
            * If a `command` generates a long output, it will be truncated and marked with `<response clipped>`
            * The `undo_edit` command will revert the last edit made to the file at `path`
            Notes for using the `str_replace` command:
            * The `old_str` parameter should match EXACTLY one or more consecutive lines from the original file. Be mindful of whitespaces!
            * If the `old_str` parameter is not unique in the file, the replacement will not be performed. Make sure to include enough context in `old_str` to make it unique
            * The `new_str` parameter should contain the edited lines that should replace the `old_str`
            """;

    private final Map<String, Deque<String>> fileHistory = new ConcurrentHashMap<>();

    @Tool(name = "str_replace_editor", description = DESCRIPTION)
    public String execute(
            @ToolParam(
                    description = "The commands to run. Allowed options are: `view`, `create`, `str_replace`, `insert`, `undo_edit`.",
                    required = true)
            String command,
            @ToolParam(description = "Absolute path to file or directory.", required = true) String path,
            @ToolParam(description = "Required for `create`: content of the file to be created.", required = false)
            String file_text,
            @ToolParam(description = "Required for `str_replace`: string in `path` to replace.", required = false)
            String old_str,
            @ToolParam(
                    description = "For `str_replace`: replacement text. For `insert`: text to insert (required).",
                    required = false)
            String new_str,
            @ToolParam(
                    description = "Required for `insert`: `new_str` is inserted AFTER this line (0-based line index).",
                    required = false)
            Integer insert_line,
            @ToolParam(
                    description = "Optional for `view` on a file: [startLine, endLine], 1-based; use -1 as end for EOF.",
                    required = false)
            List<Integer> view_range)
            throws IOException {
        Path filePath = Path.of(path).toAbsolutePath().normalize();
        validatePath(command, filePath);

        return switch (command) {
            case "view" -> view(filePath, view_range);
            case "create" -> create(filePath, file_text);
            case "str_replace" -> strReplace(filePath, old_str, new_str);
            case "insert" -> insert(filePath, insert_line, new_str);
            case "undo_edit" -> undoEdit(filePath);
            default -> throw new ToolExecutionException(
                    "Unrecognized command '%s'. Allowed: view, create, str_replace, insert, undo_edit"
                            .formatted(command));
        };
    }

    private void validatePath(String command, Path path) throws IOException {
        if (!path.isAbsolute()) {
            throw new ToolExecutionException("The path " + path + " is not an absolute path");
        }
        if ("create".equals(command)) {
            if (Files.exists(path)) {
                throw new ToolExecutionException(
                        "File already exists at: " + path + ". Cannot overwrite files using command `create`.");
            }
            return;
        }
        if (!Files.exists(path)) {
            throw new ToolExecutionException(
                    "The path " + path + " does not exist. Please provide a valid path.");
        }
        if (Files.isDirectory(path) && !"view".equals(command)) {
            throw new ToolExecutionException(
                    "The path " + path + " is a directory and only the `view` command can be used on directories");
        }
    }

    private String view(Path path, List<Integer> viewRange) throws IOException {
        if (Files.isDirectory(path)) {
            if (viewRange != null && !viewRange.isEmpty()) {
                throw new ToolExecutionException(
                        "The `view_range` parameter is not allowed when `path` points to a directory.");
            }
            return viewDirectory(path);
        }
        return viewFile(path, viewRange);
    }

    private String viewDirectory(Path path) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Here's the files and directories up to 2 levels deep in " + path + ", excluding hidden items:");
        try (Stream<Path> walk = Files.walk(path, 2, FileVisitOption.FOLLOW_LINKS)) {
            walk.filter(p -> !isHiddenInTree(path, p))
                    .sorted()
                    .forEach(p -> lines.add(path.relativize(p).toString().replace('\\', '/')));
        }
        return maybeTruncate(String.join("\n", lines));
    }

    private static boolean isHiddenInTree(Path root, Path candidate) {
        if (candidate.equals(root)) {
            return false;
        }
        Path rel = root.relativize(candidate);
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (rel.getName(i).toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private String viewFile(Path path, List<Integer> viewRange) throws IOException {
        String fileContent = Files.readString(path);
        int initLine = 1;
        if (viewRange != null && !viewRange.isEmpty()) {
            if (viewRange.size() != 2) {
                throw new ToolExecutionException("Invalid `view_range`. It should be a list of two integers.");
            }
            String[] fileLines = fileContent.split("\n", -1);
            int nLines = fileLines.length;
            int init = viewRange.get(0);
            int fin = viewRange.get(1);
            if (init < 1 || init > nLines) {
                throw new ToolExecutionException(
                        "Invalid `view_range`: first element should be within [1, %d]".formatted(nLines));
            }
            if (fin != -1 && fin > nLines) {
                throw new ToolExecutionException(
                        "Invalid `view_range`: second element should be <= %d".formatted(nLines));
            }
            if (fin != -1 && fin < init) {
                throw new ToolExecutionException("Invalid `view_range`: second element should be >= first");
            }
            fileContent = fin == -1
                    ? String.join("\n", Arrays.asList(fileLines).subList(init - 1, nLines))
                    : String.join("\n", Arrays.asList(fileLines).subList(init - 1, fin));
            initLine = init;
        }
        return makeOutput(fileContent, path.toString(), initLine);
    }

    private String create(Path path, String fileText) throws IOException {
        if (fileText == null) {
            throw new ToolExecutionException("Parameter `file_text` is required for command: create");
        }
        Files.createDirectories(path.getParent() != null ? path.getParent() : Path.of("."));
        Files.writeString(path, fileText);
        fileHistory.computeIfAbsent(path.toString(), k -> new ArrayDeque<>()).addLast(fileText);
        return "File created successfully at: " + path;
    }

    private String strReplace(Path path, String oldStr, String newStr) throws IOException {
        if (oldStr == null) {
            throw new ToolExecutionException("Parameter `old_str` is required for command: str_replace");
        }
        String fileContent = Files.readString(path).replace("\t", "        ");
        String oldExpanded = oldStr.replace("\t", "        ");
        String newExpanded = newStr != null ? newStr.replace("\t", "        ") : "";

        int occurrences = countOccurrences(fileContent, oldExpanded);
        if (occurrences == 0) {
            throw new ToolExecutionException(
                    "No replacement was performed, old_str did not appear verbatim in " + path);
        }
        if (occurrences > 1) {
            String[] lines = fileContent.split("\n", -1);
            List<Integer> lineNums = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(oldExpanded)) {
                    lineNums.add(i + 1);
                }
            }
            throw new ToolExecutionException(
                    "No replacement was performed. Multiple occurrences of old_str in lines " + lineNums);
        }

        String newFileContent = fileContent.replace(oldExpanded, newExpanded);
        Files.writeString(path, newFileContent);
        fileHistory.computeIfAbsent(path.toString(), k -> new ArrayDeque<>()).addLast(fileContent);

        int replacementLine = fileContent.split(oldExpanded, -1)[0].split("\n", -1).length - 1;
        String[] newLines = newFileContent.split("\n", -1);
        int start = Math.max(0, replacementLine - SNIPPET_LINES);
        int end = Math.min(newLines.length, replacementLine + SNIPPET_LINES + newExpanded.split("\n", -1).length);
        String snippet = String.join("\n", Arrays.asList(newLines).subList(start, end));

        return "The file " + path + " has been edited. "
                + makeOutput(snippet, "a snippet of " + path, start + 1)
                + "Review the changes and make sure they are as expected. Edit the file again if necessary.";
    }

    private String insert(Path path, Integer insertLine, String newStr) throws IOException {
        if (insertLine == null) {
            throw new ToolExecutionException("Parameter `insert_line` is required for command: insert");
        }
        if (newStr == null) {
            throw new ToolExecutionException("Parameter `new_str` is required for command: insert");
        }
        String fileText = Files.readString(path).replace("\t", "        ");
        String newExpanded = newStr.replace("\t", "        ");
        List<String> fileLines = new ArrayList<>(Arrays.asList(fileText.split("\n", -1)));
        int nLines = fileLines.size();
        if (insertLine < 0 || insertLine > nLines) {
            throw new ToolExecutionException(
                    "Invalid `insert_line`: %d. It should be within [0, %d]".formatted(insertLine, nLines));
        }
        List<String> newStrLines = Arrays.asList(newExpanded.split("\n", -1));
        List<String> merged = new ArrayList<>();
        merged.addAll(fileLines.subList(0, insertLine));
        merged.addAll(newStrLines);
        merged.addAll(fileLines.subList(insertLine, nLines));

        String newFileText = String.join("\n", merged);
        List<String> snippetLines = new ArrayList<>();
        snippetLines.addAll(fileLines.subList(Math.max(0, insertLine - SNIPPET_LINES), insertLine));
        snippetLines.addAll(newStrLines);
        snippetLines.addAll(fileLines.subList(insertLine, Math.min(nLines, insertLine + SNIPPET_LINES)));

        Files.writeString(path, newFileText);
        fileHistory.computeIfAbsent(path.toString(), k -> new ArrayDeque<>()).addLast(fileText);

        return "The file " + path + " has been edited. "
                + makeOutput(
                        String.join("\n", snippetLines),
                        "a snippet of the edited file",
                        Math.max(1, insertLine - SNIPPET_LINES + 1))
                + "Review the changes and make sure they are as expected. Edit the file again if necessary.";
    }

    private String undoEdit(Path path) throws IOException {
        Deque<String> history = fileHistory.get(path.toString());
        if (history == null || history.isEmpty()) {
            throw new ToolExecutionException("No edit history found for " + path);
        }
        String oldText = history.removeLast();
        Files.writeString(path, oldText);
        return "Last edit to " + path + " undone successfully. " + makeOutput(oldText, path.toString());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String makeOutput(String fileContent, String fileDescriptor) {
        return makeOutput(fileContent, fileDescriptor, 1);
    }

    private static String makeOutput(String fileContent, String fileDescriptor, int initLine) {
        fileContent = maybeTruncate(fileContent).replace("\t", "        ");
        String[] lines = fileContent.split("\n", -1);
        int startLine = initLine;
        String numbered = IntStream.range(0, lines.length)
                .mapToObj(i -> "%6d\t%s".formatted(startLine + i, lines[i]))
                .collect(Collectors.joining("\n"));
        return "Here's the result of running `cat -n` on " + fileDescriptor + ":\n" + numbered + "\n";
    }

    private static String maybeTruncate(String content) {
        if (content.length() <= MAX_RESPONSE_LEN) {
            return content;
        }
        return content.substring(0, MAX_RESPONSE_LEN) + TRUNCATED_MESSAGE;
    }

    /** Thrown to surface tool errors to the model (Spring AI wraps exception message). */
    public static class ToolExecutionException extends RuntimeException {
        public ToolExecutionException(String message) {
            super(message);
        }
    }
}

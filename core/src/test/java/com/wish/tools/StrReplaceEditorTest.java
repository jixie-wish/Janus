package com.wish.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrReplaceEditorTest {

    @TempDir
    Path tempDir;

    private StrReplaceEditor editor;

    @BeforeEach
    void setUp() {
        editor = new StrReplaceEditor();
    }

    private String pathOf(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private String run(String command, Path target) throws IOException {
        return run(command, target, null, null, null, null, null);
    }

    private String run(
            String command,
            Path target,
            String fileText,
            String oldStr,
            String newStr,
            Integer insertLine,
            List<Integer> viewRange)
            throws IOException {
        return editor.execute(command, pathOf(target), fileText, oldStr, newStr, insertLine, viewRange);
    }

    @Test
    void create_writesFile() throws IOException {
        Path file = tempDir.resolve("new.txt");

        String result = run("create", file, "hello\nworld", null, null, null, null);

        assertEquals("hello\nworld", Files.readString(file));
        assertTrue(result.contains("File created successfully"));
    }

    @Test
    void create_requiresFileText() {
        Path file = tempDir.resolve("missing.txt");

        assertThrows(
                StrReplaceEditor.ToolExecutionException.class,
                () -> run("create", file, null, null, null, null, null));
    }

    @Test
    void create_rejectsExistingFile() throws IOException {
        Path file = tempDir.resolve("exists.txt");
        Files.writeString(file, "old");

        assertThrows(
                StrReplaceEditor.ToolExecutionException.class,
                () -> run("create", file, "new", null, null, null, null));
    }

    @Test
    void view_file_showsNumberedLines() throws IOException {
        Path file = tempDir.resolve("view.txt");
        Files.writeString(file, "alpha\nbeta");

        String result = run("view", file);

        assertTrue(result.contains("cat -n"));
        assertTrue(result.contains("alpha"));
        assertTrue(result.contains("beta"));
        assertTrue(result.contains("     1\talpha"));
        assertTrue(result.contains("     2\tbeta"));
    }

    @Test
    void view_file_withRange() throws IOException {
        Path file = tempDir.resolve("range.txt");
        Files.writeString(file, "one\ntwo\nthree");

        String result = run("view", file, null, null, null, null, List.of(2, 2));

        assertTrue(result.contains("     2\ttwo"));
        assertTrue(!result.contains("     1\tone"));
        assertTrue(!result.contains("three"));
    }

    @Test
    void view_directory_listsEntries() throws IOException {
        Path sub = tempDir.resolve("sub");
        Files.createDirectories(sub);
        Files.writeString(tempDir.resolve("a.txt"), "a");
        Files.writeString(sub.resolve("b.txt"), "b");

        String result = run("view", tempDir);

        assertTrue(result.contains("a.txt"));
        assertTrue(result.contains("sub"));
        assertTrue(result.contains("b.txt"));
    }

    @Test
    void strReplace_replacesUniqueMatch() throws IOException {
        Path file = tempDir.resolve("edit.txt");
        Files.writeString(file, "foo bar\nbaz");

        run("str_replace", file, null, "bar", "qux", null, null);

        assertEquals("foo qux\nbaz", Files.readString(file));
    }

    @Test
    void strReplace_failsWhenOldStrMissing() throws IOException {
        Path file = tempDir.resolve("nomatch.txt");
        Files.writeString(file, "content");

        assertThrows(
                StrReplaceEditor.ToolExecutionException.class,
                () -> run("str_replace", file, null, "nope", "x", null, null));
    }

    @Test
    void strReplace_failsWhenOldStrNotUnique() throws IOException {
        Path file = tempDir.resolve("dup.txt");
        Files.writeString(file, "aa\naa");

        StrReplaceEditor.ToolExecutionException ex = assertThrows(
                StrReplaceEditor.ToolExecutionException.class,
                () -> run("str_replace", file, null, "aa", "b", null, null));

        assertTrue(ex.getMessage().contains("Multiple occurrences"));
    }

    @Test
    void insert_insertsAfterLine() throws IOException {
        Path file = tempDir.resolve("insert.txt");
        Files.writeString(file, "line0\nline1");

        run("insert", file, null, null, "inserted", 1, null);

        assertEquals("line0\ninserted\nline1", Files.readString(file));
    }

    @Test
    void undoEdit_revertsLastChange() throws IOException {
        Path file = tempDir.resolve("undo.txt");
        Files.writeString(file, "original");

        run("str_replace", file, null, "original", "changed", null, null);
        assertEquals("changed", Files.readString(file));

        run("undo_edit", file);
        assertEquals("original", Files.readString(file));
    }

    @Test
    void undoEdit_failsWithoutHistory() throws IOException {
        Path file = tempDir.resolve("nohist.txt");
        Files.writeString(file, "x");

        assertThrows(StrReplaceEditor.ToolExecutionException.class, () -> run("undo_edit", file));
    }

    @Test
    void rejectsRelativePath() {
        assertThrows(
                StrReplaceEditor.ToolExecutionException.class,
                () -> editor.execute("view", "relative.txt", null, null, null, null, null));
    }

    @Test
    void rejectsUnknownCommand() throws IOException {
        Path file = tempDir.resolve("cmd.txt");
        Files.writeString(file, "x");

        assertThrows(StrReplaceEditor.ToolExecutionException.class, () -> run("unknown", file));
    }
}

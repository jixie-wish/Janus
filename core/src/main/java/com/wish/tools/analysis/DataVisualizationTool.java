package com.wish.tools.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Tool for rendering charts and optional insights from prepared metadata. */
@Slf4j
public class DataVisualizationTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SUBPROCESS_TIMEOUT_SECONDS = 120;

    private final Path workspaceRoot;
    private final ChartLlmConfig llmConfig;
    private final Path chartVisualizeDir;

    public DataVisualizationTool(Path workspaceRoot, ChartLlmConfig llmConfig) {
        this(workspaceRoot, llmConfig, resolveChartVisualizeDir());
    }

    DataVisualizationTool(Path workspaceRoot, ChartLlmConfig llmConfig, Path chartVisualizeDir) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.llmConfig = llmConfig;
        this.chartVisualizeDir = chartVisualizeDir;
    }

    @Tool(
            name = "data_visualization",
            description =
                    """
                    Visualize statistical chart or Add insights in chart with JSON info from visualization_preparation tool. You can do steps as follows:
                    1. Visualize statistical chart
                    2. Choose insights into chart based on step 1 (Optional)
                    Outputs:
                    1. Charts (png/html)
                    2. Charts Insights (.md)(Optional)""")
    public String visualize(
            @ToolParam(description = "file path of json info with \".json\" in the end", required = true) String jsonPath,
            @ToolParam(description = "Rendering format (html=interactive)", required = false) String outputType,
            @ToolParam(description = "visualize chart or add insights", required = false) String toolType,
            @ToolParam(description = "english(en) / chinese(zh)", required = false) String language) {
        String resolvedOutputType = outputType != null && !outputType.isBlank() ? outputType : "html";
        String resolvedToolType = toolType != null && !toolType.isBlank() ? toolType : "visualization";
        String resolvedLanguage = language != null && !language.isBlank() ? language : "en";
        try {
            log.info("data_visualization with {} in: {}", jsonPath, resolvedToolType);
            Path jsonFile = Path.of(jsonPath);
            if (!Files.isRegularFile(jsonFile)) {
                jsonFile = workspaceRoot.resolve(jsonPath);
            }
            List<Map<String, Object>> jsonInfo =
                    MAPPER.readValue(jsonFile.toFile(), new TypeReference<>() {});
            if ("visualization".equals(resolvedToolType)) {
                return dataVisualization(jsonInfo, resolvedOutputType, resolvedLanguage);
            }
            return addInsights(jsonInfo, resolvedOutputType);
        } catch (Exception e) {
            log.warn("data_visualization failed: {}", e.getMessage());
            return formatResult("Error: " + e.getMessage(), false);
        }
    }

    private String dataVisualization(List<Map<String, Object>> jsonInfo, String outputType, String language)
            throws Exception {
        List<String> csvPaths = getFilePaths(jsonInfo, "csvFilePath", null);
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < jsonInfo.size(); index++) {
            Map<String, Object> item = jsonInfo.get(index);
            String csvPath = csvPaths.get(index);
            List<Map<String, Object>> records = CsvRecords.read(Path.of(csvPath));
            String dictData = MAPPER.writeValueAsString(records);
            String fileName = Path.of(csvPath).getFileName().toString().replace(".csv", "");
            String chartTitle = String.valueOf(item.get("chartTitle"));
            Map<String, Object> vmindResult =
                    invokeVmind(dictData, chartTitle, fileName, outputType, "visualization", null, language);
            if (vmindResult.containsKey("error") && !vmindResult.containsKey("chart_path")) {
                errors.add("Error in " + csvPath + ": " + vmindResult.get("error"));
            } else {
                Map<String, Object> success = new LinkedHashMap<>(vmindResult);
                success.put("title", chartTitle);
                results.add(success);
            }
        }
        if (!errors.isEmpty()) {
            return formatObservation(
                    "# Error chart generated\n" + String.join("\n", errors) + "\n" + successOutputTemplate(results),
                    false);
        }
        return formatObservation(successOutputTemplate(results), true);
    }

    private String addInsights(List<Map<String, Object>> jsonInfo, String outputType) throws Exception {
        Path visualizationDir = workspaceRoot.resolve("visualization");
        List<String> chartPaths = getFilePaths(jsonInfo, "chartPath", visualizationDir);
        List<String> errors = new ArrayList<>();
        List<String> successes = new ArrayList<>();
        for (int index = 0; index < jsonInfo.size(); index++) {
            Map<String, Object> item = jsonInfo.get(index);
            if (!item.containsKey("insights_id")) {
                continue;
            }
            String chartPath = chartPaths.get(index);
            List<Number> insightsId = toNumberList(item.get("insights_id"));
            if (insightsId.isEmpty()) {
                continue;
            }
            String fileName = Path.of(chartPath).getFileName().toString().replace("." + outputType, "");
            Map<String, Object> vmindResult =
                    invokeVmind(null, null, fileName, outputType, "insight", insightsId, "en");
            if (vmindResult.containsKey("error") && !vmindResult.containsKey("chart_path")) {
                errors.add("Error in " + chartPath + ": " + vmindResult.get("error"));
            } else {
                successes.add(chartPath);
            }
        }
        String successTemplate = successes.isEmpty()
                ? ""
                : "# Charts Update with Insights\n" + String.join(",", successes);
        if (!errors.isEmpty()) {
            return formatObservation(
                    "# Error in chart insights:\n" + String.join("\n", errors) + "\n" + successTemplate, false);
        }
        return formatObservation(successTemplate, true);
    }

    private Map<String, Object> invokeVmind(
            String dictData,
            String chartDescription,
            String fileName,
            String outputType,
            String taskType,
            List<Number> insightsId,
            String language)
            throws IOException, InterruptedException {
        Map<String, Object> llmConfigMap = Map.of(
                "base_url", llmConfig.baseUrl(),
                "model", llmConfig.model(),
                "api_key", llmConfig.apiKey());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("llm_config", llmConfigMap);
        if (chartDescription != null) {
            params.put("user_prompt", chartDescription);
        }
        params.put("dataset", dictData != null ? dictData : List.of());
        params.put("file_name", fileName);
        params.put("output_type", outputType);
        params.put("insights_id", insightsId != null ? insightsId : List.of());
        params.put("task_type", taskType);
        params.put("directory", workspaceRoot.toString());
        params.put("language", language);

        String inputJson = MAPPER.writeValueAsString(params);
        ProcessBuilder builder = new ProcessBuilder("npx", "ts-node", "src/chartVisualize.ts");
        builder.directory(chartVisualizeDir.toFile());
        builder.redirectErrorStream(false);
        Process process = builder.start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(inputJson.getBytes(StandardCharsets.UTF_8));
        }
        boolean finished = process.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return Map.of("error", "Subprocess timeout after " + SUBPROCESS_TIMEOUT_SECONDS + " seconds");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() == 0) {
            return MAPPER.readValue(stdout, new TypeReference<>() {});
        }
        return Map.of("error", "Node.js Error: " + stderr);
    }

    private List<String> getFilePaths(List<Map<String, Object>> jsonInfo, String pathKey, Path baseDir)
            throws IOException {
        List<String> resolved = new ArrayList<>();
        for (Map<String, Object> item : jsonInfo) {
            String path = String.valueOf(item.get(pathKey));
            Path candidate = Path.of(path);
            if (Files.exists(candidate)) {
                resolved.add(candidate.toString());
            } else if (baseDir != null && Files.exists(baseDir.resolve(path))) {
                resolved.add(baseDir.resolve(path).toString());
            } else if (Files.exists(workspaceRoot.resolve(path))) {
                resolved.add(workspaceRoot.resolve(path).toString());
            } else {
                throw new IOException("No such file or directory: " + path);
            }
        }
        return resolved;
    }

    private static String successOutputTemplate(List<Map<String, Object>> results) {
        if (results.isEmpty()) {
            return "Is EMPTY!";
        }
        StringBuilder content = new StringBuilder();
        for (Map<String, Object> item : results) {
            content.append("## ").append(item.get("title")).append('\n');
            content.append("Chart saved in: ").append(item.get("chart_path"));
            if (item.containsKey("insight_path") && item.get("insight_path") != null && item.containsKey("insight_md")) {
                content.append('\n').append(item.get("insight_md"));
            } else {
                content.append('\n');
            }
        }
        return "Chart Generated Successful!\n" + content;
    }

    static Path resolveChartVisualizeDir() {
        String env = System.getenv("JANUS_CHART_VISUALIZE_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        Path[] candidates = {
            Path.of("core/chart-visualization"),
            Path.of("chart-visualization"),
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate) && Files.exists(candidate.resolve("src/chartVisualize.ts"))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return Path.of("core/chart-visualization").toAbsolutePath().normalize();
    }

    private static String formatObservation(String observation, boolean success) {
        return "{observation=%s, success=%s}".formatted(observation, success);
    }

    private static String formatResult(String observation, boolean success) {
        return formatObservation(observation, success);
    }

    @SuppressWarnings("unchecked")
    private static List<Number> toNumberList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Number> numbers = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Number number) {
                numbers.add(number);
            }
        }
        return numbers;
    }
}

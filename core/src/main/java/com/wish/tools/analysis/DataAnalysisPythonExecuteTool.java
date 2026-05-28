package com.wish.tools.analysis;

import com.wish.tools.PythonExecuteTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Python execution tool used by the data-analysis agent. */
public class DataAnalysisPythonExecuteTool {

    private static final int TIMEOUT_SECONDS = 5;

    private final PythonExecuteTool python = new PythonExecuteTool();

    @Tool(
            name = "python_execute",
            description =
                    "Execute Python code for in-depth data analysis / data report (task conclusion) / other normal task without direct visualization.")
    public String execute(
            @ToolParam(
                            description = "code type, data process / data report / others",
                            required = false)
                    String codeType,
            @ToolParam(
                            description =
                                    """
                                    Python code to execute.
                                    # Note
                                    1. The code should generate a comprehensive text-based report containing dataset overview, column details, basic statistics, derived metrics, timeseries comparisons, outliers, and key insights.
                                    2. Use print() for all outputs so the analysis (including sections like 'Dataset Overview' or 'Preprocessing Results') is clearly visible and save it also
                                    3. Save any report / processed files / each analysis result in workspace directory
                                    4. Data reports need to be content-rich, including your overall analysis process and corresponding data visualization.
                                    5. You can invoke this tool step-by-step to do data analysis from summary to in-depth with data report saved also""",
                            required = true)
                    String code) {
        return python.execute(code, TIMEOUT_SECONDS);
    }
}

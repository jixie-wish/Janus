package com.wish.tools.analysis;

import com.wish.tools.PythonExecuteTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Tool for preparing chart metadata and cleaned CSV inputs. */
public class VisualizationPrepareTool {

    private static final int TIMEOUT_SECONDS = 5;

    private final PythonExecuteTool python = new PythonExecuteTool();

    @Tool(
            name = "visualization_preparation",
            description =
                    "Using Python code to generates metadata of data_visualization tool. Outputs: 1) JSON Information. 2) Cleaned CSV data files (Optional).")
    public String execute(
            @ToolParam(
                            description = "code type, visualization: csv -> chart; insight: choose insight into chart",
                            required = true)
                    String codeType,
            @ToolParam(
                            description =
                                    """
                                    Python code for data_visualization prepare.
                                    ## Visualization Type
                                    1. Data loading logic
                                    2. Csv Data and chart description generate
                                    2.1 Csv data (The data you want to visulazation, cleaning / transform from origin data, saved in .csv)
                                    2.2 Chart description of csv data (The chart title or description should be concise and clear. Examples: 'Product sales distribution', 'Monthly revenue trend'.)
                                    3. Save information in json file.( format: {"csvFilePath": string, "chartTitle": string}[])
                                    ## Insight Type
                                    1. Select the insights from the data_visualization results that you want to add to the chart.
                                    2. Save information in json file.( format: {"chartPath": string, "insights_id": number[]}[])
                                    # Note
                                    1. You can generate one or multiple csv data with different visualization needs.
                                    2. Make each chart data esay, clean and different.
                                    3. Json file saving in utf-8 with path print: print(json_path)
                                    """,
                            required = true)
                    String code) {
        return python.execute(code, TIMEOUT_SECONDS);
    }
}

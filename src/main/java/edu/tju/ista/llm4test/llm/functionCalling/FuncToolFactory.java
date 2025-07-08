package edu.tju.ista.llm4test.llm.functionCalling;

import java.util.List;
import java.util.Map;


@Deprecated
public class FuncToolFactory {

    public static Map<String, Object>[] toToolsArray(List<FuncTool> tools) {
        Map<String, Object>[] toolsArray = new Map[tools.size()];
        for (int i = 0; i < tools.size(); i++) {
            toolsArray[i] = tools.get(i).toMap();
        }
        return toolsArray;
    }

    public static FuncTool createRootCauseOutputFuncTool() {
        FuncTool tool = new FuncTool("root_cause_analysis", "Analyze the root cause of a bug");
        tool.addParameter("root_cause", "string", "The root cause of the bug");
        tool.addParameter("bug_location", "string", "The location of the bug");
        tool.addParameter("bug_type", "string", "The type of the bug");
        tool.addParameter("fix_advice", "string", "The advice to fix the bug");
        tool.addParameter("report_bug", "boolean", "Whether to report the bug to Java Community");
        return tool;
    }

    /**
     * Create a FuncTool for JDK method-JavaDoc consistency verification
     * Check whether JDK method implementations potentially deviate from official JavaDoc
     * @return the FuncTool for JDK documentation conformance analysis
     */
    public static FuncTool createJDKDocConformanceFuncTool() {
        FuncTool tool = new FuncTool(
                "jdk_doc_conformance_check",
                "Verify consistency between JDK method implementations and their JavaDoc specifications"
        );

        tool.addParameter("inconsistencies", "string", "List of detected discrepancies");
        tool.addParameter("severity", "string", "Issue severity level(CRITICAL, WARNING, INFO, NO)");
        tool.addParameter("confidence_score", "string", "Analysis confidence rating (0.0-1.0), 1.0 being the most confident");

        return tool;
    }




}
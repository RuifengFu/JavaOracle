package edu.tju.ista.llm4test.llm.functionCalling;

import java.util.List;
import java.util.Map;
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
        tool.addParameter("report_bug", "boolean", "Whether to report the bug to JDK Community");
        return tool;
    }




}
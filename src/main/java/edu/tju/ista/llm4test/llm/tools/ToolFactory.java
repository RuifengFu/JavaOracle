package edu.tju.ista.llm4test.llm.tools;

import java.util.List;
import java.util.Map;

public class ToolFactory {

    public static Map<String, Object>[] toToolsArray(List<Tool<?>> tools) {
        return tools.stream()
                .map(Tool::toMap)
                .toArray(Map[]::new);
    }

}



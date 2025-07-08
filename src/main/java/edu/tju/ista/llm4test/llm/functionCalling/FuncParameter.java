package edu.tju.ista.llm4test.llm.functionCalling;

import java.util.HashMap;
import java.util.Map;

@Deprecated
public class FuncParameter {
    public String name;
    public String type;
    public String description;
    public FuncParameter(String name, String type, String description) {
        this.name = name;
        this.type = type;
        this.description = description;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        Map<String, Object> nestedMap = new HashMap<>();
        map.put(name, nestedMap);
        nestedMap.put("type", type);
        nestedMap.put("description", description);
        return map;
    }

    public String getName() {
        return name;
    }
}

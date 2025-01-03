package edu.tju.ista.llm4test.llm.functionCalling;

import java.util.ArrayList;
import java.util.HashMap;

// 表示Function定义
public class FuncTool {
    private String name;
    private String description;
    private ArrayList<FuncParameter> parameters = new ArrayList<>();

    public FuncTool(String name, String description) {
        this.name = name;
        this.description = description;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ArrayList<FuncParameter> getParameters() {
        return parameters;
    }

    public void addParameter(String name, String type, String description) {
        parameters.add(new FuncParameter(name, type, description));
    }

    public HashMap<String, Object> toMap() {
        /* Json Example
        {
            "type": "function",
            "function": {
                "name": "get_weather",
                "description": "Get weather of an location, the user shoud supply a location first",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "location": {
                            "type": "string",
                            "description": "The city and state, e.g. San Francisco, CA",
                        }
                    },
                    "required": ["location"]
                },
            }
        }
        */
        HashMap<String, Object> map = new HashMap<>();
        HashMap<String, Object> funcMap = new HashMap<>();
        HashMap<String, Object> paraMap = new HashMap<>();
        HashMap<String, Object> properties = new HashMap<>();
        String[] required = parameters.stream().map(FuncParameter::getName).toArray(String[]::new);

        map.put("type", "function");
        map.put("function", funcMap);

        funcMap.put("name", name);
        funcMap.put("description", description);
        funcMap.put("parameters", paraMap);

        paraMap.put("type", "object");
        paraMap.put("properties", properties);
        paraMap.put("required", required);

        parameters.forEach(p -> properties.putAll(p.toMap()));
        return map;
    }

    public HashMap<String, Object> parseResult(HashMap<String, Object> result) {
        try {
            var map = (HashMap<String, Object>) result.get(this.name);
            HashMap<String, Object> arguments = new HashMap<>();
            parameters.forEach(p -> {
                arguments.put(p.getName(), map.get(p.getName()));
            });
            return arguments;
        } catch (Exception e) {
            throw new RuntimeException("Error in parsing result");
        }
    }



}
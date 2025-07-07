package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.utils.CodeExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A tool to extract the first code block from a string, typically an LLM response.
 */
public class ExtractCodeTool implements Tool<String> {
    @Override
    public String getName() {
        return "extract_code";
    }

    @Override
    public String getDescription() {
        return "Extracts the last code block (e.g., ```java ... ```) from a given string. Returns only the code.";
    }

    @Override
    public List<String> getParameters() {
        return List.of("raw_string");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of("raw_string", "The string containing the code block, usually a raw response from an LLM.");
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of("raw_string", "string");
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        String rawString = (String) args.get("raw_string");
        if (rawString == null) {
            return ToolResponse.failure("Input string cannot be null.");
        }

        ArrayList<String> codeBlocks = CodeExtractor.extractCode(rawString);
        if (codeBlocks.isEmpty()) {
            // If no code block is found, maybe the raw string itself is the code.
            // This is a safe fallback for when the LLM behaves perfectly.
            return ToolResponse.success(rawString.trim());
        }

        // Return the last found code block, as LLMs often have preamble.
        return ToolResponse.success(codeBlocks.get(codeBlocks.size() - 1));
    }
} 
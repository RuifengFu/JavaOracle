package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

@Deprecated
public class CheckSimplicityTool implements Tool<Boolean> {

    private final OpenAI llm;
    private static final String PROMPT_TEMPLATE = """
You are a code simplicity evaluator. Your task is to determine if a given Java test case is "simple enough".
A simple test case should have the following characteristics:
- Minimal dependencies
- No complex logic (e.g., loops, complex conditions) unless absolutely necessary to reproduce the failure.
- Focuses on a single issue.
- Is very short and easy to read.

Based on the code below, answer with only "true" if you think the code is simple enough and further reduction is unlikely to be fruitful, or "false" if you believe it can be simplified further.

Do not provide any explanation, just the word "true" or "false".

Code:
```java
%s
```
""";

    public CheckSimplicityTool(OpenAI llm) {
        this.llm = Objects.requireNonNull(llm, "LLM instance cannot be null");
    }

    @Override
    public String getName() {
        return "check_simplicity";
    }

    @Override
    public String getDescription() {
        return "Checks if a given piece of code is simple enough and does not need further reduction. Returns true or false.";
    }

    @Override
    public List<String> getParameters() {
        return List.of("code");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of("code", "The Java code of the test case to evaluate.");
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of("code", "string");
    }

    @Override
    public ToolResponse<Boolean> execute(Map<String, Object> args) {
        String code = (String) args.get("code");
        if (code == null || code.isBlank()) {
            return ToolResponse.failure("Input code cannot be empty.");
        }

        try {
            String prompt = String.format(PROMPT_TEMPLATE, code);
            String response = llm.messageCompletion(prompt, 0.0, false); // Low temperature for deterministic answer

            // Clean up response to get just the boolean word
            String cleanedResponse = response.replaceAll("<thinking>[\\s\\S]*?</thinking>", "").trim().toLowerCase();

            if (cleanedResponse.contains("true")) {
                return ToolResponse.success(true);
            } else if (cleanedResponse.contains("false")) {
                return ToolResponse.success(false);
            } else {
                LoggerUtil.logExec(Level.WARNING, "CheckSimplicityTool received an ambiguous response: " + response);
                // Default to false to allow for more reduction attempts in case of ambiguity.
                return ToolResponse.success(false);
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Error executing CheckSimplicityTool: " + e.getMessage());
            return ToolResponse.failure(e.getMessage());
        }
    }
} 
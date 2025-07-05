package edu.tju.ista.llm4test.llm.agents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.agents.flow.AgentFlow;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import freemarker.template.TemplateException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class TestCaseMinimizationAgent extends Agent {

    // --- State ---
    private TestCase originalTestCase;
    private String workingDirectory;
    private List<Map<String, Object>> plan;
    private int currentStep = 0;

    // --- Configuration ---
    private final String baseWorkspace = "minimization_workspace";

    public TestCaseMinimizationAgent() {
        // The prompt for this agent will be generated dynamically.
        super("You are an expert test case minimizer.");
        this.LLM = OpenAI.R1;
    }

    /**
     * Minimizes the given test case within a specified parent workspace.
     * @param testCase The test case to minimize.
     * @param parentWorkspace The directory where the minimization workspace will be created.
     * @return A File object pointing to the minimized test case, or null if minimization failed.
     */
    public File minimize(TestCase testCase, Path parentWorkspace) {
        this.originalTestCase = testCase;
        this.currentStep = 0;

        try {
            setupWorkspace(parentWorkspace);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to setup workspace for test case minimization. " + e.getMessage());
            return null;
        }

        // Generate the initial plan
        try {
            String planJson = generatePlan();
            this.plan = parsePlan(planJson);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to generate or parse the minimization plan. " +  e.getMessage());
            return originalTestCase.getFile();
        }

        // Execute the plan step-by-step
        return executePlan();
    }

    private String generatePlan() throws TemplateException, IOException {
        Path originalPath = originalTestCase.getFile().toPath();
        String originalTestDirectory = originalPath.getParent().toString();

        // Directly get the source code and failure output from the TestCase object
        String sourceCode = originalTestCase.getSourceCode();
        String testFailureOutput = originalTestCase.getResult() != null ?
                originalTestCase.getResult().getOutput() : "No failure output available.";

        String prompt = PromptGen.generateTestCaseMinimizationPlanPrompt(
                sourceCode,
                testFailureOutput,
                this.workingDirectory,
                originalPath.toString()
        );
        
        return this.LLM.messageCompletion(prompt);
    }

    private List<Map<String, Object>> parsePlan(String json) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        // A simple way to clean the JSON from markdown code blocks
        json = json.trim().replace("```json", "").replace("```", "");
        return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>(){});
    }

    private File executePlan() {
        Map<String, Tool<?>> tools = registerTools();
        String originalFailureOutput = originalTestCase.getResult() != null ? originalTestCase.getResult().getOutput() : "";

        while (currentStep < plan.size()) {
            Map<String, Object> step = plan.get(currentStep);
            String toolName = (String) step.get("tool");
            
            LoggerUtil.logExec(Level.INFO, "Executing step " + (currentStep + 1) + ": " + toolName);
            
            if (toolName.equals("start_reduction_loop")) {
                // The reduction loop handles its own progression and returns the final file.
                return startReductionLoop((Map<String, Object>) step.get("parameters"));
            }

            Tool<?> tool = tools.get(toolName);
            if (tool == null) {
                LoggerUtil.logExec(Level.WARNING, "Tool not found: " + toolName);
                currentStep++;
                continue;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) step.get("parameters");

            // Execute the tool
            ToolResponse<?> response = tool.execute(params);
            
            // For simplicity, we just log the outcome. More complex logic can be added here.
            if (response.isSuccess()) {
                LoggerUtil.logExec(Level.INFO, "Tool '" + toolName + "' executed successfully. Result: " + response.getResult());

                // **** CRITICAL VERIFICATION STEP ****
                if (toolName.equals("jtreg_execute")) {
                    TestResult newResult = (TestResult) response.getResult();
                    String newFailureOutput = newResult.getOutput();

                    // For now, we do a simple check. A more robust check might compare stack traces.
                    if (newResult.isSuccess() || !newFailureOutput.contains(originalFailureOutput.lines().findFirst().orElse(""))) {
                         LoggerUtil.logExec(Level.SEVERE, "Verification failed! The test no longer produces the original failure after setup. Halting minimization.");
                         LoggerUtil.logExec(Level.SEVERE, "Original failure signature: " + originalFailureOutput.lines().findFirst().orElse("N/A"));
                         LoggerUtil.logExec(Level.SEVERE, "New output: " + newFailureOutput);
                         return null; // Stop execution
                    } else {
                        LoggerUtil.logExec(Level.INFO, "Verification successful! The test reproduces the original failure in the new environment.");
                    }
                }

            } else {
                LoggerUtil.logExec(Level.WARNING, "Tool '" + toolName + "' failed. Message: " + response.getMessage());
                // Decide if we should stop the execution on failure
                // For now, we continue
            }
            
            currentStep++;
        }
        return null; // Should be returned from the reduction loop
    }
    
    private File startReductionLoop(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        if (filePath == null) {
            LoggerUtil.logExec(Level.SEVERE, "No file_path provided for reduction loop.");
            return null;
        }

        LoggerUtil.logExec(Level.INFO, "Reduction loop started for file: " + filePath);

        final int maxIterations = 10; // To prevent infinite loops
        String lastSuccessfulCode;
        String originalFailureOutput = originalTestCase.getResult() != null ? originalTestCase.getResult().getOutput() : "";

        // Tools needed for the loop
        ReadFileTool readFileTool = new ReadFileTool();
        WriteFileTool writeFileTool = new WriteFileTool();
        JtregExecuteTool jtregExecuteTool = new JtregExecuteTool();

        // Initial read of the code
        ToolResponse<String> readResponse = readFileTool.execute(Map.of("file_path", filePath));
        if (!readResponse.isSuccess()) {
            LoggerUtil.logExec(Level.SEVERE, "Could not read initial file for reduction: " + filePath);
            return null;
        }
        String currentCode = readResponse.getResult();
        lastSuccessfulCode = currentCode;

        for (int i = 0; i < maxIterations; i++) {
            LoggerUtil.logExec(Level.INFO, "Reduction attempt #" + (i + 1));

            // 1. Propose a change using LLM
            String reducedCode;
            try {
                String prompt = PromptGen.generateTestCaseMinimizationReducePrompt(originalFailureOutput, currentCode);
                reducedCode = this.LLM.messageCompletion(prompt);
                // Simple cleanup of the LLM response
                reducedCode = reducedCode.replaceAll("```java", "").replaceAll("```", "").trim();

                if (reducedCode.equals(currentCode) || reducedCode.isEmpty()) {
                    LoggerUtil.logExec(Level.INFO, "LLM proposed no changes, or an empty response. Ending reduction.");
                    break;
                }

            } catch (Exception e) {
                LoggerUtil.logExec(Level.SEVERE, "Failed to generate reduction prompt or get LLM response. " + e.getMessage());
                break; // Stop if we can't get a valid reduction
            }

            // 2. Apply the change
            writeFileTool.execute(Map.of("file_path", filePath, "content", reducedCode));

            // 3. Verify the change
            ToolResponse<TestResult> execResponse = jtregExecuteTool.execute(Map.of("file_path", filePath));

            // 4. Decide
            if (!execResponse.isSuccess()) {
                // The test still fails. For this simple agent, we'll consider this a success.
                // A more advanced agent would compare the failure output.
                LoggerUtil.logExec(Level.INFO, "Reduction successful. Test still fails. Keeping changes.");
                currentCode = reducedCode;
                lastSuccessfulCode = currentCode;
            } else {
                // The test now passes, so the change was invalid. Revert.
                LoggerUtil.logExec(Level.INFO, "Reduction failed. Test now passes. Reverting changes.");
                writeFileTool.execute(Map.of("file_path", filePath, "content", currentCode));
            }
             LoggerUtil.logExec(Level.INFO, "Minimization progress:\n" + currentCode);
        }

        // Save the final, most reduced version of the code.
        LoggerUtil.logExec(Level.INFO, "Reduction loop finished. Final version of the code saved.");
        String minimizedFilePath = filePath.replace(".java", "_minimized.java");
        writeFileTool.execute(Map.of("file_path", minimizedFilePath, "content", lastSuccessfulCode));
        return new File(minimizedFilePath);
    }


    private Map<String, Tool<?>> registerTools() {
        Map<String, Tool<?>> tools = new HashMap<>();
        tools.put("copy_file", new CopyFileTool());
        tools.put("list_directory", new ListDirTool());
        tools.put("write_file", new WriteFileTool());
        tools.put("read_file", new ReadFileTool());
        tools.put("jtreg_execute", new JtregExecuteTool());
        return tools;
    }


    private void setupWorkspace(Path parentWorkspace) throws IOException {
        this.workingDirectory = parentWorkspace.resolve("minimization").toString();

        Path workspacePath = Paths.get(this.workingDirectory);
        Files.createDirectories(workspacePath);

        LoggerUtil.logExec(Level.INFO, "Created minimization workspace at: " + workspacePath);
    }


    public void close() {
        // Cleanup if needed
    }
} 
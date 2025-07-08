package edu.tju.ista.llm4test.llm.agents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.functionCalling.FuncTool;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * A dynamic, autonomous agent for test case minimization.
 * It follows a Think-Act-Observe loop to iteratively reduce a failing test case.
 */
public class TestCaseAgent extends Agent {

    private final ToolRegistry toolRegistry;
    private final List<String> history;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_ITERATIONS = 15; // Safety limit for the reduction loop

    public TestCaseAgent() {
        super("You are an expert test case minimizer. Your goal is to reduce a given Java test case to its minimal form while preserving the original failure.");
        this.LLM = OpenAI.R1;
        this.toolRegistry = new ToolRegistry();
        this.history = new ArrayList<>();
        registerTools();
    }

    private void registerTools() {
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new JtregExecuteTool());
        toolRegistry.register(new CheckSimplicityTool(this.LLM));
        toolRegistry.register(new CopyFileTool());
        toolRegistry.register(new ListDirTool());
    }

    /**
     * Main entry point for the agent's execution loop.
     * @param testCase The initial failing test case.
     * @param workspaceRoot The root directory for all operations.
     * @return The minimized source code, or the original code if minimization fails.
     */
    public String run(TestCase testCase, Path workspaceRoot) {
        // 1. Setup
        history.clear();
        Path testFilePath = setupWorkspace(testCase, workspaceRoot);
        if (testFilePath == null) {
            return testCase.getSourceCode(); // Return original if setup fails
        }

        String originalFailureOutput = testCase.getResult().getOutput();
        String currentCode = testCase.getSourceCode();

        addToHistory("=== TestCaseAgent Started ===");
        addToHistory("Target: " + testCase.name);
        addToHistory("Workspace: " + testFilePath.getParent().getFileName());

        // 2. Minimization Loop (Think-Act-Observe)
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            addToHistory("--- Iteration " + (i + 1) + "/" + MAX_ITERATIONS + " ---");

            // THINK: Decide on the next action
            String actionJson = think(currentCode, originalFailureOutput);
            if (actionJson == null || actionJson.isEmpty()) {
                addToHistory("LOOP: THINK failed, ending minimization");
                break;
            }

            // Check for finish action
            if (actionJson.contains("finish_minimization")) {
                addToHistory("LOOP: LLM decided minimization complete");
                break;
            }

            // ACT: Execute the action
            ToolResponse<?> toolResponse = act(actionJson);

            // OBSERVE: Process the result
            currentCode = observe(toolResponse, currentCode, originalFailureOutput, testFilePath);
        }

        addToHistory("=== Minimization Complete ===");
        return currentCode;
    }

    /**
     * Sets up the workspace by creating a directory and copying the initial test file.
     */
    private Path setupWorkspace(TestCase testCase, Path workspaceRoot) {
        try {
            Path testCaseWorkspace = workspaceRoot.resolve(testCase.name + "_minimization_ws_" + System.currentTimeMillis());
            Files.createDirectories(testCaseWorkspace);

            Path testFilePath = testCaseWorkspace.resolve(testCase.getFile().getName());
            Files.writeString(testFilePath, testCase.getSourceCode());
            
            // Change to the workspace directory for relative file operations
            System.setProperty("user.dir", testCaseWorkspace.toString());
            
            // Perform workspace preparation with function calling
            prepareWorkspaceWithFunctionCalling(testCase, testCaseWorkspace);
            
            return testFilePath;
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to set up workspace: " + e.getMessage());
            return null;
        }
    }

    /**
     * Prepares the workspace by analyzing the test case and copying required files using function calling.
     */
    private void prepareWorkspaceWithFunctionCalling(TestCase testCase, Path workspaceRoot) {
        try {
            // Get the original test directory path (relative)
            String originalTestPath = testCase.getFile().getParent().toString();
            
            // List the contents of the original test directory
            String directoryListing = getDirectoryListing(testCase.getFile().getParentFile());
            
            // Generate the workspace preparation prompt
            String prompt;
            try {
                prompt = PromptGen.generateWorkspacePreparationPrompt(
                    testCase.getSourceCode(),
                    testCase.getResult().getOutput(),
                    originalTestPath,
                    directoryListing
                );
            } catch (Exception e) {
                addToHistory("SETUP: Failed to generate prompt - " + e.getMessage());
                return;
            }
            
            addToHistory("SETUP: Analyzing test dependencies...");
            
            // Prepare function tools for the LLM
            List<Tool<?>> tools = new ArrayList<>();
            tools.add(toolRegistry.get("copy_file"));
            tools.add(toolRegistry.get("list_directory"));
            
            // Call LLM with function calling capability
            List<ToolCall> toolCalls = this.LLM.funcCall(prompt, tools);
            
            if (toolCalls != null && !toolCalls.isEmpty()) {
                addToHistory("SETUP: Found " + toolCalls.size() + " files to copy");
                
                // Execute each function call
                for (ToolCall toolCall : toolCalls) {
                    String toolName = toolCall.toolName;
                    Map<String, Object> args = toolCall.arguments;
                    
                    try {
                        // Parse the arguments JSON
                        // Convert paths to be relative to the original test directory
//                        if ("copy_file".equals(toolName)) {
//                            String sourcePath = (String) args.get("sourcePath");
//                            String destinationPath = (String) args.get("destinationPath");
//
//                            // Make source path absolute from original test directory
//                            Path originalTestDir = testCase.getFile().getParentFile().toPath();
//                            Path absoluteSourcePath = originalTestDir.resolve(sourcePath);
//                            args.put("sourcePath", absoluteSourcePath.toString());
//
//                            // Keep destination path relative to current workspace
//                            // (current directory is already set to workspace)
//                        }
                        
                        // Execute the tool
                        Tool<?> tool = toolRegistry.get(toolName);
                        ToolResponse<?> response = tool.execute(args);
                        
                        if (response.isSuccess()) {
                            addToHistory("SETUP: Copied " + toolName.replace("_", " "));
                        } else {
                            addToHistory("SETUP: Failed to copy " + toolName + " - " + response.getMessage());
                        }
                        
                    } catch (Exception e) {
                        addToHistory("SETUP: Error with " + toolName + " - " + e.getMessage());
                    }
                }
            } else {
                addToHistory("SETUP: No additional files needed");
            }
            
        } catch (Exception e) {
            addToHistory("SETUP: Error - " + e.getMessage());
            LoggerUtil.logExec(Level.WARNING, "Workspace preparation failed: " + e.getMessage());
        }
    }
    
    /**
     * Gets a directory listing as a formatted string
     */
    private String getDirectoryListing(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return "Directory not found or not accessible";
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            return "Unable to list directory contents";
        }
        
        StringBuilder listing = new StringBuilder();
        for (File file : files) {
            if (file.isDirectory()) {
                listing.append("[DIR] ").append(file.getName()).append("\n");
            } else {
                listing.append("[FILE] ").append(file.getName()).append(" (").append(file.length()).append(" bytes)\n");
            }
        }
        
        return listing.toString();
    }

    /**
     * The THINK step of the loop. The LLM analyzes the state and proposes the next action.
     */
    private String think(String currentCode, String originalFailureOutput) {
        try {
            String prompt = PromptGen.generateTestCaseMinimizationReducePrompt(originalFailureOutput, currentCode);
            addToHistory("THINK: Analyzing current code and proposing reduction...");

            String response = this.LLM.messageCompletion(prompt, 0.5, true);
            
            // Filter out thinking tags
            String filteredResponse = filterThinkingTags(response);
            
            String extractedJson = CodeExtractor.extractCode(filteredResponse).stream().findFirst().orElse(filteredResponse);

            addToHistory("THINK: Proposed action - " + (extractedJson.length() > 100 ? extractedJson.substring(0, 100) + "..." : extractedJson));
            return extractedJson;
        } catch (Exception e) {
            addToHistory("THINK: Error - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Filter out thinking tags from LLM response
     */
    private String filterThinkingTags(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }
        return response.replaceAll("<thinking>[\\s\\S]*?</thinking>", "").trim();
    }

    /**
     * The ACT step of the loop. Executes the tool call decided in the THINK step.
     */
    private ToolResponse<?> act(String actionJson) {
        try {
            Map<String, Object> actionMap = objectMapper.readValue(actionJson, new TypeReference<>() {});
            String toolName = (String) actionMap.get("tool_name");
            Map<String, Object> parameters = (Map<String, Object>) actionMap.get("parameters");

            if (toolName == null) {
                return ToolResponse.failure("No tool_name specified in the action.");
            }
            
            addToHistory("ACT: Executing " + toolName + " tool");

            Tool<?> tool = toolRegistry.get(toolName);
            return tool.execute(parameters);

        } catch (Exception e) {
            addToHistory("ACT: Parse error - " + e.getMessage());
            return ToolResponse.failure("Failed to parse or execute action: " + e.getMessage());
        }
    }

    /**
     * The OBSERVE step of the loop. Analyzes the tool's output and updates the state.
     */
    private String observe(ToolResponse<?> toolResponse, String previousCode, String originalFailure, Path testFilePath) {
        if (!toolResponse.isSuccess()) {
            addToHistory("OBSERVE: Tool failed, reverting - " + toolResponse.getMessage());
            // Revert the file content if the tool failed (e.g., write_file failed)
            try {
                Files.writeString(testFilePath, previousCode);
            } catch (IOException e) {
                addToHistory("OBSERVE: FATAL - Cannot revert file! " + e.getMessage());
            }
            return previousCode;
        }

        Object result = toolResponse.getResult();
        if (result instanceof TestResult) {
            // This was a jtreg_execute call
            TestResult testResult = (TestResult) result;
            if (testResult.isFail() && outputsAreSimilar(originalFailure, testResult.getOutput())) {
                // Good reduction: The test still fails with a similar error.
                try {
                    String newCode = Files.readString(testFilePath);
                    addToHistory("OBSERVE: Success - Test still fails correctly, reduction accepted");
                    return newCode; // The new code is now the current code
                } catch (IOException e) {
                    addToHistory("OBSERVE: Error reading new code, reverting - " + e.getMessage());
                    return previousCode;
                }
            } else {
                // Bad reduction: The test either passed or the failure changed too much.
                String status = testResult.isFail() ? "failure changed" : "test passed";
                addToHistory("OBSERVE: Failed - " + status + ", reverting reduction");
                try {
                    Files.writeString(testFilePath, previousCode);
                } catch (IOException e) {
                    addToHistory("OBSERVE: FATAL - Cannot revert file! " + e.getMessage());
                }
                return previousCode;
            }
        } else {
            // This was another tool call, like write_file. The result is just a confirmation message.
            addToHistory("OBSERVE: Tool completed successfully");
            // The state of the code is now whatever is on disk, which we assume is correct for the next step.
            try {
                return Files.readString(testFilePath);
            } catch (IOException e) {
                addToHistory("OBSERVE: Error reading file, reverting - " + e.getMessage());
                return previousCode;
            }
        }
    }

    /**
     * A simple heuristic to check if two failure logs are similar enough.
     * A real implementation might use more sophisticated comparison.
     */
    private boolean outputsAreSimilar(String original, String current) {
        if (original == null || current == null) return false;

        // Extract exception types
        String originalException = original.split("\n")[0];
        String currentException = current.split("\n")[0];

        return originalException.equals(currentException);
    }

    private void addToHistory(String message) {
        LoggerUtil.logExec(Level.INFO, "[TestCaseAgent] " + message);
        history.add(message);
    }
}
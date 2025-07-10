package edu.tju.ista.llm4test.llm.agents;


import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestExecutor;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import edu.tju.ista.llm4test.llm.tools.ToolCall;
import freemarker.template.TemplateException;
import java.util.HashMap;

/**
 * A dynamic, autonomous agent for test case minimization.
 * It follows a Think-Act-Observe loop to iteratively reduce a failing test case.
 */
public class TestCaseAgent extends Agent {

    private static final Tool<Void> ACTION_CONTINUE = new BasicTool("continue_minimization", "Continue to the next minimization iteration.");
    private static final Tool<Void> ACTION_FINISH = new BasicTool("finish_minimization", "Finish the minimization process as it is complete.");
    private static final Tool<Void> ACTION_REDO = new BasicTool("redo_simplification", "Abandon the current simplification and revert to the previous code.");

    private final ToolRegistry toolRegistry;
    private final List<String> history;
    private final List<String> feedbackHistory; // 新增：存储每次observe的反馈
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_ITERATIONS = 10; // Safety limit for the reduction loop
    private List<String> lastExecutedTools; // Track executed tools for observe method

    private String workspace_testcase_path;
    private Path workspace_root;
    private TestCase testCase;
    private TestCase minimizedTestCase;

    public TestCaseAgent() {
        super("You are an expert test case minimizer. Your goal is to reduce a given Java test case to its minimal form while preserving the original failure.");
        this.LLM = OpenAI.R1;
        this.toolRegistry = new ToolRegistry();
        this.history = new ArrayList<>();
        this.feedbackHistory = new ArrayList<>(); // 初始化反馈历史
        this.lastExecutedTools = new ArrayList<>(); // 初始化工具跟踪
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
    public TestCase run(TestCase testCase, Path workspaceRoot) {
        // 1. Setup


        this.testCase = testCase;
        history.clear();
        feedbackHistory.clear(); // 清空反馈历史
        Path testFilePath = setupWorkspace(testCase, workspaceRoot);
        if (testFilePath == null) {
            return testCase; // Return original if setup fails
        }

        String originalFailureOutput = testCase.getResult().getOutput();
        String currentCode = testCase.getSourceCode();



        addToHistory("=== TestCaseAgent Started ===");
        addToHistory("Target: " + testCase.name);
        addToHistory("Workspace: " + testFilePath.getParent().getFileName());
        addToHistory("MinimizationPath: " + testFilePath.toString());

        this.minimizedTestCase = new TestCase(testFilePath.toFile());
        minimizedTestCase.setOriginFile(testCase.getFile());
        minimizedTestCase.setResult(testCase.getResult());
        // 2. Minimization Loop (Think-Act-Observe)
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            addToHistory("--- Iteration " + (i + 1) + "/" + MAX_ITERATIONS + " ---");

            // THINK: Decide on the next action (with feedback from previous attempts)
            String previousFeedback = feedbackHistory.isEmpty() ? "" : String.join("\n", feedbackHistory);
            List<ToolCall> actionJson = think(currentCode, originalFailureOutput, previousFeedback, testFilePath);
            if (actionJson == null || actionJson.isEmpty()) {
                addToHistory("LOOP: THINK failed, ending minimization");
                break;
            }

            // Check for finish action
            if (actionJson.stream().anyMatch(toolCall -> toolCall.toolName.equals(ACTION_FINISH.getName()))) {
                addToHistory("LOOP: LLM decided minimization complete");
                break;
            }

            // ACT: Execute the action
            List<ToolResponse<?>> toolResponses = act(actionJson);

            // OBSERVE: Process the result and generate feedback for next iteration
            ObserveResult observeResult = observe(toolResponses, currentCode, originalFailureOutput, testFilePath);
            currentCode = observeResult.newCode;
            
            if (observeResult.nextAction != null && ACTION_FINISH.getName().equals(observeResult.nextAction)) {
                addToHistory("LOOP: LLM decided to finish minimization.");
                break;
            }

            // Store feedback for next iteration
            if (!observeResult.feedback.isEmpty()) {
                feedbackHistory.add("Iteration " + (i + 1) + ": " + observeResult.feedback);
                // Keep only last 3 feedbacks to avoid prompt getting too long
                if (feedbackHistory.size() > 3) {
                    feedbackHistory.remove(0);
                }
            }
        }

        addToHistory("=== Minimization Complete ===");
        var executor = new TestExecutor();
        var result = executor.executeTest(minimizedTestCase);
        minimizedTestCase.setResult(result);
        return minimizedTestCase;
    }

    /**
     * Sets up the workspace by creating a directory and copying the initial test file.
     */
    private Path setupWorkspace(TestCase testCase, Path workspaceRoot) {
        try {
            Path testCaseWorkspace = workspaceRoot.resolve(testCase.name + "_minimization_ws_" + System.currentTimeMillis()).toAbsolutePath();
            Files.createDirectories(testCaseWorkspace);
            workspace_root = testCaseWorkspace;
            Path testFilePath = testCaseWorkspace.resolve(testCase.getFile().getName());
            workspace_testcase_path = testFilePath.toString();
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
                        // Enhance paths for copy_file tool to ensure they are absolute
                        if ("copy_file".equals(toolName)) {
                            // Source path should be relative to the original test's directory
                            String sourcePath = (String) args.get("sourcePath");
                            if (sourcePath != null) {
                                Path originalTestDir = testCase.getFile().getParentFile().toPath();
                                Path absoluteSourcePath = originalTestDir.resolve(sourcePath);
                                if (Files.exists(absoluteSourcePath)) {
                                    args.put("sourcePath", absoluteSourcePath.toString());
                                }
                            }

                            // Destination path should be relative to the new workspace root
                            String destinationPath = (String) args.get("destinationPath");
                            if (destinationPath != null) {
                                Path destPathObj = workspace_root.resolve(destinationPath);
                                if (!Path.of(destinationPath).isAbsolute()) {
                                    // Ensure destination is within the workspace
                                    args.put("destinationPath", destPathObj.toString());
                                }
                            }
                        }
                        
                        // Execute the tool
                        Tool<?> tool = toolRegistry.get(toolName);
                        ToolResponse<?> response = tool.execute(args);
                        
                        if (!response.isSuccess()) {
                            addToHistory("SETUP: Failed to execute " + toolName + " - " + response.getFailMessage());
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
    private List<ToolCall> think(String currentCode, String originalFailureOutput, String previousFeedback, Path testFilePath) {
        try {
            // The CWD is set to the test's workspace directory. For the prompt, providing
            // the relative path (just the filename) is cleaner and sufficient for the LLM.
            String relativeTestPath = testFilePath.getFileName().toString();
            String prompt = PromptGen.generateTestCaseMinimizationReducePrompt(originalFailureOutput, currentCode, relativeTestPath, previousFeedback);
            addToHistory("THINK: Analyzing current code and proposing reduction...");

            // Prepare function tools for the LLM
            List<Tool<?>> tools = new ArrayList<>();
            tools.add(toolRegistry.get("write_to_file"));
            tools.add(toolRegistry.get("jtreg_execute"));
            tools.add(toolRegistry.get("check_simplicity"));
            tools.add(ACTION_CONTINUE);
            tools.add(ACTION_FINISH);
            tools.add(ACTION_REDO);


            List<ToolCall> toolCalls = this.LLM.funcCall(prompt, tools);

            addToHistory("THINK: Proposed " + (toolCalls != null ? toolCalls.size() : 0) + " actions");

            if (toolCalls == null) {
                toolCalls = new ArrayList<>();
            }

            boolean hasWriteToFile = toolCalls.stream().anyMatch(tc -> "write_to_file".equals(tc.toolName));
            boolean hasJtregExecute = toolCalls.stream().anyMatch(tc -> "jtreg_execute".equals(tc.toolName));

            if (hasWriteToFile && !hasJtregExecute) {
                addToHistory("THINK: Auto-adding jtreg_execute to verify changes");
                Map<String, Object> jtregParams = new HashMap<>();
                jtregParams.put("content", workspace_testcase_path);
                jtregParams.put("is_file_path", true);
                jtregParams.put("class_name", testCase.getName());
                toolCalls.add(new ToolCall("jtreg_execute", jtregParams));
                addToHistory("THINK: Now proposed " + toolCalls.size() + " actions");
            }

            return toolCalls;
        } catch (TemplateException | IOException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * The ACT step of the loop. Executes the tool call decided in the THINK step.
     */
    private List<ToolResponse<?>> act(List<ToolCall> actionJson) {
        List<ToolResponse<?>> responses = new ArrayList<>();
        List<String> executedTools = new ArrayList<>(); // Track executed tools

        try {
            if (actionJson == null || actionJson.isEmpty()) {
                responses.add(ToolResponse.failure("No actions to execute."));
                return responses;
            }

            // Execute all the requested tools
            for (ToolCall toolCall : actionJson) {
                String toolName = toolCall.toolName;
                Map<String, Object> parameters = toolCall.arguments;

                if (toolName == null) {
                    responses.add(ToolResponse.failure("No tool_name specified in the action."));
                    continue;
                }

                if (toolName.equals("write_to_file")) {
                    String path = (String) parameters.get("path");
                    Path filePath = workspace_root.resolve(path);
                    if (!Files.exists(Path.of(path))) {
                        parameters.put("path", filePath.toString());
                    }
                }

                addToHistory("ACT: Executing " + toolName + " tool");

                Tool<?> tool = toolRegistry.get(toolName);
                if (tool == null) {
                    responses.add(ToolResponse.failure("Tool not found: " + toolName));
                    continue;
                }


                ToolResponse<?> response = tool.execute(parameters);

                // Store tool name for observe method
                executedTools.add(toolName);
                responses.add(response);
            }

            // Store tool names in a thread-local or instance variable for observe method
            this.lastExecutedTools = executedTools;

            return responses;

        } catch (Exception e) {
            addToHistory("ACT: Parse error - " + e.getMessage());
            responses.add(ToolResponse.failure("Failed to parse or execute action: " + e.getMessage()));
            return responses;
        }
    }

    /**
     * The OBSERVE step of the loop. Analyzes the tool's output and updates the state.
     */
    private ObserveResult observe(List<ToolResponse<?>> toolResponses, String previousCode, String originalFailure, Path testFilePath) {
        if (toolResponses == null || toolResponses.isEmpty()) {
            addToHistory("OBSERVE: No tool responses to process.");
            return new ObserveResult(previousCode, "No tool responses to process.", ACTION_CONTINUE.getName());
        }

        String feedback = "";
        String currentCode = previousCode;
        boolean modificationApplied = false;

        // Process file operations first
        List<String> executedTools = this.lastExecutedTools;
        boolean fileWriteSuccess = true;
        for (int i = 0; i < executedTools.size(); i++) {
            if ("write_to_file".equals(executedTools.get(i))) {
                modificationApplied = true;
                if (!toolResponses.get(i).isSuccess()) {
                    fileWriteSuccess = false;
                    feedback += "File write operation failed. ";
                    break;
                }
            }
        }

        if (!fileWriteSuccess) {
            feedback += "Reverting code.";
            return new ObserveResult(previousCode, feedback, ACTION_CONTINUE.getName());
        }

        // Process test executions
        TestResult lastTestResult = null;
        for (int i = 0; i < executedTools.size(); i++) {
            if ("jtreg_execute".equals(executedTools.get(i))) {
                if (toolResponses.get(i).isSuccess() && toolResponses.get(i).getResult() instanceof TestResult) {
                    lastTestResult = (TestResult) toolResponses.get(i).getResult();
                } else {
                    feedback += "Test execution tool failed. ";
                }
            }
        }

        // Collect information for decision making
        if (modificationApplied && lastTestResult != null) {
            try {
                currentCode = Files.readString(testFilePath);
                
                // 检查代码是否真的有变化
                if (currentCode.equals(previousCode)) {
                    addToHistory("OBSERVE: Code unchanged despite modification attempt, finishing minimization");
                    feedback += "WARNING: Code unchanged despite modification attempt. Minimization appears complete. ";
                    return new ObserveResult(previousCode, feedback, ACTION_FINISH.getName());
                }
                
            } catch (IOException e) {
                feedback += "Failed to read updated code. ";
            }
            
            String newOutput = lastTestResult.getOutput();
            String newStatus = lastTestResult.isFail() ? "FAILED" : "PASSED";
            if (newOutput == null || newOutput.isBlank()) {
                newOutput = "[Test " + newStatus + " - No Output]";
            }
            feedback += "--- ORIGINAL FAILURE ---\n" + originalFailure.trim() + "\n";
            feedback += "--- NEW OUTPUT (Status: " + newStatus + ") ---\n" + newOutput.trim() + "\n";
        } else if (modificationApplied) {
            feedback += "Change applied but not verified by a test run. ";
        } else {
            feedback += "No code modification was attempted. ";
        }
        
        // Let LLM decide the next strategic step with error comparison
        String nextAction = decideNextAction(feedback, originalFailure, lastTestResult);

        // Handle the decision
        String finalCode = currentCode;
        if (ACTION_REDO.getName().equals(nextAction)) {
            finalCode = previousCode;
            try {
                Files.writeString(testFilePath, previousCode);
                feedback += "Reverting code. ";
            } catch (IOException e) {
                addToHistory("OBSERVE: FATAL - Cannot revert file on REDO! " + e.getMessage());
            }
        } else if (lastTestResult != null && lastTestResult.isFail() && !ACTION_FINISH.getName().equals(nextAction)) {
            // Keep the successful reduction
            finalCode = currentCode;
        } else if (modificationApplied && (lastTestResult == null || !lastTestResult.isFail())) {
            // Revert if test didn't fail as expected
            finalCode = previousCode;
            try {
                Files.writeString(testFilePath, previousCode);
                feedback += "Reverting code due to test behavior change. ";
            } catch (IOException e) {
                addToHistory("OBSERVE: FATAL - Cannot revert file! " + e.getMessage());
            }
        }
        
        return new ObserveResult(finalCode, feedback, nextAction);
    }

    private String decideNextAction(String feedback, String originalFailure, TestResult currentResult) {
        try {
            String prompt = PromptGen.generateTestCaseObserveAndDecidePrompt(feedback, originalFailure, currentResult);
            addToHistory("OBSERVE: Asking LLM for next step...");

            List<Tool<?>> decisionTools = List.of(ACTION_CONTINUE, ACTION_FINISH, ACTION_REDO);
            List<ToolCall> toolCalls = this.LLM.funcCall(prompt, decisionTools);

            if (toolCalls != null && !toolCalls.isEmpty()) {
                String actionName = toolCalls.get(0).toolName;
                addToHistory("OBSERVE: LLM chose action: " + actionName);
                return actionName;
            } else {
                addToHistory("OBSERVE: LLM failed to choose an action, defaulting to continue.");
                return ACTION_CONTINUE.getName();
            }
        } catch (Exception e) {
            addToHistory("OBSERVE: Error during LLM decision, defaulting to continue. " + e.getMessage());
            return ACTION_CONTINUE.getName();
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
        String filePrefix = workspace_testcase_path != null ? "[" + workspace_testcase_path + "] " : "";
        LoggerUtil.logExec(Level.INFO, "[TestCaseAgent] " + filePrefix + message);
        history.add(message);
    }
    /**
     * Result object for observe method containing both new code and feedback
     */
    private static class ObserveResult {
        final String newCode;
        final String feedback;
        final String nextAction;
        
        ObserveResult(String newCode, String feedback, String nextAction) {
            this.newCode = newCode;
            this.feedback = feedback != null ? feedback : "";
            this.nextAction = nextAction;
        }
    }
}
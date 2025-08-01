package edu.tju.ista.llm4test.llm.agents;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.utils.Log;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestExecutor;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.DebugUtils;
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
 * A dynamic, autonomous agent for test case Reduce.
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

    static {
        DebugUtils.getInstance().createPart("TestCaseAgent");
    }

    public TestCaseAgent() {
        super("You are an expert test case minimizer. Your goal is to reduce a given Java test case to its minimal form while preserving the original failure.");
        this.LLM = OpenAI.K2;
        this.toolRegistry = new ToolRegistry();
        this.history = new ArrayList<>();
        this.feedbackHistory = new ArrayList<>(); // 初始化反馈历史
        this.lastExecutedTools = new ArrayList<>(); // 初始化工具跟踪
        registerTools();
    }

    private void registerTools() {
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new JtregExecuteTool());
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
            // TODO fix!
            if (actionJson.isEmpty()) {
                addToHistory(Level.WARNING, "LOOP: THINK failed, jump this iteration");
                continue;
            }

            // Check for finish action
            if (actionJson.stream().anyMatch(toolCall -> toolCall.toolName.equals(ACTION_FINISH.getName()))) {
                addToHistory("LOOP: LLM decided minimization complete");
                break;
            }

            // ACT: Execute the action
            List<ToolResponse<?>> toolResponses = act(actionJson);

            // After a write, we must execute the test to observe the outcome.
            if (lastExecutedTools.contains("write_to_file")) {
                addToHistory("LOOP: Auto-executing test after file write.");
                Tool<?> jtregTool = toolRegistry.get("jtreg_execute");
                Map<String, Object> jtregParams = new HashMap<>();
                jtregParams.put("content", workspace_testcase_path);
                jtregParams.put("is_file_path", true);
                jtregParams.put("class_name", testCase.getName());
                ToolResponse<?> jtregResponse = jtregTool.execute(jtregParams);
                toolResponses.add(jtregResponse);
                this.lastExecutedTools.add(jtregTool.getName());
            }


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
        String originCode = testCase.getSourceCode();
        String reducedCode = minimizedTestCase.getSourceCode();
        if (originCode.equals(reducedCode)) {
            LoggerUtil.logExec(Level.WARNING, "Minimization did not change the test case: " + minimizedTestCase.getFile());
        } else {
            LoggerUtil.logExec(Level.INFO, "Reduce successful: " + minimizedTestCase.getFile() + " reduce: " + (originCode.length() - reducedCode.length()));
        }
        return minimizedTestCase;
    }

    /**
     * Sets up the workspace by creating a directory and copying the initial test file.
     */
    private Path setupWorkspace(TestCase testCase, Path workspaceRoot) {
        try {
            // Directly use the provided workspaceRoot, which is the complete verify environment.
            this.workspace_root = workspaceRoot.toAbsolutePath();

            // The test file path is already correct as it's inside the verify environment.
            Path testFilePath = testCase.getFile().toPath();
            this.workspace_testcase_path = testFilePath.toString();

            // Set the current working directory to the workspace root for consistency.
            System.setProperty("user.dir", workspaceRoot.toAbsolutePath().toString());
            
            // The workspace is already prepared.
            prepareWorkspaceWithFunctionCalling(testCase, workspaceRoot);

            LoggerUtil.logExec(Level.INFO, "TestCaseAgent working directly in environment: " + workspaceRoot);

            return testFilePath;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to set up workspace in TestCaseAgent: " + e.getMessage());
            return null;
        }
    }

    /**
     * Prepares the workspace by analyzing the test case and copying required files using function calling.
     */
    private void prepareWorkspaceWithFunctionCalling(TestCase testCase, Path workspaceRoot) {
        // Since the entire test environment is now copied, this step is no longer needed.
        // The LLM-based dependency analysis is removed to favor the complete environment.
        addToHistory("SETUP: Working in a complete test environment, no additional file preparation needed.");
    }
    
    /**
     * Gets a directory listing as a formatted string
     */
    private String getDirectoryListing(File directory) {
        if (directory == null || !directory.isDirectory()) {
            throw new IllegalArgumentException("Invalid directory: " + (directory != null ? directory.getAbsolutePath() : "null"));
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            throw new IllegalStateException("Failed to list files in directory: " + directory.getAbsolutePath());
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
            DebugUtils.getInstance().saveToFileWithTimestamp("TestCaseAgent", testCase.name + "_think_prompt", prompt);
            addToHistory("THINK: Analyzing current code and proposing reduction...");

            // Prepare function tools for the LLM
            List<Tool<?>> tools = new ArrayList<>();
            tools.add(toolRegistry.get("write_to_file"));
            tools.add(ACTION_FINISH);


            List<ToolCall> toolCalls = this.LLM.funcCall(prompt, tools);

            addToHistory("THINK: Proposed " + (toolCalls != null ? toolCalls.size() : 0) + " actions");

            if (toolCalls == null) {
                toolCalls = new ArrayList<>();
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
            addToHistory(Level.WARNING, "ACT: Parse error - " + e.getMessage());
            responses.add(ToolResponse.failure("Failed to parse or execute action: " + e.getMessage()));
            return responses;
        }
    }

    /**
     * The OBSERVE step of the loop. Analyzes the tool's output and updates the state.
     */
    private ObserveResult observe(List<ToolResponse<?>> toolResponses, String previousCode, String originalFailure, Path testFilePath) {
        if (toolResponses == null || toolResponses.isEmpty()) {
            addToHistory(Level.FINE, "OBSERVE: No tool responses to process.");
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
                addToHistory(Level.SEVERE, "OBSERVE: FATAL - Cannot revert file on REDO! " + e.getMessage());
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
                addToHistory(Level.SEVERE,"OBSERVE: FATAL - Cannot revert file! " + e.getMessage());
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
                addToHistory(Level.WARNING,"OBSERVE: LLM failed to choose an action, defaulting to continue.");
                return ACTION_CONTINUE.getName();
            }
        } catch (Exception e) {
            addToHistory(Level.WARNING,"OBSERVE: Error during LLM decision, defaulting to continue. " + e.getMessage());
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
        addToHistory(Level.INFO, message);
    }

    private void addToHistory(Level level, String message) {
        String filePrefix = workspace_testcase_path != null ? "[" + workspace_testcase_path + "] " : "";
        LoggerUtil.logExec(level, "[TestCaseAgent] " + filePrefix + message);
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
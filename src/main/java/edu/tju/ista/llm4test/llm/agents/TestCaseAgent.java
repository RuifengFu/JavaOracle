package edu.tju.ista.llm4test.llm.agents;


import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import edu.tju.ista.llm4test.utils.DebugUtils;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import freemarker.template.TemplateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * A dynamic, autonomous agent for test case Reduce.
 * It follows a Think-Act-Observe-Decide loop to iteratively reduce a failing test case.
 */
public class TestCaseAgent extends Agent {

    private static final Tool<Void> ACTION_CONTINUE = new BasicTool("continue_minimization", "Continue to the next minimization iteration.");
    private static final Tool<Void> ACTION_FINISH = new BasicTool("finish_minimization", "Finish the minimization process as it is complete.");
    private static final Tool<Void> ACTION_REDO = new RedoTool("redo_simplification", "Abandon the current simplification and revert to the previous code, providing feedback.");


    private final ToolRegistry toolRegistry;
    private final List<String> history;
    private final List<String> feedbackHistory;
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
        this.feedbackHistory = new ArrayList<>();
        this.lastExecutedTools = new ArrayList<>();
        registerTools();
    }

    private void registerTools() {
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new JtregExecuteTool());
    }

    public TestCase run(TestCase testCase, Path workspaceRoot) {
        String header = "Test Case Minimization Run: " + testCase.getName();
        LoggerUtil.logVerify(Level.INFO, header, "Minimization agent started.");

        this.testCase = testCase;
        history.clear();
        feedbackHistory.clear();
        Path testFilePath = setupWorkspace(testCase, workspaceRoot);
        if (testFilePath == null) {
            LoggerUtil.logVerify(Level.WARNING, header, "Minimization agent failed: Could not set up workspace.");
            return testCase;
        }

        String originalFailureOutput = testCase.getResult().getOutput();
        String originalCode = testCase.getSourceCode();
        String currentCode = originalCode;

        addToHistory("=== TestCaseAgent Started ===");
        addToHistory("Target: " + testCase.name);
        addToHistory("MinimizationPath: " + testFilePath);

        this.minimizedTestCase = new TestCase(testFilePath.toFile());
        minimizedTestCase.setOriginFile(testCase.getFile());
        minimizedTestCase.setResult(testCase.getResult());
        minimizedTestCase.setSourceCode(currentCode);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            addToHistory("--- Iteration " + (i + 1) + "/" + MAX_ITERATIONS + " ---");

            String codeBeforeAct = currentCode;

            // THINK
            String previousFeedback = feedbackHistory.isEmpty() ? "" : String.join("\n", feedbackHistory);
            List<ToolCall> toolCalls = think(currentCode, originalFailureOutput, previousFeedback, testFilePath);
            if (toolCalls.isEmpty()) {
                addToHistory(Level.WARNING, "LOOP: THINK failed to produce a tool call, skipping.");
                feedbackHistory.add("Iteration " + (i + 1) + ": THINK step failed to produce any action. Retrying.");
                if (feedbackHistory.size() > 5) feedbackHistory.remove(0);
                continue;
            }

            // ACT
            List<ToolResponse<?>> toolResponses = act(toolCalls);

            // OBSERVE
            ObserveResult observeResult = observe(toolResponses, testFilePath);

            // DECIDE
            Decision decision = decideNextAction(observeResult.feedback, originalFailureOutput, observeResult.lastTestResult);
            
            // EXECUTE DECISION
            if (ACTION_REDO.getName().equals(decision.actionName)) {
                addToHistory("LOOP: Decision is to REDO. Reverting code.");
                revertCode(testFilePath, codeBeforeAct, "revert-on-decision");
                currentCode = codeBeforeAct;
                // Use the specific failure reason from the LLM as feedback
                feedbackHistory.add("Iteration " + (i + 1) + ": " + decision.feedback);
            } else if (ACTION_FINISH.getName().equals(decision.actionName)) {
                addToHistory("LOOP: Decision is to FINISH.");
                currentCode = observeResult.newCode;
                minimizedTestCase.setSourceCode(currentCode);
                break;
            } else { // CONTINUE
                addToHistory("LOOP: Decision is to CONTINUE.");
                currentCode = observeResult.newCode;
                minimizedTestCase.setSourceCode(currentCode);
                minimizedTestCase.setResult(observeResult.lastTestResult);
                // Use the LLM's own explanation for 'continue' as the feedback. It's more insightful
                // than the raw observation summary.
                feedbackHistory.add("Iteration " + (i + 1) + ": " + decision.feedback);
            }
            
            if (feedbackHistory.size() > 5) {
                feedbackHistory.remove(0);
            }
        }

        addToHistory("=== Minimization Complete ===");
        logFinalResult(originalCode);
        return minimizedTestCase;
    }

    private Path setupWorkspace(TestCase testCase, Path workspaceRoot) {
        try {
            this.workspace_root = workspaceRoot.toAbsolutePath();
            Path testFilePath = testCase.getFile().toPath();
            this.workspace_testcase_path = testFilePath.toString();
            System.setProperty("user.dir", this.workspace_root.toString());
            LoggerUtil.logExec(Level.INFO, "TestCaseAgent working in environment: " + this.workspace_root);
            return testFilePath;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to set up workspace in TestCaseAgent: " + e.getMessage());
            return null;
        }
    }

    private List<ToolCall> think(String currentCode, String originalFailureOutput, String previousFeedback, Path testFilePath) {
        try {
            String relativeTestPath = testFilePath.getFileName().toString();
            if (!previousFeedback.isEmpty()) {
                addToHistory("THINK: Providing previous feedback to LLM:\n" + previousFeedback);
            }
            String prompt = PromptGen.generateTestCaseMinimizationReducePrompt(originalFailureOutput, currentCode, relativeTestPath, previousFeedback);
            DebugUtils.getInstance().saveToFileWithTimestamp("TestCaseAgent", testCase.name + "_think_prompt", prompt);
            addToHistory("THINK: Analyzing current code and proposing reduction...");

            List<Tool<?>> tools = List.of(toolRegistry.get("write_to_file"), ACTION_FINISH);
            OpenAI.ToolCallResult response = this.LLM.toolCallWithContent(prompt, tools);
            List<ToolCall> toolCalls = response.toolCalls();

            addToHistory("THINK: Proposed " + (toolCalls != null ? toolCalls.size() : 0) + " actions.");
            if (toolCalls != null && !toolCalls.isEmpty()) {
                for (ToolCall toolCall : toolCalls) {
                    addToHistory("THINK: Proposing action: " + toolCall.toolName + " with arguments: " + toolCall.arguments);
                }
            }

            if (toolCalls == null || toolCalls.isEmpty()) {
                addToHistory(Level.INFO, "THINK: No tool_call in response, trying to extract code manually.");
                ArrayList<String> extractedCode = CodeExtractor.extractCode(response.content());
                if (!extractedCode.isEmpty()) {
                    addToHistory(Level.INFO, "THINK: Found code block in raw response. Creating write_to_file call.");
                    Map<String, Object> args = new HashMap<>();
                    args.put("path", relativeTestPath);
                    args.put("content", extractedCode.get(0));
                    toolCalls = new ArrayList<>();
                    toolCalls.add(new ToolCall("write_to_file", args));
                }
            }
            return toolCalls != null ? toolCalls : new ArrayList<>();
        } catch (TemplateException | IOException e) {
            throw new RuntimeException("Error during THINK phase", e);
        }
    }

    private List<ToolResponse<?>> act(List<ToolCall> toolCalls) {
        List<ToolResponse<?>> responses = new ArrayList<>();
        this.lastExecutedTools = new ArrayList<>();
        
        // Auto-execute test after a file write
        if (toolCalls.stream().anyMatch(tc -> tc.toolName.equals("write_to_file"))) {
            toolCalls.add(new ToolCall("jtreg_execute", Map.of(
                "content", testCase.getFile().getAbsolutePath().toString(),
                "is_file_path", true,
                "class_name", testCase.getName()
            )));
        }

        for (ToolCall toolCall : toolCalls) {
            String toolName = toolCall.toolName;
            Map<String, Object> parameters = toolCall.arguments;

            if (toolName.equals("write_to_file")) {
                String fixedPath = testCase.getFile().getAbsolutePath();
                addToHistory("ACT: Forcing write_to_file to target: " + fixedPath
                        + ". Original path from LLM was: " + parameters.get("path"));
                parameters.put("path", fixedPath);
            }

            addToHistory("ACT: Executing " + toolName);
            Tool<?> tool = toolRegistry.get(toolName);
            if (tool != null) {
                responses.add(tool.execute(parameters));
                lastExecutedTools.add(toolName);
            } else {
                addToHistory(Level.WARNING, "ACT: Tool not found: " + toolName);
                responses.add(ToolResponse.failure("Tool not found: " + toolName));
            }
        }
        return responses;
    }

    private ObserveResult observe(List<ToolResponse<?>> toolResponses, Path testFilePath) {
        StringBuilder feedback = new StringBuilder();
        String currentCode = "";
        TestResult lastTestResult = null;

        boolean modificationAttempted = lastExecutedTools.contains("write_to_file");
        if (!modificationAttempted) {
            feedback.append("No code modification was attempted. No new observations.");
            try {
                currentCode = Files.readString(testFilePath);
            } catch (IOException e) {
                addToHistory(Level.SEVERE, "OBSERVE: Could not read file even though no modification was attempted.");
            }
            return new ObserveResult(currentCode, feedback.toString(), null);
        }

        int writeIndex = lastExecutedTools.indexOf("write_to_file");
        if (writeIndex == -1 || !toolResponses.get(writeIndex).isSuccess()) {
            feedback.append("File write operation failed. No changes were applied to the file.");
        }

        int testIndex = lastExecutedTools.indexOf("jtreg_execute");
        if (testIndex != -1 && toolResponses.get(testIndex).isSuccess() && toolResponses.get(testIndex).getResult() instanceof TestResult) {
            lastTestResult = (TestResult) toolResponses.get(testIndex).getResult();
            String status = lastTestResult.isFail() ? "FAILED" : "PASSED";
            String output = lastTestResult.getOutput() != null ? lastTestResult.getOutput().trim() : "[No Output]";
            feedback.append("Test executed after modification. Status: ").append(status).append("\n");
            feedback.append("--- NEW OUTPUT ---\n").append(output);
        } else {
            feedback.append("Test execution failed or did not run after modification.");
            if (testIndex != -1) {
                ToolResponse<?> response = toolResponses.get(testIndex);
                if (!response.isSuccess()) {
                    String failureReason = response.getResult() != null ? response.getResult().toString() : "Unknown failure reason";
                    feedback.append(" Failure reason: ").append(failureReason);
                }
            } else {
                feedback.append(" 'jtreg_execute' tool was not run.");
            }
        }

        try {
            currentCode = Files.readString(testFilePath);
        } catch (IOException e) {
            addToHistory(Level.SEVERE, "OBSERVE: Failed to read updated code from disk: " + e.getMessage());
            feedback.append("\nError: Failed to read file post-modification.");
        }
        
        return new ObserveResult(currentCode, feedback.toString(), lastTestResult);
    }
    
    private Decision decideNextAction(String feedback, String originalFailure, TestResult currentResult) {
        if (currentResult == null) {
            currentResult = new TestResult(edu.tju.ista.llm4test.execute.TestResultKind.TEST_FAIL);
            currentResult.setOutput("Test execution failed or did not produce a result.");
        }
        try {
            String prompt = PromptGen.generateTestCaseObserveAndDecidePrompt(feedback, originalFailure, currentResult);
            addToHistory("DECIDE: Asking LLM for next step...");

            List<Tool<?>> decisionTools = List.of(ACTION_CONTINUE, ACTION_FINISH, ACTION_REDO);
            OpenAI.ToolCallResult response = this.LLM.toolCallWithContent(prompt, decisionTools);
            List<ToolCall> toolCalls = response.toolCalls();

            if (toolCalls != null && !toolCalls.isEmpty()) {
                ToolCall chosenCall = toolCalls.get(0);
                String actionName = chosenCall.toolName;
                String feedbackText;

                if (actionName.equals(ACTION_REDO.getName())) {
                    // For the RedoTool, the feedback is in a specific 'feedback' argument.
                    feedbackText = (String) chosenCall.arguments.get("feedback");
                } else {
                    // For other tools, the explanation is in the general 'content'.
                    feedbackText = response.content();
                }

                addToHistory("DECIDE: LLM chose action: " + actionName);
                if (feedbackText != null && !feedbackText.isBlank()){
                    addToHistory("DECIDE: LLM explanation/feedback: " + feedbackText);
                }
                return new Decision(actionName, feedbackText);
            } else {
                addToHistory(Level.WARNING,"DECIDE: LLM failed to choose an action, defaulting to continue.");
                return new Decision(ACTION_CONTINUE.getName(), "LLM failed to provide a decision.");
            }
        } catch (Exception e) {
            addToHistory(Level.WARNING,"DECIDE: Error during LLM decision, defaulting to continue. " + e.getMessage());
            return new Decision(ACTION_CONTINUE.getName(), "Exception during decision making.");
        }
    }

    private void revertCode(Path testFilePath, String codeToRevert, String reason) {
        try {
            DebugUtils.getInstance().saveToFileWithTimestamp("TestCaseAgent", testCase.name + "_" + reason, codeToRevert);
            Files.writeString(testFilePath, codeToRevert);
            addToHistory("REVERT: Successfully reverted code.");
        } catch (IOException e) {
            addToHistory(Level.SEVERE, "REVERT: FATAL - Failed to revert file! " + e.getMessage());
        }
    }

    private void logFinalResult(String originalCode) {
        String reducedCode = minimizedTestCase.getSourceCode();
        boolean success = !originalCode.equals(reducedCode);
        int originSize = originalCode.length();
        int reducedSize = reducedCode.length();
        double reductionPercentage = originSize > 0 ? (double) (originSize - reducedSize) / originSize * 100 : 0;

        String header = "Test Case Minimization Result: " + minimizedTestCase.getName();
        String message = String.format(
            "  Success: %s\n" +
            "  Original Size: %d chars\n" +
            "  Reduced Size: %d chars\n" +
            "  Reduction: %.2f%%",
            success, originSize, reducedSize, reductionPercentage
        );

        LoggerUtil.logVerify(Level.INFO, header, message);

        if (!success) {
            LoggerUtil.logExec(Level.WARNING, "Minimization did not change the test case: " + minimizedTestCase.getFile());
        } else {
            LoggerUtil.logExec(Level.INFO, "Reduce successful: " + minimizedTestCase.getFile() + " | Reduction: " + (originSize - reducedSize) + " chars");
        }
    }
    
    private void addToHistory(String message) {
        addToHistory(Level.INFO, message);
    }

    private void addToHistory(Level level, String message) {
        String filePrefix = workspace_testcase_path != null ? "[" + Path.of(workspace_testcase_path).getFileName() + "] " : "";
        LoggerUtil.logExec(level, "[TestCaseAgent] " + filePrefix + message);
        history.add(message);
    }

    private static class ObserveResult {
        final String newCode;
        final String feedback;
        final TestResult lastTestResult;

        ObserveResult(String newCode, String feedback, TestResult lastTestResult) {
            this.newCode = newCode;
            this.feedback = feedback != null ? feedback : "";
            this.lastTestResult = lastTestResult;
        }
    }
    
    private static class Decision {
        final String actionName;
        final String feedback;

        Decision(String actionName, String feedback) {
            this.actionName = actionName;
            this.feedback = feedback != null ? feedback : "";
        }
    }
}

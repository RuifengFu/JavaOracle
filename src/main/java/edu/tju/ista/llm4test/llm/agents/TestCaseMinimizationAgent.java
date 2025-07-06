package edu.tju.ista.llm4test.llm.agents;

import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.agents.flow.*;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import freemarker.template.TemplateException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class TestCaseMinimizationAgent extends Agent {

    private final String baseWorkspace = "minimization_workspace";

    public TestCaseMinimizationAgent() {
        super("You are an expert test case minimizer.");
        this.LLM = OpenAI.R1;
    }

    /**
     * Minimizes the given test case using the AgentFlow framework.
     * @param testCase The test case to minimize.
     * @param parentWorkspace The directory where the minimization workspace will be created.
     * @return A File object pointing to the minimized test case, or the original file if minimization failed.
     */
    public File minimize(TestCase testCase, Path parentWorkspace) {
        // 1. Setup Workspace
        String workingDir;
        try {
            workingDir = setupWorkspace(parentWorkspace);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to setup workspace for test case minimization: " + e.getMessage());
            return testCase.getFile();
        }

        // 2. Build the workflow
        AgentFlow flow;
        try {
            flow = buildMinimizationFlow();
        } catch (TemplateException | IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to build minimization flow: " + e.getMessage());
            return testCase.getFile();
        }

        // 3. Initialize Context and Tools
        FlowContext context = new FlowContext();
        context.put("originalTestCaseFile", testCase.getFile().getAbsolutePath());
        context.put("workingDirectory", workingDir);
        context.put("testCaseName", testCase.getFile().getName());
        context.put("fullWorkspacePath", Paths.get(workingDir, testCase.getFile().getName()).toString());
        context.put("originalFailureOutput", testCase.getResult() != null ? testCase.getResult().getOutput() : "");
        context.put("maxIterations", 10);
        context.put("currentIteration", 0);


        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new CopyFileTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new JtregExecuteTool());
        toolRegistry.register(new CheckSimplicityTool(this.LLM));
        toolRegistry.register(new ExtractCodeTool());

        // 4. Execute the flow
        AgentFlowExecutor executor = new AgentFlowExecutor(flow, toolRegistry, this.LLM, context);
        FlowResult result = executor.execute();

        // 5. Return the result from the final context
        if (result.isSuccess() && result.getContext().get("minimizedCode") != null) {
            String minimizedCode = (String) result.getContext().get("minimizedCode");
            Path minimizedPath = Paths.get(workingDir, testCase.getFile().getName().replace(".java", "_minimized.java"));
            try {
                Files.writeString(minimizedPath, minimizedCode);
                LoggerUtil.logExec(Level.INFO, "Successfully saved minimized test case to: " + minimizedPath);
                return minimizedPath.toFile();
            } catch (IOException e) {
                LoggerUtil.logExec(Level.SEVERE, "Failed to write minimized file: " + e.getMessage());
            }
        }
        
        LoggerUtil.logExec(Level.WARNING, "Minimization did not succeed or was inconclusive. Returning original test case.");
        return testCase.getFile();
    }

    private String setupWorkspace(Path parentWorkspace) throws IOException {
        String workingDirectory = parentWorkspace.resolve("minimization").toString();
        Path workspacePath = Paths.get(workingDirectory);
        if (!Files.exists(workspacePath)) {
            Files.createDirectories(workspacePath);
        }
        LoggerUtil.logExec(Level.INFO, "Ensured minimization workspace exists at: " + workspacePath);
        return workingDirectory;
    }

    private AgentFlow buildMinimizationFlow() throws TemplateException, IOException {
        AgentFlow flow = new AgentFlow("Test Case Minimization Flow", "setup");

        // Step 1: Copy file to workspace
        FlowStep setupStep = new FlowStep();
        setupStep.setId("setup");
        setupStep.setDescription("Copy original test case to the working directory.");
        FlowAction setupAction = new FlowAction();
        setupAction.setType(ActionType.TOOL_CALL);
        setupAction.setToolName("copy_file");
        setupAction.setParameters(Map.of("source", "${originalTestCaseFile}", "destination", "${fullWorkspacePath}"));
        setupAction.setOutputKey("copy_result");
        setupStep.setAction(setupAction);
        setupStep.setNextStepId("initial_verify");
        flow.addStep(setupStep);

        // Step 2: Initial verification
        FlowStep initialVerifyStep = new FlowStep();
        initialVerifyStep.setId("initial_verify");
        initialVerifyStep.setDescription("Run the test in the new workspace to verify the failure is reproducible.");
        FlowAction initialVerifyAction = new FlowAction();
        initialVerifyAction.setType(ActionType.TOOL_CALL);
        initialVerifyAction.setToolName("jtreg_execute");
        initialVerifyAction.setParameters(Map.of("file_path", "${fullWorkspacePath}"));
        initialVerifyAction.setOutputKey("initial_verify_result");
        initialVerifyStep.setAction(initialVerifyAction);
        initialVerifyStep.setConditionKey("initial_verify_result_success"); // jtreg success means test *passed*, which is a failure for us
        initialVerifyStep.setConditionalNextSteps(Map.of("true", "end_failure_to_reproduce"));
        initialVerifyStep.setDefaultNextStepId("initial_read");
        flow.addStep(initialVerifyStep);

        // Step 3: Read the initial code into context
        FlowStep initialReadStep = new FlowStep();
        initialReadStep.setId("initial_read");
        initialReadStep.setDescription("Read the source code to begin reduction.");
        FlowAction initialReadAction = new FlowAction();
        initialReadAction.setType(ActionType.TOOL_CALL);
        initialReadAction.setToolName("read_file");
        initialReadAction.setParameters(Map.of("file_path", "${fullWorkspacePath}"));
        initialReadAction.setOutputKey("currentCode");
        initialReadStep.setAction(initialReadAction);
        initialReadStep.setNextStepId("loop_condition_check");
        flow.addStep(initialReadStep);

        // Step 4: LOOP START - Condition check
        FlowStep loopConditionCheckStep = new FlowStep();
        loopConditionCheckStep.setId("loop_condition_check");
        loopConditionCheckStep.setDescription("Check if loop should continue (iteration count and simplicity).");
        FlowAction loopConditionAction = new FlowAction();
        loopConditionAction.setType(ActionType.TOOL_CALL);
        loopConditionAction.setToolName("check_simplicity");
        loopConditionAction.setParameters(Map.of("code", "${currentCode}"));
        loopConditionAction.setOutputKey("is_simple_enough");
        loopConditionCheckStep.setAction(loopConditionAction);
        loopConditionCheckStep.setConditionKey("is_simple_enough");
        loopConditionCheckStep.setConditionalNextSteps(Map.of("true", "prepare_final_result"));
        loopConditionCheckStep.setDefaultNextStepId("propose_reduction");
        flow.addStep(loopConditionCheckStep);
        
        // Step 5: Propose a reduction
        FlowStep proposeReductionStep = new FlowStep();
        proposeReductionStep.setId("propose_reduction");
        proposeReductionStep.setDescription("Use LLM to propose a simplification of the current code.");
        FlowAction proposeReductionAction = new FlowAction();
        proposeReductionAction.setType(ActionType.LLM_CALL);
        proposeReductionAction.setPromptTemplate(PromptGen.generateTestCaseMinimizationReducePrompt("${originalFailureOutput}", "${currentCode}"));
        proposeReductionAction.setOutputKey("llm_proposal");
        proposeReductionStep.setAction(proposeReductionAction);
        proposeReductionStep.setNextStepId("extract_proposed_code");
        flow.addStep(proposeReductionStep);

        // Step 5.5: Extract clean code from the proposal
        FlowStep extractCodeStep = new FlowStep();
        extractCodeStep.setId("extract_proposed_code");
        extractCodeStep.setDescription("Extract clean code from LLM proposal.");
        FlowAction extractCodeAction = new FlowAction();
        extractCodeAction.setType(ActionType.TOOL_CALL);
        extractCodeAction.setToolName("extract_code");
        extractCodeAction.setParameters(Map.of("raw_string", "${llm_proposal}"));
        extractCodeAction.setOutputKey("proposedCode");
        extractCodeStep.setAction(extractCodeAction);
        extractCodeStep.setNextStepId("apply_reduction");
        flow.addStep(extractCodeStep);

        // Step 6: Apply the reduction
        FlowStep applyReductionStep = new FlowStep();
        applyReductionStep.setId("apply_reduction");
        applyReductionStep.setDescription("Apply the proposed code reduction to the file.");
        FlowAction applyReductionAction = new FlowAction();
        applyReductionAction.setType(ActionType.TOOL_CALL);
        applyReductionAction.setToolName("write_file");
        applyReductionAction.setParameters(Map.of("file_path", "${fullWorkspacePath}", "content", "${proposedCode}"));
        applyReductionAction.setOutputKey("write_reduction_result");
        applyReductionStep.setAction(applyReductionAction);
        applyReductionStep.setNextStepId("verify_reduction");
        flow.addStep(applyReductionStep);

        // Step 7: Verify the reduction
        FlowStep verifyReductionStep = new FlowStep();
        verifyReductionStep.setId("verify_reduction");
        verifyReductionStep.setDescription("Run the test with the reduced code to see if it still fails correctly.");
        FlowAction verifyReductionAction = new FlowAction();
        verifyReductionAction.setType(ActionType.TOOL_CALL);
        verifyReductionAction.setToolName("jtreg_execute");
        verifyReductionAction.setParameters(Map.of("file_path", "${fullWorkspacePath}"));
        verifyReductionAction.setOutputKey("verify_reduction_result");
        verifyReductionStep.setAction(verifyReductionAction);
        verifyReductionStep.setConditionKey("verify_reduction_result_success");
        verifyReductionStep.setConditionalNextSteps(Map.of("true", "revert_reduction")); // Test passed -> bad reduction
        verifyReductionStep.setDefaultNextStepId("commit_reduction"); // Test failed -> good reduction
        flow.addStep(verifyReductionStep);
        
        // Step 8a: Commit the successful reduction
        FlowStep commitReductionStep = new FlowStep();
        commitReductionStep.setId("commit_reduction");
        commitReductionStep.setDescription("The reduction was successful. Update the current code state.");
        FlowAction commitReductionAction = new FlowAction();
        commitReductionAction.setType(ActionType.TOOL_CALL);
        commitReductionAction.setToolName("read_file");
        commitReductionAction.setParameters(Map.of("file_path", "${fullWorkspacePath}"));
        commitReductionAction.setOutputKey("currentCode"); // Overwrite currentCode with the new successful version
        commitReductionStep.setAction(commitReductionAction);
        commitReductionStep.setNextStepId("loop_condition_check"); // Loop back
        flow.addStep(commitReductionStep);
        
        // Step 8b: Revert the failed reduction
        FlowStep revertReductionStep = new FlowStep();
        revertReductionStep.setId("revert_reduction");
        revertReductionStep.setDescription("The reduction was invalid. Reverting to the previous version.");
        FlowAction revertReductionAction = new FlowAction();
        revertReductionAction.setType(ActionType.TOOL_CALL);
        revertReductionAction.setToolName("write_file");
        revertReductionAction.setParameters(Map.of("file_path", "${fullWorkspacePath}", "content", "${currentCode}"));
        revertReductionAction.setOutputKey("revert_result");
        revertReductionStep.setAction(revertReductionAction);
        revertReductionStep.setNextStepId("loop_condition_check"); // Loop back
        flow.addStep(revertReductionStep);

        // Final Steps
        FlowStep prepareResultStep = new FlowStep();
        prepareResultStep.setId("prepare_final_result");
        prepareResultStep.setDescription("Set the final minimized code for output.");
        FlowAction prepareResultAction = new FlowAction();
        prepareResultAction.setType(ActionType.CONTEXT_MANIPULATION);
        prepareResultAction.setParameters(Map.of(
            "operation", "set",
            "key", "minimizedCode",
            "value", "${currentCode}"
        ));
        prepareResultStep.setAction(prepareResultAction);
        prepareResultStep.setNextStepId("end_success");
        flow.addStep(prepareResultStep);

        FlowStep endSuccessStep = new FlowStep();
        endSuccessStep.setId("end_success");
        endSuccessStep.setDescription("Minimization finished successfully.");
        endSuccessStep.setAction(null); // No action needed
        flow.addStep(endSuccessStep);
        
        FlowStep endFailureStep = new FlowStep();
        endFailureStep.setId("end_failure_to_reproduce");
        endFailureStep.setDescription("Minimization failed because the initial failure could not be reproduced.");
        endFailureStep.setAction(null); // No action needed
        flow.addStep(endFailureStep);

        return flow;
    }

} 
package edu.tju.ista.llm4test.llm.agents.flow;

import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.Tool;
import edu.tju.ista.llm4test.llm.tools.ToolRegistry;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.DefaultObjectWrapperBuilder;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Executes an AgentFlow, managing the flow's state and context.
 */
public class AgentFlowExecutor {
    private final AgentFlow flow;
    private final ToolRegistry toolRegistry;
    private final OpenAI llm;
    private final FlowContext context;
    private final Configuration freemarkerCfg;

    public AgentFlowExecutor(AgentFlow flow, ToolRegistry toolRegistry, OpenAI llm, FlowContext initialContext) {
        this.flow = Objects.requireNonNull(flow, "Flow cannot be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "ToolRegistry cannot be null");
        this.llm = Objects.requireNonNull(llm, "LLM cannot be null");
        this.context = Objects.requireNonNull(initialContext, "Initial context cannot be null");

        // Initialize Freemarker for prompt templating
        this.freemarkerCfg = new Configuration(Configuration.VERSION_2_3_31);
        this.freemarkerCfg.setNumberFormat("computer");
        this.freemarkerCfg.setObjectWrapper(new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_31).build());
    }

    public FlowResult execute() {
        String currentStepId = flow.getStartStepId();
        int maxSteps = 100; // Safety break to prevent infinite loops
        int stepCount = 0;

        while (currentStepId != null && stepCount < maxSteps) {
            FlowStep currentStep = flow.getStep(currentStepId);
            if (currentStep == null) {
                LoggerUtil.logExec(Level.SEVERE, "Flow execution failed: Step with ID '" + currentStepId + "' not found.");
                return FlowResult.failure("Step not found: " + currentStepId);
            }

            LoggerUtil.logExec(Level.INFO, "Executing step: " + currentStep.getId() + " - " + currentStep.getDescription());
            executeStep(currentStep);

            currentStepId = determineNextStepId(currentStep);
            stepCount++;
        }

        if (stepCount >= maxSteps) {
            LoggerUtil.logExec(Level.WARNING, "Flow execution reached max steps limit. Terminating.");
        }

        LoggerUtil.logExec(Level.INFO, "Flow execution finished.");
        return FlowResult.success(context);
    }

    private void executeStep(FlowStep step) {
        FlowAction action = step.getAction();
        if (action == null) {
            LoggerUtil.logExec(Level.FINE, "Step " + step.getId() + " has no action. Proceeding to next step.");
            return;
        }

        try {
            switch (action.getType()) {
                case LLM_CALL:
                    executeLlmCall(action);
                    break;
                case TOOL_CALL:
                    executeToolCall(action);
                    break;
                case CONTEXT_MANIPULATION:
                    executeContextManipulation(action);
                    break;
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Error executing step " + step.getId() + ": " + e.getMessage());
            context.put("lastError", e.getMessage());
            // Here you could add logic to jump to an error handler step
        }
    }

    private void executeLlmCall(FlowAction action) throws IOException, TemplateException {
        String promptTemplate = action.getPromptTemplate();
        String prompt = renderTemplate(promptTemplate);

        boolean expectJson = prompt.toLowerCase().contains("json");
        String response = llm.messageCompletion(prompt, 0.5, expectJson); // Temperature could also be a parameter

        if (action.getOutputKey() != null) {
            context.put(action.getOutputKey(), response);
        }
    }

    private void executeToolCall(FlowAction action) {
        String toolName = action.getToolName();
        Tool<?> tool = toolRegistry.get(toolName);
        
        Map<String, Object> renderedParams = new HashMap<>();
        Map<String, Object> params = action.getParameters();
        if (params != null) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    try {
                        // Use the existing renderTemplate method for consistency
                        renderedParams.put(entry.getKey(), renderTemplate((String) value));
                    } catch (IOException | TemplateException e) {
                        LoggerUtil.logExec(Level.WARNING, "Failed to render template for tool parameter '" + entry.getKey() + "'. Using raw value. Error: " + e.getMessage());
                        renderedParams.put(entry.getKey(), value);
                    }
                } else {
                    renderedParams.put(entry.getKey(), value);
                }
            }
        }
        
        ToolResponse<?> response = tool.execute(renderedParams);
        
        if (action.getOutputKey() != null) {
            // Store the whole response or just the result
            context.put(action.getOutputKey(), response.getResult());
            context.put(action.getOutputKey() + "_success", response.isSuccess());
            context.put(action.getOutputKey() + "_message", response.getFailMessage());
        }
    }

    private void executeContextManipulation(FlowAction action) throws IOException, TemplateException {
        Map<String, Object> params = action.getParameters();
        String operation = (String) params.get("operation");

        if ("set".equalsIgnoreCase(operation)) {
            String key = (String) params.get("key");
            Object value = params.get("value");

            if (key == null) {
                throw new IllegalArgumentException("CONTEXT_MANIPULATION 'set' operation requires a 'key' parameter.");
            }

            // Render the value if it's a string template
            Object renderedValue = value;
            if (value instanceof String) {
                // Important: The value to be set might refer to another context variable.
                // e.g. value: "${currentCode}"
                renderedValue = renderTemplate((String) value);
            }

            context.put(key, renderedValue);
            LoggerUtil.logExec(Level.INFO, "Context Manipulation: SET " + key + " = " + renderedValue);
        } else {
            throw new IllegalArgumentException("Unsupported CONTEXT_MANIPULATION operation: " + operation);
        }
    }

    private String determineNextStepId(FlowStep currentStep) {
        String conditionKey = currentStep.getConditionKey();

        if (conditionKey == null || conditionKey.isEmpty()) {
            return currentStep.getNextStepId(); // Linear flow
        }

        Object value = context.get(conditionKey);
        String conditionValue = (value == null) ? "null" : String.valueOf(value);

        Map<String, String> branches = currentStep.getConditionalNextSteps();
        if (branches != null && branches.containsKey(conditionValue)) {
            return branches.get(conditionValue);
        }

        return currentStep.getDefaultNextStepId(); // Fallback
    }

    private String renderTemplate(String templateContent) throws IOException, TemplateException {
        if (templateContent == null) return "";
        Template template = new Template("prompt", templateContent, freemarkerCfg);
        StringWriter writer = new StringWriter();
        template.process(context.getAll(), writer);
        return writer.toString();
    }
} 
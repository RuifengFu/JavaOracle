package edu.tju.ista.llm4test.llm.agents;

import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * An autonomous agent that minimizes a failing test case by following a Think-Act-Observe-Adapt loop.
 * This agent is designed based on the "Code-Shrinker" Genesis Prompt.
 */
public class CodeShrinkerAgent extends Agent {

    private final ToolRegistry toolRegistry;
    private final List<String> history;
    private String originalFailureLog;
    private String workspacePath;

    public CodeShrinkerAgent() {
        super();
        this.LLM = OpenAI.R1; // Or any suitable model
        this.toolRegistry = new ToolRegistry();
        this.history = new ArrayList<>();
        // TODO: Register tools here
    }

    /**
     * The main entry point for the agent's execution loop.
     *
     * @param initialCode        The initial failing source code.
     * @param initialFailureLog  The initial failure log to be preserved.
     * @param workingDirectory   The directory to perform all operations in.
     * @return The minimized source code.
     */
    public String run(String initialCode, String initialFailureLog, String workingDirectory) {
        this.originalFailureLog = initialFailureLog;
        this.workspacePath = workingDirectory;
        
        // Prime the history
        history.add("Initial State: Received a test case and its failure log: " + initialFailureLog);
        history.add("Initial Code:\n```java\n" + initialCode + "\n```");
        
        // The main Think-Act-Observe-Adapt loop
        while (true) {
            // 1. Think
            String prompt = buildThinkingPrompt();
            
            // TODO: Call LLM to get the next action in JSON format
            
            // 2. Act
            // TODO: Parse the action from the LLM response
            
            // TODO: Execute the action using the tool registry
            
            // TODO: Check for the "Finish" action to break the loop
            
            // 3. Observe
            // TODO: Record the observation from the tool execution into the history
            
            // 4. Adapt (Happens automatically as the history is used in the next loop's prompt)
        }
    }

    private String buildThinkingPrompt() {
        // TODO: Implement the prompt template from the Genesis Prompt,
        // including identity, objective, tools, and the full history.
        return "";
    }
} 
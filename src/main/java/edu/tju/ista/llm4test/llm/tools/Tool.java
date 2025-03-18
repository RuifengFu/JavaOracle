package edu.tju.ista.llm4test.llm.tools;

public interface Tool<T> {
    String getName();
    String getDescription();
    ToolResponse<T> execute(String input) throws Exception;
}
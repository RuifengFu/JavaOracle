package edu.tju.ista.llm4test.llm.agents;

import java.util.HashMap;

public interface ChatCompletion {
    public String complete(String chat);
    public String complete(String chat, HashMap<String, Object> dataMode);
}

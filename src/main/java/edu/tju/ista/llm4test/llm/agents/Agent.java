package edu.tju.ista.llm4test.llm.agents;

import edu.tju.ista.llm4test.llm.OpenAI;
import freemarker.template.Configuration;
import freemarker.template.Template;

import java.io.StringWriter;
import java.util.HashMap;

public class Agent {
    protected String prompt;
    protected static final Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
    protected OpenAI LLM = OpenAI.Doubao_think;

    public Agent() {
        prompt = "You are a helpful assistant.";
    }

    public Agent(String prompt) {
        this.prompt = prompt;
    }

    public String genPrompt(HashMap<String, Object> dataModel) {
        Template template;
        StringWriter writer = new StringWriter();
        try {
            template = new Template(this.getClass().getName(), prompt, cfg);
            template.process(dataModel, writer);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate prompt", e);
        }
        return writer.toString();
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public OpenAI getLLM() {
        return LLM;
    }
}


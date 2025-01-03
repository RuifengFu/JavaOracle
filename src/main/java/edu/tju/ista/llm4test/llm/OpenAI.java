package edu.tju.ista.llm4test.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import edu.tju.ista.llm4test.llm.functionCalling.FuncTool;
import edu.tju.ista.llm4test.llm.functionCalling.FuncToolFactory;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class OpenAI {
    private static final String API_KEY = "sk-xxxxxxxxxxxxxxxxxxxxxx"; // Replace with your API key

    private static final String BASE_URL = "https://api.deepseek.com/beta/v1/chat/completions";

    private static final String MODEL = "deepseek-chat";

    private static final int MAX_TOKENS = 8096;

    public static String messageCompletion(String prompt) {
        return messageCompletion("You are a helpful assistant", prompt);
    }

    static {
//        API_KEY = "hk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
//        BASE_URL = "https://api.openai-hk.com/beta/v1/chat/completions";
//        MODEL = "claude-3-5-sonnet-20241022";
    }


    private static Map<String, Object> getBaseRequestMap(String systemPrompt, String prompt) {
        // Create request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL); // Choose the model, e.g., "gpt-4" or "gpt-3.5-turbo"
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("max_tokens", MAX_TOKENS); // Limit the response length
        requestBody.put("temperature", 0); // Set the randomness of the output
        return requestBody;
    }

    private static Map<String, Object> getResponseBody(Map<String, Object> requestBody) throws IOException, InterruptedException {
        // Convert request body to JSON
        ObjectMapper mapper = new ObjectMapper();
        String requestBodyJson = mapper.writeValueAsString(requestBody);

        // Initialize HttpClient
        HttpClient httpClient = HttpClient.newHttpClient();

        // Build the HTTP request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL)) // Replace BASE_URL with your API endpoint
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();

        // Send the request and receive the response
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Parse the response body
        Map<String, Object> responseBody = mapper.readValue(response.body(), Map.class);
        return responseBody;
    }


    public static String messageCompletion(String systemPrompt, String prompt) {
        try {
            Map<String, Object> requestBody = getBaseRequestMap(systemPrompt, prompt);
            Map<String, Object> responseBody = getResponseBody(requestBody);
            // Extract the "message.content" value from the response
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0); // Get the first choice
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                if (message != null) {
                    String content = (String) message.get("content"); // Extract "content"
                    LoggerUtil.logOpenAI(Level.FINE, "OpenAI response: \n" + content);
                    return content; // Return the extracted content
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }

        return "";
    }


    public static Map<String, String> funcCall(String systemPrompt, String prompt, List<FuncTool> tools) {
        try {
            // Create request body
            Map<String, Object> requestBody = getBaseRequestMap(systemPrompt, prompt);
            requestBody.put("tools", FuncToolFactory.toToolsArray(tools));

            // Convert request body to JSON
            Map<String, Object> responseBody = getResponseBody(requestBody);
            LoggerUtil.logOpenAI(Level.INFO, "OpenAI response Body : \n" + responseBody);
            // Extract the "message.content" value from the response
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0); // Get the first choice
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                if (message != null) {
                    String content = (String) message.get("content"); // Extract "content"
                    ArrayList<HashMap<String, Object>> toolCalls = (ArrayList<HashMap<String, Object>>) message.get("tool_calls");
                    Map<String, String> callMap = toolCalls.stream().map(call -> (HashMap<String, Object>)call.get("function"))
                            .collect(Collectors.toMap(
                                    func -> (String)func.get("name"),
                                    func -> (String)func.get("arguments")
                            ));

                    LoggerUtil.logOpenAI(Level.INFO, "OpenAI response: \n" + "content :\n" + content + "\nfunction calling: \n" + callMap);
                    return callMap; // Return the extracted content
                }
            }

        } catch (Exception e) {
            e.printStackTrace(System.out);
            LoggerUtil.logExec(Level.SEVERE, "OpenAI function calling failed: " + e.getMessage());
        }
        return new HashMap<>();
    }
}

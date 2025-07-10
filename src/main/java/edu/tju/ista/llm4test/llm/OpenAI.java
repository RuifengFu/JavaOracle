package edu.tju.ista.llm4test.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.llm.tools.Tool;
import edu.tju.ista.llm4test.llm.tools.ToolFactory;
import edu.tju.ista.llm4test.llm.tools.ToolCall;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Thread.sleep;

/*
 *  OpenAI style LLM api calling.
 */
public class OpenAI {
    private final String API_KEY; // Replace with your API key

    private String BASE_URL;
//    private static final String BASE_URL = "https://api.siliconflow.cn/v1/chat/completions";

    private String MODEL;

    private final double TEMPERATURE = 0;

    private int MAX_TOKENS = 8192;
    private boolean STREAM = true;

    private boolean JSON_OUTPUT = false;

    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 60000;

    public String messageCompletion(String prompt) {
        return messageCompletion(prompt, 0.7);
    }


    public OpenAI() {
        API_KEY = GlobalConfig.getOpenaiApiKey();
        BASE_URL = GlobalConfig.getOpenaiBaseUrl();
        MODEL = GlobalConfig.getOpenaiModel();
    }

    public OpenAI(String modelName) {
        this();
        MODEL = modelName;
        System.out.println("BASE_URL: " + BASE_URL);
        System.out.println("API_KEY: " + API_KEY);
        System.out.println("MODEL: " + MODEL);
    }

    public OpenAI(String API_KEY, String BASE_URL, String modelName) {
        this.API_KEY = API_KEY;
        this.BASE_URL = BASE_URL;
        MODEL = modelName;
    }

    public static OpenAI R1;
    public static OpenAI V3;
    public static OpenAI Doubao;

    static {

        
        String arkApiKey = GlobalConfig.getDoubaoApiKey();
        String arkBaseUrl = GlobalConfig.getDoubaoBaseUrl();
        String arkModel = GlobalConfig.getDoubaoModel();

        arkModel = "doubao-seed-1-6-flash-250615";

        Doubao = new OpenAI(arkApiKey, arkBaseUrl, arkModel);

        R1 = new OpenAI(GlobalConfig.getOpenaiR1Model());
        V3 = new OpenAI(GlobalConfig.getOpenaiV3Model());
        V3.JSON_OUTPUT = true;

    }


    private Map<String, Object> getBaseRequestMap(String prompt) {
        // Create request body
        Map<String, Object> requestBody = new HashMap<>();
        if (this.JSON_OUTPUT) {
            requestBody.put("response_format", Map.of("type", "json_object"));
            if (!prompt.contains("json")) {
                prompt += "\nPlease return the response in json format.";
            }
        }
        requestBody.put("model", MODEL);
        requestBody.put("messages", List.of(
//                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", prompt)
        ));

        requestBody.put("max_tokens", MAX_TOKENS); // Limit the response length
        requestBody.put("temperature", TEMPERATURE); // Set the randomness of the output
        requestBody.put("stream", STREAM);
        return requestBody;
    }

    private HttpClient buildHttpClient() {
        if (GlobalConfig.isProxyEnabled()) {
            String proxyHost = GlobalConfig.getProxyHost();
            int proxyPort = GlobalConfig.getProxyPort();
            
            if (!proxyHost.isEmpty() && proxyPort > 0) {
                return HttpClient.newBuilder()
                        .proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)))
                        .build();
            }
        }
        return HttpClient.newHttpClient();
    }

    private HttpRequest buildRequest(String requestBodyJson) {
        return HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
    }

    private boolean shouldRetry(int statusCode) {
        return statusCode == 503 || statusCode == 429 || statusCode == 504 || statusCode == 421;
    }

    private <T> T executeWithRetry(String operationName, RetryableOperation<T> retryOperation) 
            throws IOException, InterruptedException {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount <= MAX_RETRIES) {
            try {
                return retryOperation.execute();
            } catch (Exception e) {
                lastException = e;
                
                // 检查是否需要重试
                if (shouldRetry(e)) {
                    handleRetry(operationName, retryCount);
                    retryCount++;
                    continue;
                }
                
                // 不需要重试的错误直接抛出
                throw e;
            }
        }

        // 如果所有重试都失败了，抛出最后一个异常
        throw new RuntimeException(
            String.format("Operation '%s' failed after %d retries. Last error: %s", 
                operationName, MAX_RETRIES, lastException.getMessage()),
            lastException
        );
    }

    private boolean shouldRetry(Exception e) {
        if (!(e instanceof RuntimeException)) {
            return false;
        }

        String message = e.getMessage();
        if (message == null) {
            return false;
        }

        // 检查是否是 HTTP 错误
        if (message.contains("HTTP error")) {
            try {
                int statusCode = extractStatusCode(message);
                return shouldRetry(statusCode);
            } catch (Exception ex) {
                return false;
            }
        }

        // 检查是否是空响应错误
        if (message.contains("empty response") || message.contains("Stream response was empty")) {
            return true;
        }

        return false;
    }

    private void handleRetry(String operationName, int retryCount) throws InterruptedException {
        if (retryCount >= MAX_RETRIES) {
            throw new RuntimeException(
                String.format("Maximum retry attempts (%d) reached for operation: %s", 
                    MAX_RETRIES, operationName)
            );
        }

        LoggerUtil.logExec(Level.WARNING, 
            String.format("Retry attempt %d/%d for %s after %dms", 
                retryCount + 1, MAX_RETRIES, operationName, RETRY_DELAY_MS));
        
        Thread.sleep(RETRY_DELAY_MS);
    }

    private int extractStatusCode(String errorMessage) {
        try {
            return Integer.parseInt(errorMessage.split("HTTP error: ")[1].split("\n")[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    @FunctionalInterface
    private interface RetryableOperation<T> {
        T execute() throws IOException, InterruptedException;
    }

    private static boolean isValidDataLine(String line) {
        return !line.trim().isEmpty()
                && !line.startsWith(": keep-alive")
                && line.startsWith("data: ");
    }

    private static String extractJsonContent(String line) {
        return line.substring(5).trim(); // 去掉"data:"前缀
    }

    public String messageCompletion(String prompt, double temperature) {
        return messageCompletion(prompt, temperature, false);
    }


    /*     * 发送消息并获取回复
     * @param prompt 用户输入的提示
     * @param temperature 控制生成文本的随机性，范围0-1
     * @param jsonOutput 是否需要返回JSON格式的输出
     * @return 返回生成的文本内容
     */
    public String messageCompletion(String prompt, double temperature, boolean jsonOutput) {
        try {
            if (jsonOutput && !prompt.contains(" json ")) {
                prompt += "\nPlease return the response in json format.";
            }
            Map<String, Object> requestBody = getBaseRequestMap(prompt);
            if (jsonOutput) {
                requestBody.put("response_format", Map.of("type", "json_object"));
            }
            // update temperature
            requestBody.put("temperature", temperature);
            if (STREAM) {
                StringBuilder reasonSb = new StringBuilder();
                StringBuilder contentSb = new StringBuilder();
                // 调用流式接口
                streamResponse(requestBody, chunk -> {
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        Map<String, Object> choice = choices.get(0);
                        Map<String, Object> message = (Map<String, Object>) choice.get("delta");
                        if (message != null) {
                            String reasoning_content = (String) message.get("reasoning_content");
                            if (reasoning_content != null)
                                reasonSb.append(reasoning_content);
                            String content = (String) message.get("content");
                            if (content != null)
                                contentSb.append(content);
                        }
                    }
                });
                String result = "<thinking>\n" + reasonSb + "\n</thinking>\n\n" + contentSb;
                LoggerUtil.logOpenAI(Level.INFO, "OpenAI response: \n" + result);
                return contentSb.toString();
            }
            Map<String, Object> responseBody = getResponseBody(requestBody);
            // Extract the "message.content" value from the response
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0); // Get the first choice
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                if (message != null) {
                    String content = (String) message.get("content"); // Extract "content"
                    String thinking = (String) message.get("reasoning_content");
                    LoggerUtil.logOpenAI(Level.FINE, "OpenAI response: \n" + "<thinking>\n" + thinking + "\n</thinking>\n\n" + content);
                    return content; // Return the extracted content
                }
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "OpenAI message completion failed: " + e.getMessage());
            e.printStackTrace(System.err);
        }

        return "";
    }


    public List<ToolCall> funcCall(String prompt, List<Tool<?>> tools) {
        try {
            // Create request body
            Map<String, Object> requestBody = getBaseRequestMap(prompt);
            requestBody.put("tools", ToolFactory.toToolsArray(tools));
//            requestBody.put("temperature", 1.0); // Set the randomness of the output
            requestBody.put("stream", false);
            requestBody.put("max_tokens", 4096);
            requestBody.put("model", MODEL);

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
                    if (message.get("reasoning_content") != null) {
                        String reasoningContent = (String) message.get("reasoning_content");
                        content = "<thinking>\n" + reasoningContent + "\n</thinking>\n\n" + content;
                    }
                    ArrayList<HashMap<String, Object>> toolCalls = (ArrayList<HashMap<String, Object>>) message.getOrDefault("tool_calls", new ArrayList<>());
                    ArrayList<ToolCall> callList = toolCalls.stream().map(call -> (HashMap<String, Object>)call.get("function"))
                            .map(func -> {
                                try {
                                    return new ToolCall((String)func.get("name"), (String)func.get("arguments"));
                                } catch (JsonProcessingException e) {
                                    LoggerUtil.logExec(Level.WARNING, "Failed to parse function call arguments: " + e.getMessage());
                                    return null;
                                }
                            }).filter(Objects::nonNull)
                            .collect(Collectors.toCollection(ArrayList::new));


                    LoggerUtil.logOpenAI(Level.INFO, "OpenAI response: \n" + "content :\n" + content + "\nfunction calling: \n" + callList);
                    return callList; // Return the extracted content
                }
            }

        } catch (Exception e) {
            e.printStackTrace(System.out);
            LoggerUtil.logExec(Level.SEVERE, "OpenAI function calling failed: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    private Map<String, Object> getResponseBody(Map<String, Object> requestBody) throws IOException, InterruptedException {
        return executeWithRetry("getResponseBody", () -> {
            ObjectMapper mapper = new ObjectMapper();
            String requestBodyJson = mapper.writeValueAsString(requestBody);
            HttpClient httpClient = buildHttpClient();
            HttpRequest request = buildRequest(requestBodyJson);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP error: " + response.statusCode() + "\n" + 
                    response.body());
            }

            Map<String, Object> responseBody = new HashMap<>();
            String responseBodyString = response.body();
            Arrays.stream(responseBodyString.split("\n")).forEach(line -> {
                if (!line.trim().isEmpty() && !line.startsWith(": keep-alive")) {
                    try {
                        Map<String, Object> lineResponse = mapper.readValue(line, Map.class);
                        responseBody.putAll(lineResponse);
                    } catch (IOException e) {
                        LoggerUtil.logExec(Level.WARNING, "Failed to parse response line: " + line + "\n" + e.getMessage());
                    }
                }
            });

            if (responseBody.isEmpty()) {
                throw new RuntimeException("Server returned empty response");
            }

            return responseBody;
        });
    }

    private void streamResponse(Map<String, Object> requestBody, Consumer<Map<String, Object>> chunkConsumer)
            throws IOException, InterruptedException {
        executeWithRetry("streamResponse", () -> {
            ObjectMapper mapper = new ObjectMapper();
            String requestBodyJson = mapper.writeValueAsString(requestBody);
            HttpClient httpClient = buildHttpClient();
            HttpRequest request = buildRequest(requestBodyJson);

            CompletableFuture<Void> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(response -> {
                        if (response.statusCode() != 200) {
                            throw new RuntimeException("HTTP error: " + response.statusCode() + "\n" +
                                                       response.body().collect(Collectors.joining("\n")));
                        }

                        boolean[] hasValidData = {false};

                        response.body().forEach(line -> {
                            if (isValidDataLine(line)) {
                                try {
                                    if (line.startsWith("data: [DONE]")) return;
                                    hasValidData[0] = true;
                                    String jsonContent = extractJsonContent(line);
                                    Map<String, Object> chunk = mapper.readValue(jsonContent, Map.class);
                                    chunkConsumer.accept(chunk);
                                } catch (IOException e) {
                                    LoggerUtil.logExec(Level.WARNING, "Failed to parse chunk: " + line);
                                }
                            }
                        });

                        if (!hasValidData[0]) {
                            throw new RuntimeException("Stream response was empty");
                        }
                    });

            future.join();
            return null;
        });
    }

    public static String filterThinkingTag(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // Remove <thinking> tags and their content
        return content.replaceAll("<thinking>[\\s\\S]*?</thinking>", "").trim();
    }

}

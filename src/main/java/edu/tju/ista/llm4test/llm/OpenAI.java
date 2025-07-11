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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
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
    private static final long RETRY_DELAY_MS = 60000;

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

    /**
     * 内部类，用于封装流式响应的结果
     */
    private static class StreamedResponse {
        final String content;
        final String thinkingAndContent;

        StreamedResponse(String content, String thinkingAndContent) {
            this.content = content;
            this.thinkingAndContent = thinkingAndContent;
        }
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
        // 最佳实践：为所有网络请求设置一个合理的超时，以防止无限期等待。
        // 这个值理想情况下应该来自 GlobalConfig，此处设置为60秒作为一个安全的默认值。
        final long REQUEST_TIMEOUT_SECONDS = 1800;

        return HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)) // **关键修复：设置请求超时**
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
    }

    private boolean shouldRetry(int statusCode) {
        // 扩展列表以包括 500 Internal Server Error，这是一种常见的可重试瞬时错误。
        return statusCode == 500 || statusCode == 503 || statusCode == 429 || statusCode == 504 || statusCode == 421;
    }

    private boolean shouldRetry(Throwable t) {
        if (!(t instanceof RuntimeException)) {
            return false;
        }

        String message = t.getMessage();
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


    /*     * 发送消息并获取回复 (同步阻塞版本)
     * @param prompt 用户输入的提示
     * @param temperature 控制生成文本的随机性，范围0-1
     * @param jsonOutput 是否需要返回JSON格式的输出
     * @return 返回生成的文本内容
     */
    public String messageCompletion(String prompt, double temperature, boolean jsonOutput) {
        try {
            // 调用异步版本并阻塞等待结果
            return messageCompletionAsync(prompt, temperature, jsonOutput).join();
        } catch (Exception e) {
            // 解包CompletionException以获得更清晰的日志
            Throwable cause = (e instanceof CompletionException) ? e.getCause() : e;
            LoggerUtil.logExec(Level.SEVERE, "OpenAI message completion failed: " + cause.getMessage());
            cause.printStackTrace(System.err);
            return "";
        }
    }

    /**
     * 异步发送消息并获取回复 (非阻塞版本)
     * @param prompt 用户输入的提示
     * @param temperature 控制生成文本的随机性，范围0-1
     * @param jsonOutput 是否需要返回JSON格式的输出
     * @return CompletableFuture<String>，最终将包含回复内容
     */
    public CompletableFuture<String> messageCompletionAsync(String prompt, double temperature, boolean jsonOutput) {
        try {
            if (jsonOutput && !prompt.contains(" json ")) {
                prompt += "\nPlease return the response in json format.";
            }
            Map<String, Object> requestBody = getBaseRequestMap(prompt);
            if (jsonOutput) {
                requestBody.put("response_format", Map.of("type", "json_object"));
            }
            requestBody.put("temperature", temperature);

            if (STREAM) {
                return executeWithRetryAsync("streamResponse", () -> streamResponseAsync(requestBody), 0)
                        .thenApply(streamedResponse -> {
                            LoggerUtil.logOpenAI(Level.INFO, "OpenAI async response: \n" + streamedResponse.thinkingAndContent);
                            return streamedResponse.content;
                        });
            } else {
                return executeWithRetryAsync("getResponseBody", () -> getResponseBodyAsync(requestBody), 0)
                        .thenApply(responseBody -> {
                            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                            if (choices != null && !choices.isEmpty()) {
                                Map<String, Object> choice = choices.get(0);
                                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                                if (message != null) {
                                    String content = (String) message.get("content");
                                    String thinking = (String) message.get("reasoning_content");
                                    LoggerUtil.logOpenAI(Level.FINE, "OpenAI async response: \n" + "<thinking>\n" + thinking + "\n</thinking>\n\n" + content);
                                    return content;
                                }
                            }
                            return "";
                        });
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to start OpenAI message completion async: " + e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }


    /**
     * 执行函数调用 (同步阻塞版本)
     */
    public List<ToolCall> funcCall(String prompt, List<Tool<?>> tools) {
        try {
            // 调用异步版本并阻塞等待结果
            return funcCallAsync(prompt, tools).join();
        } catch (Exception e) {
            // 解包CompletionException
            Throwable cause = (e instanceof CompletionException) ? e.getCause() : e;
            cause.printStackTrace(System.out);
            LoggerUtil.logExec(Level.SEVERE, "OpenAI function calling failed: " + cause.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 异步执行函数调用 (非阻塞版本)
     */
    public CompletableFuture<List<ToolCall>> funcCallAsync(String prompt, List<Tool<?>> tools) {
        try {
            Map<String, Object> requestBody = getBaseRequestMap(prompt);
            requestBody.put("tools", ToolFactory.toToolsArray(tools));
            requestBody.put("stream", false);
            requestBody.put("max_tokens", 4096);
            requestBody.put("model", MODEL);

            return executeWithRetryAsync("funcCall", () -> getResponseBodyAsync(requestBody), 0)
                    .thenApply(responseBody -> {
                        LoggerUtil.logOpenAI(Level.INFO, "OpenAI async response Body : \n" + responseBody);
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                        if (choices != null && !choices.isEmpty()) {
                            Map<String, Object> choice = choices.get(0);
                            Map<String, Object> message = (Map<String, Object>) choice.get("message");
                            if (message != null) {
                                String content = (String) message.get("content");
                                if (message.get("reasoning_content") != null) {
                                    String reasoningContent = (String) message.get("reasoning_content");
                                    content = "<thinking>\n" + reasoningContent + "\n</thinking>\n\n" + content;
                                }
                                ArrayList<HashMap<String, Object>> toolCalls = (ArrayList<HashMap<String, Object>>) message.getOrDefault("tool_calls", new ArrayList<>());
                                ArrayList<ToolCall> callList = toolCalls.stream().map(call -> (HashMap<String, Object>) call.get("function"))
                                        .map(func -> {
                                            try {
                                                return new ToolCall((String) func.get("name"), (String) func.get("arguments"));
                                            } catch (JsonProcessingException e) {
                                                LoggerUtil.logExec(Level.WARNING, "Failed to parse function call arguments: " + e.getMessage());
                                                return null;
                                            }
                                        }).filter(Objects::nonNull)
                                        .collect(Collectors.toCollection(ArrayList::new));

                                LoggerUtil.logOpenAI(Level.INFO, "OpenAI response: \n" + "content :\n" + content + "\nfunction calling: \n" + callList);
                                return callList;
                            }
                        }
                        return new ArrayList<ToolCall>();
                    });
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to start OpenAI function calling async: " + e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 异步、非阻塞地执行网络请求，并带有重试逻辑
     */
    private <T> CompletableFuture<T> executeWithRetryAsync(
            String operationName, Supplier<CompletableFuture<T>> operation, int attempt) {

        return operation.get().handle((result, ex) -> {
            if (ex != null) {
                // 解包 CompletionException
                Throwable cause = (ex instanceof CompletionException) ? ex.getCause() : ex;

                if (attempt < MAX_RETRIES && shouldRetry(cause)) {
                    LoggerUtil.logExec(Level.WARNING,
                            String.format("Async retry attempt %d/%d for %s after %dms",
                                    attempt + 1, MAX_RETRIES, operationName, RETRY_DELAY_MS));

                    // 使用延迟执行器进行非阻塞等待
                    return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(RETRY_DELAY_MS, TimeUnit.MILLISECONDS))
                            .thenCompose(v -> executeWithRetryAsync(operationName, operation, attempt + 1));
                } else {
                    // 达到最大重试次数或遇到不可重试的错误
                    return CompletableFuture.<T>failedFuture(cause);
                }
            }
            // 成功
            return CompletableFuture.completedFuture(result);
        }).thenCompose(Function.identity()); // 展开嵌套的CompletableFuture
    }

    /**
     * 异步获取完整的响应体 (非流式)
     */
    private CompletableFuture<Map<String, Object>> getResponseBodyAsync(Map<String, Object> requestBody) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String requestBodyJson = mapper.writeValueAsString(requestBody);
            HttpClient httpClient = buildHttpClient();
            HttpRequest request = buildRequest(requestBodyJson);

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new CompletionException(new RuntimeException("HTTP error: " + response.statusCode() + "\n" +
                                    response.body()));
                        }

                        Map<String, Object> responseBodyMap = new HashMap<>();
                        String responseBodyString = response.body();
                        Arrays.stream(responseBodyString.split("\n")).forEach(line -> {
                            if (!line.trim().isEmpty() && !line.startsWith(": keep-alive")) {
                                try {
                                    Map<String, Object> lineResponse = mapper.readValue(line, Map.class);
                                    responseBodyMap.putAll(lineResponse);
                                } catch (IOException e) {
                                    LoggerUtil.logExec(Level.WARNING, "Failed to parse response line: " + line + "\n" + e.getMessage());
                                }
                            }
                        });

                        if (responseBodyMap.isEmpty()) {
                            throw new CompletionException(new RuntimeException("Server returned empty response"));
                        }
                        return responseBodyMap;
                    });
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * 异步处理流式响应，并将结果聚合后返回
     */
    private CompletableFuture<StreamedResponse> streamResponseAsync(Map<String, Object> requestBody) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String requestBodyJson = mapper.writeValueAsString(requestBody);
            HttpClient httpClient = buildHttpClient();
            HttpRequest request = buildRequest(requestBodyJson);

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new CompletionException(new RuntimeException("HTTP error: " + response.statusCode() + "\n" +
                                    response.body().collect(Collectors.joining("\n"))));
                        }

                        StringBuilder reasonSb = new StringBuilder();
                        StringBuilder contentSb = new StringBuilder();
                        boolean[] hasValidData = {false};

                        response.body().forEach(line -> {
                            if (isValidDataLine(line)) {
                                try {
                                    if (line.startsWith("data: [DONE]")) return;
                                    hasValidData[0] = true;
                                    String jsonContent = extractJsonContent(line);
                                    Map<String, Object> chunk = mapper.readValue(jsonContent, Map.class);

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
                                } catch (IOException e) {
                                    LoggerUtil.logExec(Level.WARNING, "Failed to parse chunk: " + line);
                                }
                            }
                        });

                        if (!hasValidData[0]) {
                            throw new CompletionException(new RuntimeException("Stream response was empty"));
                        }

                        String fullLog = "<thinking>\n" + reasonSb + "\n</thinking>\n\n" + contentSb;
                        return new StreamedResponse(contentSb.toString(), fullLog);
                    });
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public static String filterThinkingTag(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // Remove <thinking> tags and their content
        return content.replaceAll("<thinking>[\\s\\S]*?</thinking>", "").trim();
    }

}

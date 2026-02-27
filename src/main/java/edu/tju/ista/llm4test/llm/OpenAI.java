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

import edu.tju.ista.llm4test.config.ConfigError;
import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.llm.tools.Tool;
import edu.tju.ista.llm4test.llm.tools.ToolFactory;
import edu.tju.ista.llm4test.llm.tools.ToolCall;
import edu.tju.ista.llm4test.llm.tools.ToolRegistry;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import edu.tju.ista.llm4test.config.ModelConfig;

/*
 *  OpenAI style LLM api calling.
 */
public class OpenAI {
    private final String API_KEY;

    private String BASE_URL;

    private String MODEL;

    private double TEMPERATURE = 0.7;

    private int MAX_TOKENS = 8192;
    private boolean STREAM = true;

    private boolean JSON_OUTPUT = false;

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 60000;
    private static final int MAX_TOOL_CALL_ROUNDS = 10;

    /** Shared HttpClient instance, lazily initialized per OpenAI instance. */
    private volatile HttpClient sharedHttpClient;

    public enum ToolCallRequirement {
        REQUIRED, // Must contain tool call, or retry (up to 3 times)
        AUTO      // Optional tool call, no retry
    }

    public static class NoToolCallException extends RuntimeException {
        public final String content;
        public final String reasoningContent;
        public NoToolCallException(String message, String content, String reasoningContent) {
            super(message);
            this.content = content;
            this.reasoningContent = reasoningContent;
        }
    }


    public String messageCompletion(String prompt) {
        return messageCompletion(prompt, this.TEMPERATURE);
    }


    public OpenAI() {
        API_KEY = GlobalConfig.getOpenaiApiKey();
        BASE_URL = GlobalConfig.getOpenaiBaseUrl();
        MODEL = GlobalConfig.getOpenaiModel();
        if (GlobalConfig.isEnableAblationTest()) {
            this.TEMPERATURE = 0.0;
        }
    }

    public OpenAI(String modelName) {
        ModelConfig config = null;
        try {
            // Try to load from JSON configuration
            config = edu.tju.ista.llm4test.config.ModelConfig.getModelByName(modelName);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Failed to get config for: " + modelName);
        }
        if (config != null) {
            this.API_KEY = config.getApiKey();
            this.BASE_URL = config.getBaseUrl();
            this.MODEL = config.getModel();
            this.MAX_TOKENS = config.getMaxTokens();
            this.STREAM = config.isStream();
            this.JSON_OUTPUT = config.isJsonOutput();
        } else {
            this.API_KEY = GlobalConfig.getOpenaiApiKey();
            this.BASE_URL = GlobalConfig.getOpenaiBaseUrl();
            this.MODEL = modelName;
        }
        if (GlobalConfig.isEnableAblationTest()) {
            this.TEMPERATURE = 0.0;
        }
        LoggerUtil.logExec(Level.INFO, "OpenAI client initialized - Model: " + MODEL + ", BaseURL: " + BASE_URL);
    }

    public OpenAI(String API_KEY, String BASE_URL, String modelName) {
        this.API_KEY = API_KEY;
        this.BASE_URL = BASE_URL;
        MODEL = modelName;
    }

    public OpenAI(edu.tju.ista.llm4test.config.ModelConfig config) {
        if (config == null) {
            API_KEY = GlobalConfig.getOpenaiApiKey();
            BASE_URL = GlobalConfig.getOpenaiBaseUrl();
            MODEL = GlobalConfig.getOpenaiModel();
            return;
        }
        this.API_KEY = config.getApiKey();
        this.BASE_URL = config.getBaseUrl();
        this.MODEL = config.getModel();
        this.MAX_TOKENS = config.getMaxTokens();
        this.STREAM = config.isStream();
        this.JSON_OUTPUT = config.isJsonOutput();
        if (GlobalConfig.isEnableAblationTest()) {
            this.TEMPERATURE = 0.0;
        } else {
            // Only set temperature from config if not in ablation test mode
            this.TEMPERATURE = config.getTemperature();
        }
        LoggerUtil.logExec(Level.INFO, "OpenAI client initialized - Model: " + MODEL + ", BaseURL: " + BASE_URL);
    }

    public static OpenAI ThinkingModel;
    public static OpenAI V3;
    public static OpenAI FlashModel; // fast and cheap
    public static OpenAI DoubaoThinking; // more powerful
    public static OpenAI AgentModel;

    static {
        try {
            java.util.Map<String, edu.tju.ista.llm4test.config.ModelConfig> models =
                edu.tju.ista.llm4test.config.ModelConfig.getModelsMap();
            ThinkingModel = new OpenAI(models.get("deepseek-reasoner"));
            V3 = new OpenAI(models.get("deepseek-chat"));
            FlashModel = new OpenAI(models.get("doubao-flash"));
            DoubaoThinking = new OpenAI(models.get("doubao-thinking"));
            AgentModel = new OpenAI(models.get("k2"));

            if (GlobalConfig.isUseFlash() && models.containsKey("doubao-flash")) {
                DoubaoThinking = new OpenAI(models.get("doubao-flash"));
                ThinkingModel = new OpenAI(models.get("doubao-flash"));
                V3 = new OpenAI(models.get("doubao-flash"));
                AgentModel = new OpenAI(models.get("doubao-flash"));
            }

        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Can not find LLM config!!!");
            throw new ConfigError("Can not find LLM config, make sure you have config.json!!!");
        }
    }

    /**
     * 内部类，用于封装流式响应的结果
     */
    private static class StreamedResponse {
        final String content;
        final String thinkingAndContent;
        final RequestTokenUsage tokenUsage;

        StreamedResponse(String content, String thinkingAndContent, RequestTokenUsage tokenUsage) {
            this.content = content;
            this.thinkingAndContent = thinkingAndContent;
            this.tokenUsage = tokenUsage;
        }
    }

    /**
     * 内部类，用于封装流式工具调用响应的结果
     */
    private static class StreamedToolCallResponse {
        final List<ToolCall> toolCalls;
        final String content;
        final String reasoningContent;
        final String thinkingAndContent;
        final RequestTokenUsage tokenUsage;

        StreamedToolCallResponse(List<ToolCall> toolCalls, String content, String reasoningContent, String thinkingAndContent, RequestTokenUsage tokenUsage) {
            this.toolCalls = toolCalls;
            this.content = content;
            this.reasoningContent = reasoningContent;
            this.thinkingAndContent = thinkingAndContent;
            this.tokenUsage = tokenUsage;
        }
    }


    private Map<String, Object> getBaseRequestMap(String prompt, double requestTemperature) {
        return getBaseRequestMapWithMessages(
                List.of(Map.of("role", "user", "content", prompt)),
                requestTemperature
        );
    }

    /**
     * Build a request body with a full message list (supports multi-turn conversations).
     */
    private Map<String, Object> getBaseRequestMapWithMessages(List<Map<String, Object>> messages, double requestTemperature) {
        Map<String, Object> requestBody = new HashMap<>();
        if (this.JSON_OUTPUT) {
            requestBody.put("response_format", Map.of("type", "json_object"));
            // Check last user message for json hint
            String lastContent = messages.stream()
                    .filter(m -> "user".equals(m.get("role")))
                    .reduce((first, second) -> second)
                    .map(m -> (String) m.get("content"))
                    .orElse("");
            if (!lastContent.contains("json")) {
                List<Map<String, Object>> updatedMessages = new ArrayList<>(messages);
                updatedMessages.add(Map.of("role", "user", "content", "Please return the response in json format."));
                messages = updatedMessages;
            }
        }
        requestBody.put("model", MODEL);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", MAX_TOKENS);

        double finalTemperature = GlobalConfig.isEnableAblationTest() ? 0.0 : requestTemperature;
        requestBody.put("temperature", finalTemperature);
        requestBody.put("stream", STREAM);
        if (STREAM) {
            requestBody.put("stream_options", Map.of("include_usage", true));
        }
        return requestBody;
    }

    protected HttpClient buildHttpClient() {
        if (sharedHttpClient != null) {
            return sharedHttpClient;
        }
        synchronized (this) {
            if (sharedHttpClient != null) {
                return sharedHttpClient;
            }
            if (GlobalConfig.isProxyEnabled()) {
                String proxyHost = GlobalConfig.getProxyHost();
                int proxyPort = GlobalConfig.getProxyPort();

                if (!proxyHost.isEmpty() && proxyPort > 0) {
                    sharedHttpClient = HttpClient.newBuilder()
                            .proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)))
                            .build();
                    return sharedHttpClient;
                }
            }
            sharedHttpClient = HttpClient.newHttpClient();
            return sharedHttpClient;
        }
    }

    /**
     * Resolve the full API endpoint URL from BASE_URL.
     * If BASE_URL already ends with a complete path (e.g., /chat/completions), use it directly.
     * Otherwise, append /chat/completions.
     */
    private String getEndpointUrl() {
        if (BASE_URL.endsWith("/chat/completions")) {
            return BASE_URL;
        }
        String base = BASE_URL.endsWith("/") ? BASE_URL : BASE_URL + "/";
        return base + "chat/completions";
    }

    private HttpRequest buildRequest(String requestBodyJson) {
        final long REQUEST_TIMEOUT_SECONDS = 1800;

        return HttpRequest.newBuilder()
                .uri(URI.create(getEndpointUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();
    }

    private boolean shouldRetry(int statusCode) {
        // 扩展列表以包括 500 Internal Server Error，这是一种常见的可重试瞬时错误。
        return statusCode == 500 || statusCode == 503 || statusCode == 429 || statusCode == 504 || statusCode == 421;
    }

    private boolean shouldRetry(Throwable t) {
        if (t instanceof NoToolCallException) {
            return true;
        }
        String message = t.getMessage();
        if (message == null) {
            return false;
        }

        // 检查是否是网络连接错误（包括SSL握手失败）
        if (isNetworkError(message)) {
            return true;
        }

        // 检查是否是 RuntimeException 的其他情况
        if (t instanceof RuntimeException) {
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
        }

        // 检查是否是 IOException 或 InterruptedException 的网络相关错误
        if (t instanceof IOException || t instanceof InterruptedException) {
            return isNetworkError(message);
        }

        return false;
    }

    private boolean isNetworkError(String message) {
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("handshake close") ||
               lowerMessage.contains("connection reset") ||
               lowerMessage.contains("connection closed") ||
               lowerMessage.contains("ssl handshake failed") ||
               lowerMessage.contains("network is unreachable") ||
               lowerMessage.contains("connection timed out") ||
               lowerMessage.contains("connect timed out") ||
               lowerMessage.contains("read timed out") ||
               lowerMessage.contains("broken pipe") ||
               lowerMessage.contains("connection refused") ||
               lowerMessage.contains("no route to host") ||
               lowerMessage.contains("socket timeout") ||
               lowerMessage.contains("premature close") ||
               lowerMessage.contains("unexpected end of file") ||
               lowerMessage.contains("tunnel failed") ||
               lowerMessage.contains("tunnel connection failed") ||
               lowerMessage.contains("proxy tunnel failed") ||
               lowerMessage.contains("unable to tunnel through proxy");
    }

    /**
     * 生成随机的重试延迟时间（60-120秒之间）
     * 使用ThreadLocalRandom确保线程安全和高性能
     * @return 随机延迟时间（毫秒）
     */
    protected long getRandomRetryDelay() {
        return ThreadLocalRandom.current().nextLong(RETRY_DELAY_MS) + RETRY_DELAY_MS;
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
            Map<String, Object> requestBody = getBaseRequestMap(prompt, temperature);
            if (jsonOutput) {
                requestBody.put("response_format", Map.of("type", "json_object"));
            }

            if (STREAM) {
                return executeWithRetryAsync("streamResponse", () -> streamResponseAsync(requestBody), 0)
                        .thenApply(streamedResponse -> {
                            LoggerUtil.logOpenAI(Level.INFO, "OpenAI async response: \n" + streamedResponse.thinkingAndContent);
                            TokenUsageTracker.getInstance().record(MODEL, streamedResponse.tokenUsage);
                            return streamedResponse.content;
                        });
            } else {
                return executeWithRetryAsync("getResponseBody", () -> getResponseBodyAsync(requestBody), 0)
                        .thenApply(responseBody -> {
                            TokenUsageTracker.getInstance().record(MODEL, RequestTokenUsage.fromUsageObject(responseBody.get("usage")));
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                            if (choices != null && !choices.isEmpty()) {
                                                                Map<String, Object> choice = choices.get(0);
                                @SuppressWarnings("unchecked")
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


    public List<ToolCall> toolCall(String prompt, List<Tool<?>> tools) {
        return toolCall(prompt, tools, ToolCallRequirement.REQUIRED);
    }

    public List<ToolCall> toolCall(String prompt, List<Tool<?>> tools, ToolCallRequirement requirement) {
        try {
            return toolCallAsync(prompt, tools, requirement).join();
        } catch (Exception e) {
            Throwable cause = (e instanceof CompletionException) ? e.getCause() : e;
            LoggerUtil.logExec(Level.SEVERE, "OpenAI function calling failed: " + cause.getMessage());
            return new ArrayList<>();
        }
    }

    public record ToolCallResult(List<ToolCall> toolCalls, String content, String reasoningContent) {}

    public ToolCallResult toolCallWithContent(String prompt, List<Tool<?>> tools) {
        return toolCallWithContent(prompt, tools, ToolCallRequirement.REQUIRED);
    }

    public ToolCallResult toolCallWithContent(String prompt, List<Tool<?>> tools, ToolCallRequirement requirement) {
        try {
            return toolCallWithContentAsync(prompt, tools, requirement).join();
        } catch (Exception e) {
            Throwable cause = (e instanceof CompletionException) ? e.getCause() : e;
            if (cause instanceof NoToolCallException) {
                LoggerUtil.logExec(Level.WARNING, "OpenAI function calling failed after retries: " + cause.getMessage());
                NoToolCallException noToolEx = (NoToolCallException) cause;
                return new ToolCallResult(new ArrayList<>(), noToolEx.content, noToolEx.reasoningContent);
            }
            LoggerUtil.logExec(Level.SEVERE, "OpenAI function calling failed: " + cause.getMessage());
            return new ToolCallResult(new ArrayList<>(), "", "");
        }
    }

    private List<ToolCall> parseToolCalls(Object toolCallsObj) {
        List<ToolCall> result = new ArrayList<>();
        if (toolCallsObj instanceof List) {
            List<?> toolCalls = (List<?>) toolCallsObj;
            for (Object call : toolCalls) {
                if (call instanceof Map) {
                    Map<?, ?> callMap = (Map<?, ?>) call;
                    Map<?, ?> function = (Map<?, ?>) callMap.get("function");
                    if (function != null) {
                        try {
                            result.add(new ToolCall((String) function.get("name"), (String) function.get("arguments")));
                        } catch (JsonProcessingException e) {
                            LoggerUtil.logExec(Level.WARNING, "Failed to parse function call arguments: " + e.getMessage());
                        }
                    }
                }
            }
        }
        return result;
    }


    public CompletableFuture<ToolCallResult> toolCallWithContentAsync(String prompt, List<Tool<?>> tools, ToolCallRequirement requirement) {
        try {
            Map<String, Object> requestBody = getBaseRequestMap(prompt, this.TEMPERATURE); // Pass the instance's temperature
            requestBody.put("tools", ToolFactory.toToolsArray(tools));

            if (STREAM) {
                return executeWithRetryAsync(
                        "streamToolCallResponse",
                        () -> streamToolCallResponseAsync(requestBody).thenApply(streamedResult -> {
                            if (requirement == ToolCallRequirement.REQUIRED && (streamedResult.toolCalls == null || streamedResult.toolCalls.isEmpty())) {
                                throw new NoToolCallException("Response did not contain required tool calls.", streamedResult.content, streamedResult.reasoningContent);
                            }
                            return streamedResult;
                        }),
                        0
                ).thenApply(streamedResult -> {
                    LoggerUtil.logOpenAI(Level.INFO, "OpenAI async tool call response: \n" + streamedResult.thinkingAndContent);
                    TokenUsageTracker.getInstance().record(MODEL, streamedResult.tokenUsage);
                    return new ToolCallResult(streamedResult.toolCalls, streamedResult.content, streamedResult.reasoningContent);
                });
            } else {
                return executeWithRetryAsync(
                        "toolCallWithContent",
                        () -> getResponseBodyAsync(requestBody).thenApply(responseBody -> {
                            TokenUsageTracker.getInstance().record(MODEL, RequestTokenUsage.fromUsageObject(responseBody.get("usage")));
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                            if (choices != null && !choices.isEmpty()) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                                String content = (String) message.get("content");
                                String reasoning = (String) message.get("reasoning_content");
                                List<ToolCall> calls = parseToolCalls(message.get("tool_calls"));

                                if (requirement == ToolCallRequirement.REQUIRED && (calls == null || calls.isEmpty())) {
                                    throw new NoToolCallException("Response did not contain required tool calls.", content, reasoning);
                                }

                                return new ToolCallResult(calls, content != null ? content : "", reasoning != null ? reasoning : "");
                            }
                            if (requirement == ToolCallRequirement.REQUIRED) {
                                throw new NoToolCallException("Response was empty and did not contain required tool calls.", "", "");
                            }
                            return new ToolCallResult(new ArrayList<>(), "", "");
                        }),
                        0
                );
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Failed to start OpenAI function calling async: " + e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<List<ToolCall>> toolCallAsync(String prompt, List<Tool<?>> tools, ToolCallRequirement requirement) {
        return toolCallWithContentAsync(prompt, tools, requirement)
                .thenApply(ToolCallResult::toolCalls);
    }

    private <T> CompletableFuture<T> executeWithRetryAsync(
            String operationName, Supplier<CompletableFuture<T>> operation, int attempt) {

        return operation.get().handle((result, ex) -> {
            if (ex != null) {
                // 解包 CompletionException
                Throwable cause = (ex instanceof CompletionException) ? ex.getCause() : ex;

                if (attempt < MAX_RETRIES && shouldRetry(cause)) {
                    long randomDelay = getRandomRetryDelay();
                    LoggerUtil.logExec(Level.WARNING,
                            String.format("Async retry attempt %d/%d for %s due to %s. Retrying after %dms.",
                                    attempt + 1, MAX_RETRIES, operationName, cause.getClass().getSimpleName(), randomDelay));

                    // 使用延迟执行器进行非阻塞等待
                    return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(randomDelay, TimeUnit.MILLISECONDS))
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
    @SuppressWarnings("unchecked")
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
    @SuppressWarnings("unchecked")
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
                        final RequestTokenUsage[] tokenUsage = {RequestTokenUsage.empty()};

                        response.body().forEach(line -> {
                            if (isValidDataLine(line)) {
                                try {
                                    if (line.startsWith("data: [DONE]")) return;
                                    hasValidData[0] = true;
                                    String jsonContent = extractJsonContent(line);
                                    Map<String, Object> chunk = mapper.readValue(jsonContent, Map.class);

                                    tokenUsage[0] = RequestTokenUsage.fromUsageObject(chunk.get("usage"));
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
                        return new StreamedResponse(contentSb.toString(), fullLog, tokenUsage[0]);
                    });
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 异步处理流式工具调用响应，并将结果聚合后返回
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<StreamedToolCallResponse> streamToolCallResponseAsync(Map<String, Object> requestBody) {
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
                        final RequestTokenUsage[] tokenUsage = {RequestTokenUsage.empty()};

                        // 用于聚合流式工具调用的数据结构
                        Map<Integer, String> toolCallNames = new HashMap<>();
                        Map<Integer, StringBuilder> toolCallArgsBuilders = new HashMap<>();

                        response.body().forEach(line -> {
                            if (isValidDataLine(line)) {
                                try {
                                    if (line.startsWith("data: [DONE]")) return;
                                    hasValidData[0] = true;
                                    String jsonContent = extractJsonContent(line);
                                    Map<String, Object> chunk = mapper.readValue(jsonContent, Map.class);

                                    tokenUsage[0] = RequestTokenUsage.fromUsageObject(chunk.get("usage"));
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

                                            // 从 delta 聚合工具调用
                                            if (message.containsKey("tool_calls")) {
                                                List<Map<String, Object>> toolCallsDeltas = (List<Map<String, Object>>) message.get("tool_calls");
                                                for (Map<String, Object> delta : toolCallsDeltas) {
                                                    Integer index = (Integer) delta.get("index");
                                                    if (delta.containsKey("function")) {
                                                        Map<String, String> functionDelta = (Map<String, String>) delta.get("function");
                                                        if (functionDelta.containsKey("name")) {
                                                            toolCallNames.put(index, functionDelta.get("name"));
                                                        }
                                                        if (functionDelta.containsKey("arguments")) {
                                                            toolCallArgsBuilders.computeIfAbsent(index, k -> new StringBuilder()).append(functionDelta.get("arguments"));
                                                        }
                                                    }
                                                }
                                            }
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

                        List<ToolCall> finalToolCalls = new ArrayList<>();
                        for (Integer index : toolCallNames.keySet()) {
                            try {
                                String name = toolCallNames.get(index);
                                String arguments = toolCallArgsBuilders.getOrDefault(index, new StringBuilder()).toString();
                                finalToolCalls.add(new ToolCall(name, arguments));
                            } catch (JsonProcessingException e) {
                                LoggerUtil.logExec(Level.WARNING, "Failed to parse aggregated tool call arguments for tool '" + toolCallNames.get(index) + "': " + e.getMessage());
                            }
                        }

                        String fullLog = "<thinking>\n" + reasonSb + "\n</thinking>\n\n" + contentSb;
                        return new StreamedToolCallResponse(finalToolCalls, contentSb.toString(), reasonSb.toString(), fullLog, tokenUsage[0]);
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

    /**
     * Result of a tool call feedback loop, containing the final LLM response
     * after all tool calls have been executed and their results fed back.
     */
    public record ToolCallFeedbackResult(String content, String reasoningContent, List<ToolCallRoundInfo> rounds) {}

    /**
     * Information about a single round in the tool call feedback loop.
     */
    public record ToolCallRoundInfo(int round, List<ToolCall> toolCalls, Map<String, String> toolResults) {}

    /**
     * Execute a full tool call feedback loop:
     * 1. Send prompt with tool definitions to the LLM
     * 2. If the LLM returns tool calls, execute them
     * 3. Feed tool results back to the LLM
     * 4. Repeat until the LLM returns a final text response (no more tool calls)
     *
     * @param prompt     the user prompt
     * @param tools      the available tools
     * @param registry   the tool registry for executing tool calls
     * @return ToolCallFeedbackResult with the final response and all round info
     */
    public ToolCallFeedbackResult toolCallWithFeedback(String prompt, List<Tool<?>> tools, ToolRegistry registry) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", prompt));

        List<ToolCallRoundInfo> rounds = new ArrayList<>();

        for (int round = 0; round < MAX_TOOL_CALL_ROUNDS; round++) {
            Map<String, Object> requestBody = getBaseRequestMapWithMessages(messages, this.TEMPERATURE);
            requestBody.put("tools", ToolFactory.toToolsArray(tools));

            // Make the LLM call
            ToolCallResult result;
            try {
                if (STREAM) {
                    StreamedToolCallResponse streamed = executeWithRetryAsync(
                            "streamToolCallFeedback",
                            () -> streamToolCallResponseAsync(requestBody),
                            0
                    ).join();
                    result = new ToolCallResult(streamed.toolCalls, streamed.content, streamed.reasoningContent);
                } else {
                    result = executeWithRetryAsync(
                            "toolCallFeedback",
                            () -> getResponseBodyAsync(requestBody).thenApply(responseBody -> {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                                if (choices != null && !choices.isEmpty()) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                                    String content = (String) message.get("content");
                                    String reasoning = (String) message.get("reasoning_content");
                                    List<ToolCall> calls = parseToolCalls(message.get("tool_calls"));
                                    return new ToolCallResult(calls, content != null ? content : "", reasoning != null ? reasoning : "");
                                }
                                return new ToolCallResult(new ArrayList<>(), "", "");
                            }),
                            0
                    ).join();
                }
            } catch (Exception e) {
                Throwable cause = (e instanceof CompletionException) ? e.getCause() : e;
                LoggerUtil.logExec(Level.SEVERE, "Tool call feedback loop failed at round " + (round + 1) + ": " + cause.getMessage());
                return new ToolCallFeedbackResult("", "", rounds);
            }

            // If no tool calls, we have the final response
            if (result.toolCalls() == null || result.toolCalls().isEmpty()) {
                LoggerUtil.logOpenAI(Level.INFO, "Tool call feedback completed after " + (round + 1) + " round(s). Final response received.");
                return new ToolCallFeedbackResult(result.content(), result.reasoningContent(), rounds);
            }

            // Execute tool calls and collect results
            Map<String, String> toolResults = new LinkedHashMap<>();
            // Build assistant message with tool_calls
            List<Map<String, Object>> assistantToolCalls = new ArrayList<>();
            for (int i = 0; i < result.toolCalls().size(); i++) {
                ToolCall tc = result.toolCalls().get(i);
                String toolCallId = "call_" + round + "_" + i;
                Map<String, Object> tcMap = new HashMap<>();
                tcMap.put("id", toolCallId);
                tcMap.put("type", "function");
                try {
                    tcMap.put("function", Map.of("name", tc.toolName, "arguments", new ObjectMapper().writeValueAsString(tc.arguments)));
                } catch (JsonProcessingException e) {
                    tcMap.put("function", Map.of("name", tc.toolName, "arguments", "{}"));
                }
                assistantToolCalls.add(tcMap);
            }

            // Add assistant message
            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            if (result.content() != null && !result.content().isEmpty()) {
                assistantMsg.put("content", result.content());
            }
            assistantMsg.put("tool_calls", assistantToolCalls);
            messages.add(assistantMsg);

            // Execute each tool and add tool result messages
            for (int i = 0; i < result.toolCalls().size(); i++) {
                ToolCall tc = result.toolCalls().get(i);
                String toolCallId = "call_" + round + "_" + i;
                String toolResultStr;

                LoggerUtil.logOpenAI(Level.INFO, "Round " + (round + 1) + " - Executing tool: " + tc.toolName + " with args: " + tc.arguments);

                if (registry.has(tc.toolName)) {
                    Tool<?> tool = registry.get(tc.toolName);
                    try {
                        var response = tool.execute(tc.arguments);
                        toolResultStr = response.isSuccess() ? response.toString() : "Error: " + response.getFailMessage();
                    } catch (Exception e) {
                        toolResultStr = "Error executing tool '" + tc.toolName + "': " + e.getMessage();
                        LoggerUtil.logExec(Level.WARNING, toolResultStr);
                    }
                } else {
                    toolResultStr = "Error: Tool '" + tc.toolName + "' not found in registry.";
                    LoggerUtil.logExec(Level.WARNING, toolResultStr);
                }

                toolResults.put(tc.toolName, toolResultStr);
                LoggerUtil.logOpenAI(Level.INFO, "Round " + (round + 1) + " - Tool result for " + tc.toolName + ": " + toolResultStr.substring(0, Math.min(200, toolResultStr.length())));

                // Add tool result message
                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", toolCallId);
                toolMsg.put("content", toolResultStr);
                messages.add(toolMsg);
            }

            rounds.add(new ToolCallRoundInfo(round + 1, result.toolCalls(), toolResults));
            LoggerUtil.logOpenAI(Level.INFO, "Tool call feedback round " + (round + 1) + " completed. " + result.toolCalls().size() + " tool(s) executed.");
        }

        LoggerUtil.logExec(Level.WARNING, "Tool call feedback loop reached max rounds (" + MAX_TOOL_CALL_ROUNDS + "). Returning last available content.");
        return new ToolCallFeedbackResult("", "", rounds);
    }

    /**
     * Convenience method: execute a tool call feedback loop with a simple tool list.
     * Tools are registered automatically from the provided list.
     */
    public ToolCallFeedbackResult toolCallWithFeedback(String prompt, List<Tool<?>> tools) {
        ToolRegistry registry = new ToolRegistry();
        for (Tool<?> tool : tools) {
            registry.register(tool);
        }
        return toolCallWithFeedback(prompt, tools, registry);
    }

}

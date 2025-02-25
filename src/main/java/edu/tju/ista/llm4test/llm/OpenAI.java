package edu.tju.ista.llm4test.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import edu.tju.ista.llm4test.llm.functionCalling.FuncTool;
import edu.tju.ista.llm4test.llm.functionCalling.FuncToolFactory;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Thread.sleep;

public class OpenAI {
    private final String API_KEY; // Replace with your API key

    private String BASE_URL = "https://api.deepseek.com/beta/v1/chat/completions";
//    private static final String BASE_URL = "https://api.siliconflow.cn/v1/chat/completions";

    private String MODEL = "deepseek-chat";

    private final double TEMPERATURE = 0.3;

    private int MAX_TOKENS = 8192;
    private boolean STREAM = true;


    public String messageCompletion(String prompt) {
        return messageCompletion("You are a helpful assistant", prompt, 0.7);
    }

    public String messageCompletion(String prompt, double temperature) {
        return messageCompletion("You are a helpful assistant", prompt, temperature);
    }

    public OpenAI() {
        API_KEY = System.getenv("OPENAI_API_KEY");
        BASE_URL = System.getenv("OPENAI_BASE_URL");
        MODEL = System.getenv("OPENAI_MODEL");
        if (BASE_URL.isEmpty()) {
            BASE_URL = "https://api.deepseek.com/beta/v1/chat/completions";
        }
        if (MODEL.isEmpty()) {
            MODEL = "deepseek-chat";
        }
        System.out.println("BASE_URL: " + BASE_URL);
        System.out.println("API_KEY: " + API_KEY);
        System.out.println("MODEL: " + MODEL);
    }

    public OpenAI(String modelName) {
        this();
        MODEL = modelName;
    }

    public OpenAI(String API_KEY, String BASE_URL, String modelName) {
        this.API_KEY = API_KEY;
        this.BASE_URL = BASE_URL;
        MODEL = modelName;
    }

    public static OpenAI R1;
    public static OpenAI Doubao;

    static {
        R1 = new OpenAI();
        Doubao = new OpenAI("ep-20250214193558-qh465");
    }


    private Map<String, Object> getBaseRequestMap(String systemPrompt, String prompt) {
        // Create request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL); // Choose the model, e.g., "gpt-4" or "gpt-3.5-turbo"
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("max_tokens", MAX_TOKENS); // Limit the response length
        requestBody.put("temperature", TEMPERATURE); // Set the randomness of the output
        requestBody.put("stream", STREAM);
        return requestBody;
    }

    private Map<String, Object> getResponseBody(Map<String, Object> requestBody) throws IOException, InterruptedException {
        // Convert request body to JSON
        ObjectMapper mapper = new ObjectMapper();
        String requestBodyJson = mapper.writeValueAsString(requestBody);

        // Initialize HttpClient
        HttpClient httpClient = HttpClient.newHttpClient();
        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
            httpClient = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress("172.19.135.130",5000)))
                    .build();
        }


        // Build the HTTP request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL)) // Replace BASE_URL with your API endpoint
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();

        // Send the request and receive the response
        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            if (response.statusCode() == 503 || response.statusCode() == 429) {
                LoggerUtil.logExec(Level.WARNING, "Received HTTP error " + response.statusCode() + ", retrying...");
                sleep(10000);
                return getResponseBody(requestBody);
            } else if (response.version() == HttpClient.Version.HTTP_2 && response.statusCode() == 421) {
                LoggerUtil.logExec(Level.WARNING, "Received GOAWAY frame, retrying...");
                sleep(10000); // Wait a bit before retrying
                return getResponseBody(requestBody);
            } else if (response.statusCode() == 504) {
                LoggerUtil.logExec(Level.WARNING, "Received HTTP error " + response.statusCode() + ", retrying...");
                sleep(10000);
                return getResponseBody(requestBody);
            }
            throw new RuntimeException("HTTP error: " + response.statusCode() + "\n" + response.body().collect(Collectors.joining("\n")));
        }

        // Process the response lines
        Map<String, Object> responseBody = new HashMap<>();
        response.body().forEach(line -> {
            if (!line.trim().isEmpty() && !line.startsWith(": keep-alive")) {
                try {
                    Map<String, Object> lineResponse = mapper.readValue(line, Map.class);
                    responseBody.putAll(lineResponse);
                } catch (IOException e) {
                    LoggerUtil.logExec(Level.WARNING, "Failed to parse response line: " + line + "\n" + e.getMessage());
                }
            }
        });
        return responseBody;
    }


    private void streamResponse(Map<String, Object> requestBody, Consumer<Map<String, Object>> chunkConsumer) throws IOException, InterruptedException {
        ObjectMapper mapper = new ObjectMapper();
        String requestBodyJson = mapper.writeValueAsString(requestBody);

        HttpClient httpClient = buildHttpClient(); // 提取HTTP客户端构建逻辑

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();

        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() != 200) {
            handleErrorResponse(response); // 提取错误处理逻辑
            return;
        }

        // 流式处理核心逻辑
        response.body().forEach(line -> {
//            System.out.println(line);
            if (isValidDataLine(line)) {
                try {
                    if (line.startsWith("data: [DONE]")) return; // 结束标志
                    String jsonContent = extractJsonContent(line);
                    Map<String, Object> chunk = mapper.readValue(jsonContent, Map.class);
                    chunkConsumer.accept(chunk); // 通过回调函数实时输出
                } catch (IOException e) {
                    LoggerUtil.logExec(Level.WARNING, "Failed to parse chunk: " + line);
                }
            }
        });

        System.out.println("Stream response completed " + response.statusCode());
    }

    // 提取的辅助方法
    private static HttpClient buildHttpClient() {
        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
            return HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress("172.19.135.130", 5000)))
                    .build();
        }
        return HttpClient.newHttpClient();
    }

    private static boolean isValidDataLine(String line) {
        return !line.trim().isEmpty()
                && !line.startsWith(": keep-alive")
                && line.startsWith("data: ");
    }

    private static String extractJsonContent(String line) {
        return line.substring(5).trim(); // 去掉"data:"前缀
    }

    private static void handleErrorResponse(HttpResponse<Stream<String>> response) throws InterruptedException {
        String errorBody = response.body().collect(Collectors.joining("\n"));
        if (response.statusCode() == 503 || response.statusCode() == 429 || response.statusCode() == 504) {
            LoggerUtil.logExec(Level.WARNING, "Received HTTP error " + response.statusCode() + ", retrying...");
            sleep(10000);
            // 这里可以添加重试逻辑
        } else {
            throw new RuntimeException("HTTP error: " + response.statusCode() + "\n" + errorBody);
        }
    }


    public String messageCompletion(String systemPrompt, String prompt, double temperature) {
        try {
            Map<String, Object> requestBody = getBaseRequestMap(systemPrompt, prompt);
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
                String result = "<thinking>\n" + reasonSb + "\n<thinking\\>\n" + contentSb;
                LoggerUtil.logOpenAI(Level.INFO, "OpenAI response: \n" + result);
                return result;
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
                    LoggerUtil.logOpenAI(Level.FINE, "OpenAI response: \n" + content);
                    return content; // Return the extracted content
                }
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "OpenAI message completion failed: " + e.getMessage());
            e.printStackTrace(System.err);
        }

        return "";
    }


    public Map<String, String> funcCall(String systemPrompt, String prompt, List<FuncTool> tools) {
        try {
            // Create request body
            Map<String, Object> requestBody = getBaseRequestMap(systemPrompt, prompt);
            requestBody.put("tools", FuncToolFactory.toToolsArray(tools));
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

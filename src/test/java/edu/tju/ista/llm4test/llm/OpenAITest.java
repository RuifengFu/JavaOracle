package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.concurrent.ConcurrentExecutionManager;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Thread.sleep;
import edu.tju.ista.llm4test.llm.tools.Tool;
import com.fasterxml.jackson.annotation.JsonProperty;

public class OpenAITest {

    @Test
    public void testCall() {
        String res = OpenAI.ThinkingModel.messageCompletion("write a snake game");
        System.out.println("Answer: " + res);
    }


    @Test
    public void testExtractCode() {
        String text = OpenAI.FlashModel.messageCompletion("write a quicksort");
        ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
        for (String code: codeBlocks) {
            System.out.println("Code: \n" + code);
        }
    }

    @Test
    public void limitTest() throws InterruptedException {
        var r1 = OpenAI.ThinkingModel;
        var manager = ConcurrentExecutionManager.getInstance();
        AtomicInteger cnt = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // 修复循环条件：简单地运行50次
        for (int i = 0; i < 50; i++) {
            Thread.sleep(1000);
            int finalI = i;
            CompletableFuture<Void> future = manager.submitLLMTask(
                    () -> {
                        System.out.println("Running " + finalI);
                        String res = r1.messageCompletion("write a snake game");

                        if (!res.isEmpty()) {
                            int successCount = cnt.incrementAndGet();
                            System.out.println("Success " + finalI + " (total: " + successCount + ")");
                        } else {
                            int failCount = failed.incrementAndGet();
                            System.out.println("Failed " + finalI + " (total failures: " + failCount + ")");
                        }
                    }
            );
            futures.add(future);
        }
        
        // 等待所有任务完成
        futures.forEach(CompletableFuture::join);
        
        System.out.println("测试完成 - 成功: " + cnt.get() + ", 失败: " + failed.get());
    }

    @Test
    public void threadtest() {

        var manager = ConcurrentExecutionManager.getInstance();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 500; i++) {

            int finalI = i;
            CompletableFuture<Void> future = manager.submitLLMTask(
                () -> {
                    for (int j = 1; j <= 10; j++) {
                        System.out.println(finalI);
                        System.out.flush();
//                        try {
//                            Thread.sleep(5);
//                        } catch (InterruptedException e) {
//                            throw new RuntimeException(e);
//                        }
                    }
                }
            );
            futures.add(future);

        }
        futures.forEach(CompletableFuture::join);
    }

    @Test
    public void testToolCallWithContent() {
        // 为我们的工具定义一个参数类
        class WeatherParameters {
            @JsonProperty("location")
            public String location;

            @JsonProperty("unit")
            public String unit;
        }

        // 创建一个工具实例
        Tool<String> weatherTool = new Tool<>(
        ) {
            @Override
            public String getName() {
                return "name";
            }

            @Override
            public String getDescription() {
                return "a tool you should use to get the current weather in a given location";
            }

            @Override
            public List<String> getParameters() {
                return List.of("location");
            }

            @Override
            public Map<String, String> getParametersDescription() {
                return Map.of(
                        "location", "the city and state, e.g. San Francisco, CA, Beijing, China"
                );
            }

            @Override
            public Map<String, String> getParametersType() {
                return Map.of(
                        "location", "string"                );
            }

            @Override
            public ToolResponse<String> execute(Map<String, Object> args) {
                return ToolResponse.success("success");
            }
        };
        List<Tool<?>> tools = List.of(weatherTool);

        // 提示模型使用该工具
        String prompt = "北京的天气怎么样";

        // 使用支持工具调用的模型，例如 DoubaoFlash
        OpenAI llm = OpenAI.FlashModel;

        // 调用被测试的方法
        OpenAI.ToolCallResult result = llm.toolCallWithContent(prompt, tools);

        // 打印结果以供人工验证
        System.out.println("Content: " + result.content());
        System.out.println("Reasoning: " + result.reasoningContent());
        if (result.toolCalls() != null && !result.toolCalls().isEmpty()) {
            result.toolCalls().forEach(tc -> {
                System.out.println("Tool Call: " + tc.toolName);
                System.out.println("Arguments: " + tc.arguments);
            });
        } else {
            System.out.println("No tool calls were made.");
        }


    }
}

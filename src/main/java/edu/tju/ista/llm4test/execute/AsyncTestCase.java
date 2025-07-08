package edu.tju.ista.llm4test.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.concurrent.AsyncLLMClient;
import edu.tju.ista.llm4test.concurrent.ConcurrentExecutionManager;
import edu.tju.ista.llm4test.llm.functionCalling.FuncTool;
import edu.tju.ista.llm4test.llm.functionCalling.FuncToolFactory;
import edu.tju.ista.llm4test.llm.tools.RootCauseOutputTool;
import edu.tju.ista.llm4test.llm.tools.Tool;
import edu.tju.ista.llm4test.llm.tools.ToolCall;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * 异步测试用例类
 * 
 * 提供异步的测试用例处理功能，包括：
 * - 异步LLM调用（enhance、verify、fix、applyChange）
 * - 并发测试执行
 * - 流水线式处理
 */
public class AsyncTestCase {
    
    public String name;
    public String sourceCode;
    public File originFile;
    public String originTestCase;
    public Map<String, String> apiDocMap; // <sign, doc>
    private String apiDoc;

    public File file;
    public TestResult result;
    public String verifyMessage = "";
    
    // 异步客户端
    private final AsyncLLMClient llmClient;
    private final ConcurrentExecutionManager concurrentManager;
    
    public AsyncTestCase(File file) {
        this.file = file;
        this.name = file.getName().split("\\.")[0];
        this.result = new TestResult(TestResultKind.UNKNOWN);
        this.llmClient = AsyncLLMClient.getInstance();
        this.concurrentManager = ConcurrentExecutionManager.getInstance();
    }

    public AsyncTestCase(File file, TestResult result) {
        this(file);
        this.result = result;
    }

    // Getters and Setters
    public File getOriginFile() {
        return originFile;
    }

    public void setOriginFile(File originFile) {
        this.originFile = originFile;
        try {
            this.originTestCase = Files.readString(originFile.toPath());
        } catch (IOException e) {
            this.originTestCase = "";
        }
    }

    public String getSourceCode() {
        try {
            this.sourceCode = Files.readString(file.toPath());
        } catch (IOException e) {
            this.sourceCode = "";
        }
        return this.sourceCode;
    }

    public void updateApiDoc() {
        StringBuilder sb = new StringBuilder();
        apiDocMap.forEach((k, v) -> {
            sb.append(k).append("\n").append(v).append("\n--------------\n");
        });
        apiDoc = sb.toString();
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    public TestResult getResult() {
        return result;
    }

    public void setResult(TestResult result) {
        this.result = result;
        if (result.isSuccess()) {
            try {
                originTestCase = Files.readString(file.toPath());
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }

    public void setApiDocMap(Map<String, String> apiDocMap) {
        this.apiDocMap = apiDocMap;
        updateApiDoc();
    }

    /**
     * 异步验证测试失败
     */
    public CompletableFuture<Void> verifyTestFailAsync() {
        if (!result.isFail()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String testcase = getTestcaseWithLineNumber();

                Map<String, Object> dataModel = new HashMap<>();
                dataModel.put("testcase", testcase);
                dataModel.put("testOutput", result.toString());
                dataModel.put("apiDocs", apiDoc);

                String prompt = PromptGen.generatePrompt("RootCause", dataModel);
                ArrayList<Tool<?>> tools = new ArrayList<>();
                tools.add(new RootCauseOutputTool());
                
                return llmClient.funcCallAsync(prompt, tools, AsyncLLMClient.ClientType.DOUBAO);
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "构建验证请求失败: " + file + "\n" + e.getMessage());
                return CompletableFuture.<List<ToolCall>>completedFuture(new ArrayList<>());
            }
        }, Runnable::run) // 使用LLM线程池
        .thenCompose(futureResult -> futureResult)
        .thenAccept(list -> {
            try {

                if (!list.isEmpty() && list.get(0).toolName.equals("root_cause_analysis")) {
                    var map = list.get(0).arguments;
                    var reportBug = ((boolean) map.get("report_bug"));
                    if (reportBug) {
                        this.result.setKind(TestResultKind.VERIFIED_BUG);
                    } else {
                        this.result.setKind(TestResultKind.MAYBE_TEST_FAIL);
                    }
                    verifyMessage = map.toString();
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "解析验证结果失败: " + file + "\n" + e.getMessage());
                this.result.setKind(TestResultKind.MAYBE_TEST_FAIL);
            }
        })
        .exceptionally(ex -> {
            LoggerUtil.logExec(Level.WARNING, "验证测试用例失败: " + file + "\n" + ex.getMessage());
            this.result.setKind(TestResultKind.MAYBE_TEST_FAIL);
            return null;
        });
    }

    /**
     * 异步增强测试用例
     */
    public CompletableFuture<Void> enhanceAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> dataModel = new HashMap<>();
                dataModel.put("testcase", getTestcaseWithLineNumber());
                dataModel.put("apiDocs", apiDoc);
                String prompt = PromptGen.generatePrompt("EnhanceTestCase", dataModel);
                
                return llmClient.messageCompletionAsync(prompt, AsyncLLMClient.ClientType.R1);
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "构建增强请求失败: " + file + "\n" + e.getMessage());
                return CompletableFuture.completedFuture("");
            }
        })
        .thenCompose(futureResult -> futureResult)
        .thenAccept(text -> {
            try {
                ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
                if (codeBlocks.isEmpty()) {
                    applyChangeAsync(text).join();
                } else {
                    String generatedCode = codeBlocks.get(codeBlocks.size() - 1);
                    applyChangeAsync(generatedCode).join();
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "应用增强失败: " + file + "\n" + e.getMessage());
            }
        })
        .exceptionally(ex -> {
            LoggerUtil.logExec(Level.WARNING, "增强测试用例失败: " + file + "\n" + ex.getMessage());
            return null;
        });
    }

    /**
     * 异步修复测试用例
     */
    public CompletableFuture<Void> fixAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> dataModel = new HashMap<>();
                dataModel.put("testcase", getTestcaseWithLineNumber());
                dataModel.put("originCase", originTestCase);
                dataModel.put("testOutput", result.toString());
                dataModel.put("apiDocs", apiDoc);
                dataModel.put("rootCause", verifyMessage);
                String prompt = PromptGen.generatePrompt("FixTestCase", dataModel);
                
                return llmClient.messageCompletionAsync(prompt, AsyncLLMClient.ClientType.R1);
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "构建修复请求失败: " + file + "\n" + e.getMessage());
                return CompletableFuture.completedFuture("");
            }
        })
        .thenCompose(futureResult -> futureResult)
        .thenAccept(text -> {
            try {
                ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
                if (codeBlocks.isEmpty()) {
                    applyChangeAsync(text).join();
                } else {
                    String generatedCode = codeBlocks.get(codeBlocks.size() - 1);
                    applyChangeAsync(generatedCode).join();
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "应用修复失败: " + file + "\n" + e.getMessage());
            }
        })
        .exceptionally(ex -> {
            LoggerUtil.logExec(Level.WARNING, "修复测试用例失败: " + file + "\n" + ex.getMessage());
            return null;
        });
    }

    /**
     * 异步应用变更
     */
    public CompletableFuture<Void> applyChangeAsync(String change) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String testcase = getTestcaseWithLineNumber();
                Map<String, Object> dataModel = new HashMap<>();
                dataModel.put("originTestcase", originTestCase);
                dataModel.put("modified", change);
                String prompt = PromptGen.generatePrompt("ApplyChange", dataModel);
                
                return llmClient.messageCompletionAsync(prompt, AsyncLLMClient.ClientType.DOUBAO);
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "构建应用变更请求失败: " + file + "\n" + e.getMessage());
                return CompletableFuture.completedFuture("");
            }
        })
        .thenCompose(futureResult -> futureResult)
        .thenAccept(text -> {
            try {
                ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
                String generatedCode = codeBlocks.get(codeBlocks.size() - 1);
                writeTestCaseToFile(generatedCode);
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "写入变更失败: " + file + "\n" + e.getMessage());
            }
        })
        .exceptionally(ex -> {
            LoggerUtil.logExec(Level.WARNING, "应用变更失败: " + file + "\n" + ex.getMessage());
            return null;
        });
    }

    /**
     * 获取带行号的测试用例内容
     */
    public String getTestcaseWithLineNumber() {
        String testcase = "";
        try {
            testcase = Files.readString(file.toPath());
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "读取测试用例文件失败: " + file + "\n" + e.getMessage());
            e.printStackTrace();
        }
        // 添加行号，大模型在分析代码的时候，没有行号大模型会出现幻觉。
        String[] lines = testcase.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return testcase;
    }

    /**
     * 写入测试用例到文件
     */
    public void writeTestCaseToFile(String content) {
        try {
            Files.writeString(file.toPath(), content);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "写入测试用例文件失败: " + file + "\n" + e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * 带重试的异步修复
     */
    private CompletableFuture<TestResult> fixWithRetryAsync(TestExecutor testExecutor, int maxRetries) {
        return CompletableFuture.supplyAsync(() -> result)
            .thenCompose(currentResult -> {
                if (!currentResult.isFail() || maxRetries <= 0) {
                    return CompletableFuture.completedFuture(currentResult);
                }
                
                return fixAsync()
                    .thenCompose(v -> testExecutor.executeTestAsync(file))
                    .thenCompose(newResult -> {
                        this.setResult(newResult);
                        if (newResult.isFail() && maxRetries > 1) {
                            return fixWithRetryAsync(testExecutor, maxRetries - 1);
                        }
                        return CompletableFuture.completedFuture(newResult);
                    });
            });
    }

    // 同步版本的方法（向后兼容）
    public void verifyTestFail() {
        verifyTestFailAsync().join();
    }

    public void enhance() {
        enhanceAsync().join();
    }

    public void fix() {
        fixAsync().join();
    }

    public void applyChange(String change) {
        applyChangeAsync(change).join();
    }
} 
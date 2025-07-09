package edu.tju.ista.llm4test.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.concurrent.ConcurrentExecutionManager;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.RootCauseOutputTool;
import edu.tju.ista.llm4test.llm.tools.Tool;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.utils.ApiInfoProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TestCase {

    public String name;
    public String sourceCode;
    public File originFile;
    public String originTestCase;
    public Map<String, String> apiDocMap; // <sign, doc>
    private String apiDoc;

    public File file;
    public TestResult result;

    public String verifyMessage = "";
    
    // 并发执行管理器
    private final ConcurrentExecutionManager concurrentManager;
    
    // 临时文件管理 - 只保存实际的临时目录路径，使用线程安全的Map
    private final ConcurrentHashMap<String, String> tempDirectories; // JDK名称 -> 临时目录路径
    
    // API文档处理器 - 用于重新计算API文档
    private ApiInfoProcessor apiInfoProcessor;

    public TestCase(File file){
        this.file = file;
        this.name = file.getName().split("\\.")[0];
        this.result = new TestResult(TestResultKind.UNKNOWN);
        this.concurrentManager = ConcurrentExecutionManager.getInstance();
        this.tempDirectories = new ConcurrentHashMap<>();
    }

    public TestCase(File file, TestResult result){
        this(file);
        this.result = result;
    }

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
        } catch (IOException e){
            this.sourceCode = "";
        }
        return this.sourceCode;
    }

    public void updateApiDoc() {
        StringBuilder sb = new StringBuilder();
        apiDocMap.forEach((k, v) -> {sb.append(k).append("\n").append(v).append("\n--------------\n");});
        apiDoc = sb.toString();
    }

    public String getName() {
        return name;
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

    public String getApiDoc() {
        return apiDoc;
    }

    /**
     * 设置API文档处理器
     * @param apiInfoProcessor API文档处理器
     */
    public void setApiDocProcessor(ApiInfoProcessor apiInfoProcessor) {
        this.apiInfoProcessor = apiInfoProcessor;
        recalculateApiDocs();
    }
    
    /**
     * 重新计算API文档
     * 通常在enhance或fix之后调用，因为代码可能引入了新的API调用
     */
    public void recalculateApiDocs() {
        if (apiInfoProcessor == null || originFile == null) {
            LoggerUtil.logExec(Level.FINE, 
                String.format("无法重新计算API文档 (TestCase: %s) - ApiDocProcessor或originFile为null", name));
            return;
        }
        
        try {
            LoggerUtil.logExec(Level.FINE, 
                String.format("重新计算API文档 (TestCase: %s)", name));
            
            Map<String, String> newApiDocs = apiInfoProcessor.processApiDocs(file);
            
            // 更新API文档映射
            if (newApiDocs != null && !newApiDocs.isEmpty()) {
                this.apiDocMap = newApiDocs;
                updateApiDoc();
                LoggerUtil.logExec(Level.FINE, 
                    String.format("API文档重新计算完成 (TestCase: %s) - 发现 %d 个API", name, newApiDocs.size()));
            } else {
                LoggerUtil.logExec(Level.FINE, 
                    String.format("API文档重新计算完成 (TestCase: %s) - 未发现新API", name));
            }
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, 
                String.format("重新计算API文档失败 (TestCase: %s): %s", name, e.getMessage()));
        }
    }

    /**
     * 异步执行测试用例（CPU密集型任务）
     */
    public CompletableFuture<TestResult> executeTestAsync(TestExecutor testExecutor) {
        return concurrentManager.submitTestTask(() -> {
            LoggerUtil.logExec(Level.INFO, "执行测试用例: " + file);
            TestResult result = testExecutor.executeTest(this);
            this.setResult(result);
            return result;
        });
    }

    /**
     * 异步验证测试失败（LLM调用）
     */
    public CompletableFuture<Void> verifyTestFailAsync() {
        return concurrentManager.submitLLMTask(() -> {
            verifyTestFail();
        });
    }

    /**
     * 异步增强测试用例（LLM调用）
     */
    public CompletableFuture<Void> enhanceAsync() {
        return concurrentManager.submitLLMTask(() -> {
            enhance();
        });
    }

    /**
     * 异步修复测试用例（LLM调用）
     */
    public CompletableFuture<Void> fixAsync() {
        return concurrentManager.submitLLMTask(() -> {
            fix();
        });
    }

    /**
     * 异步应用更改（LLM调用）
     */
    public CompletableFuture<Void> applyChangeAsync(String change) {
        return concurrentManager.submitLLMTask(() -> {
            applyChange(change);
        });
    }

    /**
     * 异步处理增强工作流程：enhance → test → verify → fix
     * 专门用于处理成功的测试用例
     * @param testExecutor 测试执行器
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> processEnhancementWorkflowAsync(TestExecutor testExecutor) {
        return enhanceAsync()
                .thenCompose(v -> executeTestAsync(testExecutor))
                .thenCompose(result -> processTestResultAndFixAsync(result, testExecutor, 0)); // 从第0次尝试开始
    }
    
    /**
     * 处理测试结果的通用逻辑，并支持多次修复尝试
     * @param result 测试结果
     * @param testExecutor 测试执行器
     * @param attempt 当前修复尝试次数
     * @return CompletableFuture<Void>
     */
    private CompletableFuture<Void> processTestResultAndFixAsync(TestResult result, TestExecutor testExecutor, int attempt) {
        LoggerUtil.logResult(Level.INFO, file + " " + result.getKind());
        this.setResult(result); // 确保TestCase对象的result字段是最新的

        if (!result.isFail()) {
            LoggerUtil.logExec(Level.INFO, String.format("测试用例通过，停止修复流程 (TestCase: %s)", name));
            return CompletableFuture.completedFuture(null); // 测试通过，无需修复
        }
        
        // 如果测试失败
        return verifyTestFailAsync().thenCompose(v -> {
            if (this.getResult().isBug()) { // 如果被验证为Bug，停止修复
                LoggerUtil.logExec(Level.INFO, String.format("测试用例被验证为Bug，停止修复 (TestCase: %s)", name));
                return CompletableFuture.completedFuture(null);
            }
            
            if (attempt >= 3) { // 达到最大尝试次数，停止
                LoggerUtil.logExec(Level.INFO, String.format("达到最大修复尝试次数 (%d)，测试用例仍失败 (TestCase: %s)", 3, name));
                return CompletableFuture.completedFuture(null);
            }
            
            LoggerUtil.logExec(Level.INFO, String.format("尝试修复测试用例 (TestCase: %s, 尝试次数: %d/%d)", name, attempt + 1, 3));
            
            return fixAsync()
                    .thenCompose(v2 -> executeTestAsync(testExecutor)) // 修复后重新测试
                    .thenCompose(fixedResult -> processTestResultAndFixAsync(fixedResult, testExecutor, attempt + 1)); // 递归调用进行下一次尝试
        });
    }

    // 同步方法保持不变，供向后兼容
    public void verifyTestFail() {
        if (!result.isFail()) {
            return;
        }
        try {
            String testcase = getTestcaseWithLineNumber();

            Map<String, Object> dataModel = new HashMap<>();
            dataModel.put("testcase", testcase);
            dataModel.put("testOutput", result.toString());
            dataModel.put("apiDocs", apiDoc);

            String prompt = PromptGen.generatePrompt("RootCause", dataModel);
            ArrayList<Tool<?>> tools = new ArrayList<>();
            tools.add(new RootCauseOutputTool());
            var callList = OpenAI.Doubao.funcCall(prompt, tools);
            if (callList.isEmpty()) {
                LoggerUtil.logExec(Level.WARNING, "No function call found in the response");
                this.result.setKind(TestResultKind.MAYBE_TEST_FAIL);
                return;
            }
            var rootCauseCall = callList.get(0);
            var arguments = rootCauseCall.arguments;
            var reportBug = ((boolean) arguments.get("report_bug"));
            if (reportBug) {
                this.result.setKind(TestResultKind.VERIFIED_BUG);
            } else {
                this.result.setKind(TestResultKind.MAYBE_TEST_FAIL);
            }
            verifyMessage = arguments.toString();
        } catch(Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Verifying test case failed: " + file + "\n" + e.getMessage());
            this.result.setKind(TestResultKind.MAYBE_TEST_FAIL);
        }
    }

    /**
     * Get the content of the testcase file
     * @return the content of the testcase file
     */
    public String getTestcaseWithLineNumber() {
        // read file into String
        String testcase = "";
        try {
            testcase = Files.readString(file.toPath());
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "Writing test case to file failed: " + file + "\n" + e.getMessage());
            e.printStackTrace();
        }
        // add line number, 大模型在分析代码的时候，没有行号大模型会出现幻觉。
        String[] lines = testcase.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return testcase;
    }

    public void writeTestCaseToFile(String content) {
        try {
            Files.writeString(file.toPath(), content);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "Writing test case to file failed: " + file + "\n" + e.getMessage());
            e.printStackTrace();
        }
    }

    public void fix() {
        try {
            Map<String, Object> dataModel = new HashMap<>();
            dataModel.put("testcase", getTestcaseWithLineNumber());
            dataModel.put("originCase", originTestCase);
            dataModel.put("testOutput", result.toString());
            dataModel.put("apiDocs", apiDoc);
            dataModel.put("rootCause", verifyMessage);
            String prompt = PromptGen.generatePrompt("FixTestCase", dataModel);
            String text = OpenAI.R1.messageCompletion(prompt);
            ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
            if (codeBlocks.isEmpty()) {
                applyChange(text);
            } else {
                String generatedCode = codeBlocks.get(codeBlocks.size() - 1);
                applyChange(generatedCode);
            }
            
            // 修复后重新计算API文档，因为可能引入了新的API调用
            recalculateApiDocs();
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Fixing test case failed: " + file + "\n" + e.getMessage());
        }
    }

    public void enhance() {
        try {
            Map<String, Object> dataModel = new HashMap<>();
            dataModel.put("testcase", getTestcaseWithLineNumber());
            dataModel.put("apiDocs", apiDoc);
            String prompt = PromptGen.generatePrompt("EnhanceTestCase", dataModel);
            String text = OpenAI.R1.messageCompletion(prompt);
            ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);

            if (codeBlocks.isEmpty()) {
                applyChange(text);
            } else {
                String generatedCode = codeBlocks.get(codeBlocks.size() - 1);
                applyChange(generatedCode);
            }
            
            // 增强后重新计算API文档，因为可能引入了新的API调用
            recalculateApiDocs();

        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Enhancing test case failed: " + file + "\n" + e.getMessage());
        }
    }

    public void applyChange(String change){
        try {
            String testcase = getSourceCode();
            Map<String, Object> dataModel = new HashMap<>();
            dataModel.put("originTestcase", testcase);
            dataModel.put("modified", change);
            String prompt = PromptGen.generatePrompt("ApplyChange", dataModel);
            String text = OpenAI.Doubao.messageCompletion(prompt);
            ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
            String generatedCode = codeBlocks.get(codeBlocks.size() - 1);
            writeTestCaseToFile(generatedCode);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Applying change failed: " + file + "\n" + e.getMessage());
        }
    }

    /**
     * 为指定JDK创建独立的临时目录
     * @param jdkName JDK名称
     * @return 临时目录路径
     */
    public String createTempDirectory(String jdkName) {
        // 动态生成执行上下文信息
        long threadId = Thread.currentThread().getId();
        long timestamp = System.nanoTime();
        
        String dirName = String.format("tmp/jtreg_%s_%s_%d_%d", 
            jdkName, name, threadId, timestamp);
        
        // 注意：不能在这里调用 tempDirectories.put()！
        // computeIfAbsent 会自动将返回值放入map中，手动put会导致递归更新异常
        
        File tempDir = new File(dirName);
        if (!tempDir.exists()) {
            boolean created = tempDir.mkdirs();
            if (!created) {
                LoggerUtil.logExec(Level.WARNING, 
                    String.format("无法创建临时目录: %s (TestCase: %s)", dirName, name));
            } else {
                LoggerUtil.logExec(Level.FINE, 
                    String.format("创建临时目录: %s (TestCase: %s)", dirName, name));
            }
        }
        
        return dirName;
    }
    
    /**
     * 获取指定JDK的临时目录路径
     * 线程安全：使用ConcurrentHashMap保证并发访问安全
     * @param jdkName JDK名称
     * @return 临时目录路径，如果不存在则创建
     */
    public String getTempDirectory(String jdkName) {
        return tempDirectories.computeIfAbsent(jdkName, this::createTempDirectory);
    }
    
    /**
     * 清理当前测试用例的特定JTreg工作文件
     * 只清理与当前测试文件相关的文件，确保并发安全
     */
    public void clearSpecificJTworkFiles() {
        try {
            String baseName = file.getName().replace(".java", "");
            File jtWork = new File("JTwork");
            
            if (!jtWork.exists()) {
                return; // JTwork目录不存在，无需清理
            }
            
            // 计算相对路径（假设有resultDir，这里简化处理）
            Path relativePath = getRelativePathToFile();
            if (relativePath == null) {
                return;
            }
            
            // 要清理的文件
            String[] filesToClean = {
                baseName + ".class",
                baseName + ".jtr", 
                baseName + ".d"
            };
            
            String[] directories = {"classes", "", ""};
            
            // 只清理当前测试文件相关的特定文件
            for (int i = 0; i < filesToClean.length; i++) {
                Path targetPath = jtWork.toPath();
                if (!directories[i].isEmpty()) {
                    targetPath = targetPath.resolve(directories[i]);
                }
                targetPath = targetPath.resolve(relativePath).resolve(filesToClean[i]);
                
                File targetFile = targetPath.toFile();
                if (targetFile.exists()) {
                    boolean deleted = targetFile.delete();
                    if (deleted) {
                        LoggerUtil.logExec(Level.FINE, 
                            String.format("清理文件: %s (TestCase: %s)", targetFile.getPath(), name));
                    } else {
                        LoggerUtil.logExec(Level.FINE, 
                            String.format("无法删除文件: %s (TestCase: %s)", targetFile.getPath(), name));
                    }
                }
            }
        } catch (Exception e) {
            // 清理失败时静默处理，避免影响主要流程
            LoggerUtil.logExec(Level.FINE, 
                String.format("清理特定JTwork文件失败 (TestCase: %s): %s", name, e.getMessage()));
        }
    }
    
    /**
     * 清理指定JDK的临时文件
     * @param jdkName JDK名称
     */
    public void clearTempFiles(String jdkName) {
        String tempDir = tempDirectories.get(jdkName);
        if (tempDir == null) {
            return; // 该JDK没有创建临时目录
        }
        
        try {
            // 删除整个临时目录（包含JTwork和JTreport子目录）
            File tempDirFile = new File(tempDir);
            if (tempDirFile.exists()) {
                deleteRecursively(tempDirFile);
            }
            
            // 从缓存中移除
            tempDirectories.remove(jdkName);
            
            LoggerUtil.logExec(Level.FINE, 
                String.format("清理临时目录: %s (TestCase: %s, JDK: %s)", tempDir, name, jdkName));
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, 
                String.format("清理临时目录失败 (TestCase: %s, JDK: %s): %s", name, jdkName, e.getMessage()));
        }
    }
    
    /**
     * 清理所有临时文件
     */
    public void clearAllTempFiles() {
        // 创建keySet的快照，避免在迭代过程中的并发修改
        // 虽然ConcurrentHashMap是线程安全的，但在迭代过程中仍可能有其他线程修改
        Set<String> jdkNames = new HashSet<>(tempDirectories.keySet());
        
        // 清理所有JDK的临时文件
        jdkNames.forEach(this::clearTempFiles);
        
        // 清理JTreg工作文件
        clearSpecificJTworkFiles();
        
        LoggerUtil.logExec(Level.FINE, String.format("清理所有临时文件完成 (TestCase: %s)", name));
    }
    
    /**
     * 获取文件的相对路径（简化版本）
     * @return 相对路径，如果无法确定则返回null
     */
    private Path getRelativePathToFile() {
        try {
            // 这里可能需要根据实际的resultDir来计算
            // 简化处理：假设文件在当前工作目录下
            Path currentDir = Paths.get("").toAbsolutePath();
            Path filePath = file.toPath().toAbsolutePath();
            Path relativePath = currentDir.relativize(filePath.getParent());
            return relativePath;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 递归删除目录
     * @param file 要删除的文件或目录
     * @return 是否删除成功
     */
    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    public String getApiInfoWithSource() {
        StringBuilder sb = new StringBuilder();
        try {
            var map = apiInfoProcessor.getApiDocWithSource(file);
            map.forEach((k, v) -> {sb.append(k).append("\n").append(v).append("\n--------------\n");});
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "测试用例获取文档出错: " + name + " " + e.getMessage());
        }
        return sb.toString();
    }
}

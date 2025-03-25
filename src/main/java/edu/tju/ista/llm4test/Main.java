package edu.tju.ista.llm4test;


import edu.tju.ista.llm4test.execute.*;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.agents.BugVerifyAgent;
import edu.tju.ista.llm4test.llm.tools.JtregExecuteTool;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;


class TestStatistics {
    private final ConcurrentEnumCounter<TestResultKind> counter = new ConcurrentEnumCounter<>(TestResultKind.class);

    public void recordResult(TestResultKind result) {
        counter.increment(result);
    }

    public void logStatistics() {
        LoggerUtil.logResult(Level.INFO,
                "Success: " + counter.get(TestResultKind.SUCCESS) +
//                        "\nCompile Fail: " + counter.get(TestResultKind.COMPILE_FAIL) +
//                        "\nTest Fail: " + counter.get(TestResultKind.TEST_FAIL) +
                        "\nVerified Test Fail: " + counter.get(TestResultKind.VERIFIED_BUG) +
                        "\nUnverified Test Fail " + counter.get(TestResultKind.MAYBE_TEST_FAIL) +
                        "\nDiff: " + counter.get(TestResultKind.DIFF) +
                        "\nTimeout: " + (counter.get(TestResultKind.COMPILE_TIMEOUT) + counter.get(TestResultKind.EXECUTE_TIMEOUT)) +
                        "\nUnknown Fail: " + counter.get(TestResultKind.UNKNOWN) +
                        "\nPass: " + counter.get(TestResultKind.PASS));
    }
}

public class Main {
    private static final String TEMPLATE_MODE = "SpecTest";
    private final TestExecutor testExecutor;
    private final TestStatistics statistics;
    private final ApiDocProcessor apiDocProcessor;
    private final FileProcessor fileProcessor;

    private TestSuite testSuite;
    private final File ResultDir;

    private static final String [] jars;

    static {
        jars = new String[]{"Dependency/testng-7.10.2.jar",
                "Dependency/junit-jupiter-api-5.11.4.jar", // JUnit 5 before JUnit4
                "Dependency/junit-jupiter-engine-5.11.4.jar",
                "Dependency/junit-4.13.1.jar",};
    }

    public static void main(String[] args) {
        for (String arg: args) {
            System.out.println(arg);
        }
        switch (args[0]) {
            case "execute": execute(args[1]); break;
            case "generate": generate(args[1]); break;
            case "env" : testJDKenv(); break;
            case "verify": verifyBugs(); break;
        }
    }

    private static void testJDKenv() {
        var executor = new TestExecutor(new File(""));
        executor.testJDKenv();
    }

    public static void generate(String testPath) {
        String jarPath = String.join(File.pathSeparator, jars);
        File ResultDir = new File("test");
        if (!ResultDir.exists()) {
            ResultDir.mkdirs();
        }
        String baseDocPath = "JavaDoc/docs/api/java.base";
        String suitePath = "jdk17u-dev/test/jdk/" + testPath; // this is the true suitePath
        Main instance = new Main(jarPath, ResultDir, baseDocPath, suitePath);
        instance.runTestSuiteParallel();
    }

    public static void execute(String testPath) {
        String jarPath = String.join(File.pathSeparator, jars);
        File ResultDir = new File("test");
        if (!ResultDir.exists()) {
            ResultDir.mkdirs();
        }
        String baseDocPath = "JavaDoc/docs/api/java.base";
        String suitePath = "jdk17u-dev/test/jdk/" + testPath; // this is the true suitePath
        Main instance = new Main(jarPath, ResultDir, baseDocPath, suitePath);
        instance.runTestSuiteParallel2(Path.of("jdk/java/util/ArrayList"));
    }
    public Main(String jarPath, File resultDir, String baseDocPath, String suitePath) {
        this.testExecutor = new TestExecutor(resultDir);
        this.statistics = new TestStatistics();
        this.apiDocProcessor = new ApiDocProcessor(baseDocPath);
        this.fileProcessor = new FileProcessor(resultDir);
        this.ResultDir = resultDir;
        this.testSuite = new TestSuite(suitePath);
        fileProcessor.copyTestFiles(Path.of("jdk17u-dev/test"));
    }
    
    public static List<File> traverseDir(File dir) {
        ArrayList<File> list = new ArrayList<>();
        if(dir.isDirectory()) {
            for (File file : Objects.requireNonNull(dir.listFiles())) {
                if (file.isDirectory()) {
                    list.addAll(traverseDir(file));
                } else if (file.isFile()){
                    list.add(file);
                }
            }
        } 
        return list;
    }

    private void testCaseConstruction(File file) {
        try {
            if (Files.size(file.toPath()) <= 10000) {
                LoggerUtil.logExec(Level.INFO,"Processing file: " + file);
//                File targetFile = new File(file.getAbsolutePath().replace(rootPath.toString(), ResultDir.getPath()));
                File targetFile = new File(file.getAbsolutePath().replace("jdk17u-dev/test", "test"));
                TestCase testcase = new TestCase(targetFile);
                testcase.setOriginFile(file);
                TestResult result = processTestFile(testcase);
                LoggerUtil.logResult(Level.INFO, file + " " + result.getKind());
                if (result.isFail() || result.isBug())
                    LoggerUtil.logResult(Level.INFO, testcase.verifyMessage);
            } else {
                statistics.recordResult(TestResultKind.PASS);
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Processing test file failed: " + file + "\n" + e.getMessage());
        }
    }

    private void executeTestCase(File originFile, Path rootPath) {
        File targetFile = new File(originFile.getAbsolutePath().replace("jdk17u-dev/test", "test"));
        TestCase testcase = new TestCase(targetFile);
        testcase.setOriginFile(originFile);
        File file = testcase.getFile();
        TestResult result = testExecutor.executeTest(file);
        LoggerUtil.logExec(Level.INFO, "Processing file: " + file);
        LoggerUtil.logResult(Level.INFO, file + " " + result.getKind() + " " + result.getJtregResult().exitValue);
        statistics.recordResult(result.getKind());
    }

    public void runTestSuiteParallel() {

        ArrayList<String> testCases = testSuite.getTestCases().stream().map(s -> "jdk17u-dev/test/jdk/" + s).collect(Collectors.toCollection(ArrayList::new));

//        fileProcessor.copyTestFiles(rootPath);
//        List<File> files = traverseDir(rootPath.toFile()).stream().filter(file -> file.getName().endsWith(".java")).collect(Collectors.toList());
        // 这里的文件路径不对，需要更新
        List<File> files = testCases.stream().map(File::new).toList();
        System.out.println(files);
        System.out.println("Total files: " + files.size());
        int threadCount = Math.min(Runtime.getRuntime().availableProcessors() * 2, files.size());
//        threadCount = 1;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(files.size());
        files.forEach(file -> {
            executor.submit(() -> {
                testCaseConstruction(file);
                latch.countDown();
            });
        });
        waitForCompletion(executor, latch);
        statistics.logStatistics();
    }

    public void runTestSuiteParallel2(Path rootPath) {

        ArrayList<String> testCases = testSuite.getTestCases().stream().map(s -> "jdk17u-dev/test/jdk/" + s).collect(Collectors.toCollection(ArrayList::new));
        List<File> files = testCases.stream().map(File::new).toList();
        System.out.println("Total files: " + files.size());
        int threadCount = Math.min(Runtime.getRuntime().availableProcessors() * 2, files.size());
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(files.size());
        files.forEach(file -> {
            executor.submit(() -> {
                executeTestCase(file, rootPath);
                latch.countDown();
            });
        });
        waitForCompletion(executor, latch);
        statistics.logStatistics();
    }





    private TestResult processTestFile(TestCase testCase) {
        try {
            File file = testCase.getFile();
            TestResult result = testExecutor.executeTest(file);
            System.out.println("result " + result.getKind());
            if (!result.isSuccess()) {
                statistics.recordResult(TestResultKind.PASS);
                return new TestResult(TestResultKind.PASS);
            }
            String apiDocs = apiDocProcessor.processApiDocs(file);
            testCase.setApiDocs(apiDocs);

            testCase.enhance();
            testCase.verifyTestFail();
            testCase.setResult(testExecutor.executeTest(file));
            result = testCase.getResult();
            for (int i = 0; i < 3; i++) {
                if (!result.isFail()) {
                    break;
                }
                testCase.fix();
                testCase.setResult(testExecutor.executeTest(file));
                result = testCase.getResult();
            }
            testCase.verifyTestFail();

//            // 根据api信息生成assert
//            if (!result.isSuccess()) {
//                statistics.recordResult(result.getKind());
//                return result;
//            }
//
//            Map<String, Object> dataModel = new HashMap<>();
//            {// setup data model
//                dataModel.put("apiDocs", apiDocs);
//                dataModel.put("testcase", testCase.getTestcaseWithLineNumber());
//            }
//
//            String prompt = PromptGen.generatePrompt(TEMPLATE_MODE, dataModel);
//            System.out.println("call openAI");
//            String generatedCode = processPrompt(prompt);
//            if (generatedCode.isEmpty()) {
//                statistics.recordResult(TestResultKind.UNKNOWN);
//                return new TestResult(TestResultKind.UNKNOWN);
//            }
//            testCase.applyChange(generatedCode);
//
//            result = testExecutor.executeTest(file);
//            testCase.setResult(result);
//
//            for (int i = 0; i < 1; i++) {
//                if (!result.isFail()) {
//                    break;
//                }
//                testCase.fix();
//                testCase.setResult(testExecutor.executeTest(file));
//                result = testCase.getResult();
//            }
//            testCase.verifyTestFail();


//            if (testCase.getResult().isSuccess()) {
//                testCase.enhance();
//                testCase.setResult(testExecutor.executeTest(file));
//                result = testCase.getResult();
//            }
//            for (int i = 0; i < 1; i++) {
//                if (!result.isFail()) {
//                    break;
//                }
//                testCase.fix();
//                testCase.setResult(testExecutor.executeTest(file));
//                result = testCase.getResult();
//            }
//            testCase.verifyTestFail();
            statistics.recordResult(result.getKind());
            return result;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Processing test file failed: " + testCase.file +  "\n" + e.getMessage());
            statistics.recordResult(TestResultKind.UNKNOWN);
        }
        return new TestResult(TestResultKind.UNKNOWN);
    }

    private String processPrompt(String prompt) {
        String text = OpenAI.Doubao.messageCompletion(prompt);
        System.out.println("text " + text);
        ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
        if (codeBlocks.isEmpty()) {
            return text; // 让apply change的大模型自己处理。
        }
        return codeBlocks.get(codeBlocks.size() - 1);
    }

    private void waitForCompletion(ExecutorService executor, CountDownLatch latch) {
        try {
            latch.await();
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 验证已识别的bug并生成详细报告
     */
    public static void verifyBugs() {
        LoggerUtil.logResult(Level.INFO, "开始验证bug并生成报告...");
        
        // 设置路径和工具
        String jarPath = String.join(File.pathSeparator, jars);
        String baseDocPath = "JavaDoc/docs/api/java.base";
        String jdkSourcePath = "jdk17u-dev/src";
        
        // 创建Bug报告目录
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File bugReportDir = new File("BugReport/" + timestamp);
        if (!bugReportDir.exists()) {
            bugReportDir.mkdirs();
            LoggerUtil.logResult(Level.INFO, "创建Bug报告目录: " + bugReportDir.getAbsolutePath());
        }
        
        // 创建BugVerifyAgent
        BugVerifyAgent agent = new BugVerifyAgent(baseDocPath, jdkSourcePath);
        
        // 从result.log读取已验证的bug
        try {
            File resultLog = new File("result.log");
            if (!resultLog.exists()) {
                LoggerUtil.logResult(Level.WARNING, "找不到result.log文件，无法验证bug");
                return;
            }
            
            // 解析log文件
            List<String> lines = Files.readAllLines(resultLog.toPath());
            Map<String, String> verifiedBugs = new HashMap<>();
            
            for (int i = 0; i < lines.size() - 1; i++) {
                String line = lines.get(i);
                // 查找包含 VERIFIED_BUG 的行
                if (line.contains("VERIFIED_BUG")) {
                    // 提取文件路径 - 格式: INFO: jdk17u-dev/test/jdk/java/util/xxx/Test.java VERIFIED_BUG
                    int pathStart = line.lastIndexOf("INFO: ") + 6;
                    int pathEnd = line.lastIndexOf(" VERIFIED_BUG");
                    
                    if (pathStart >= 6 && pathEnd > pathStart) {
                        String filePath = line.substring(pathStart, pathEnd);
                        filePath = filePath.replace("jdk17u-dev/test", "test"); // 实际的测试用例放在test目录下
                        
                        // 检查下一行是否包含bug详细信息
                        String nextLine = lines.get(i + 2);
                        if (nextLine.contains("{") && nextLine.contains("}")) {
                            String verifyMessage = nextLine.substring(nextLine.indexOf("{"));
                            verifiedBugs.put(filePath, verifyMessage);
                            LoggerUtil.logResult(Level.INFO, "找到已验证的bug: " + filePath);
                        }
                        i += 2;
                    }
                }
            }
            
            LoggerUtil.logResult(Level.INFO, "从日志中找到 " + verifiedBugs.size() + " 个已验证的bug");
            
            // 为每个bug生成报告
            for (Map.Entry<String, String> entry : verifiedBugs.entrySet()) {
                String filePath = entry.getKey();
                String verifyMessage = entry.getValue();
                
                // 从JDK路径转换到测试路径
                File originFile = new File(filePath);
                File testFile = new File(filePath.replace("jdk17u-dev/test", "test"));
                
                if (!testFile.exists() && !originFile.exists()) {
                    LoggerUtil.logResult(Level.WARNING, "测试文件不存在: " + filePath);
                    continue;
                }
                
                // 使用存在的文件
                File fileToUse = testFile.exists() ? testFile : originFile;
                
                String testCaseName = fileToUse.getName().replace(".java", "");
                LoggerUtil.logResult(Level.INFO, "正在分析bug: " + testCaseName);
                
                try {
                    // 读取测试用例内容
                    String testContent = Files.readString(fileToUse.toPath());
                    
                    // 运行测试获取输出
                    JtregExecuteTool jtregTool = new JtregExecuteTool();
                    ToolResponse<TestResult> response = jtregTool.execute(fileToUse.getPath());
                    
                    String testOutput = "";
                    if (response.isSuccess()) {
                        testOutput = response.getResult().getOutput();
                    } else {
                        // 如果执行失败，尝试从result文件获取输出
                        File resultFile = new File(fileToUse.getPath() + ".result");
                        if (resultFile.exists()) {
                            testOutput = Files.readString(resultFile.toPath());
                        } else {
                            // 使用verifyMessage作为备选
                            testOutput = "无法获取测试输出，使用验证消息作为替代: " + verifyMessage;
                        }
                    }
                    
                    // 创建该测试用例的报告目录
                    File caseReportDir = new File(bugReportDir, testCaseName);
                    if (!caseReportDir.exists()) {
                        caseReportDir.mkdirs();
                    }
                    
                    // 设置测试数据并分析
                    agent.setTestData(testContent, testOutput, verifyMessage);
                    String report = agent.analyze();
                    
                    // 保存Bug报告
                    Path reportPath = Path.of(caseReportDir.getPath(), "bugReport.md");
                    Files.writeString(reportPath, report);
                    
                    // 复制测试文件
                    Files.copy(fileToUse.toPath(), 
                            Path.of(caseReportDir.getPath(), fileToUse.getName()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    
                    LoggerUtil.logResult(Level.INFO, "Bug报告已生成: " + reportPath);
                } catch (Exception e) {
                    LoggerUtil.logResult(Level.WARNING, "为测试用例生成报告失败: " + testCaseName);
                    e.printStackTrace();
                }
            }
            
            LoggerUtil.logResult(Level.INFO, "Bug验证和报告生成完成，报告保存在: " + bugReportDir.getAbsolutePath());
        } catch (Exception e) {
            LoggerUtil.logResult(Level.SEVERE, "Bug验证过程失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}




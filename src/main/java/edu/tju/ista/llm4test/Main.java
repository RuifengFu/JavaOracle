package edu.tju.ista.llm4test;


import edu.tju.ista.llm4test.execute.*;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
                        "\nCompile Fail: " + counter.get(TestResultKind.COMPILE_FAIL) +
                        "\nTest Fail: " + counter.get(TestResultKind.TEST_FAIL) +
                        "\nVerified Test Fail: " + counter.get(TestResultKind.VERIFIED_BUG) +
                        "\nUnverified Test Fail " + counter.get(TestResultKind.MAYBE_TEST_FAIL) +
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
        }
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
        this.testExecutor = new TestExecutor(jarPath, resultDir);
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
}




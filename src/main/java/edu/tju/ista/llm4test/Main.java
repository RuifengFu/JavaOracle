package edu.tju.ista.llm4test;


import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestExecutor;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.execute.TestResultKind;
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

class Main {
    private static final String TEMPLATE_MODE = "SpecTest";
    private final TestExecutor testExecutor;
    private final TestStatistics statistics;
    private final ApiDocProcessor apiDocProcessor;
    private final FileProcessor fileProcessor;

    private final File ResultDir;

    private static final String [] jars;

    static {
        jars = new String[]{"Dependency/testng-7.10.2.jar",
                "Dependency/junit-jupiter-api-5.11.4.jar", // JUnit 5 before JUnit4
                "Dependency/junit-jupiter-engine-5.11.4.jar",
                "Dependency/junit-4.13.1.jar",};
    }

    public static void main(String[] args) {
        String jarPath = String.join(File.pathSeparator, jars);
        File ResultDir = new File("test");
        if (!ResultDir.exists()) {
            ResultDir.mkdirs();
        }
        String baseDocPath = "JavaDoc/docs/api/java.base";
        Main instance = new Main(jarPath, ResultDir, baseDocPath);
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\String"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\StringBuffer"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\StringBuilder"));
////
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Boolean"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Byte"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Character"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Double"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Float"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Integer"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Long"));
////
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\math\\BigDecimal"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\math\\BigInteger"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\math\\RoundingMode"));
//
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\ArrayList"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\Arrays"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\Collection"));
//        instance.runTestSuiteParallel(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\Collections"));
//        instance.runTestSuiteParallel(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\BitSet"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\Currency"));

        instance.runTestSuiteParallel(Path.of("JavaTest/jdk/java/util"));
    }
    public Main(String jarPath, File resultDir, String baseDocPath) {
        this.testExecutor = new TestExecutor(jarPath, resultDir);
        this.statistics = new TestStatistics();
        this.apiDocProcessor = new ApiDocProcessor(baseDocPath);
        this.fileProcessor = new FileProcessor(resultDir);
        this.ResultDir = resultDir;
    }
    
    public List<File> traverseDir(File dir) {
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

    private void testCaseConstruction(File file, Path rootPath) {
        try {
            if (Files.size(file.toPath()) <= 100000) {
                LoggerUtil.logExec(Level.INFO,"Processing file: " + file);
                File targetFile = new File(file.getAbsolutePath().replace(rootPath.toString(), ResultDir.getPath()));
                TestCase testcase = new TestCase(targetFile);
                testcase.setOriginFile(file);
                TestResult result = processTestFile(testcase);
                LoggerUtil.logResult(Level.INFO, file + " " + result.getKind());
                if (result.isFail() || result.isBug())
                    LoggerUtil.logResult(Level.INFO, testcase.verifyMessage);


            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Processing test file failed: " + file + "\n" + e.getMessage());
        }
    }

    public void runTestSuiteParallel(Path rootPath) {
        fileProcessor.copyTestFiles(rootPath);
        List<File> files = traverseDir(rootPath.toFile()).stream().filter(file -> file.getName().endsWith(".java")).collect(Collectors.toList());
        int threadCount = Math.min(Runtime.getRuntime().availableProcessors() , files.size());
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(files.size());
        
        files.forEach(file -> {
            executor.submit(() -> {
                testCaseConstruction(file, rootPath);
                latch.countDown();
            });
        });
        waitForCompletion(executor, latch);
        statistics.logStatistics();
    }




    public void runTestSuiteMultiThread(Path testSuitePath)  {
        fileProcessor.copyTestFiles(testSuitePath);

        File[] files = testSuitePath.toFile().listFiles();
        int threadCount = Math.min(Runtime.getRuntime().availableProcessors() * 4, files.length);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(files.length);
        for (File file : files) {
            executor.submit(() -> {
                if (file.isFile() && file.getName().endsWith(".java")) {
                    testCaseConstruction(file, testSuitePath);
                }
                latch.countDown();
            });
        }
        waitForCompletion(executor, latch);
        statistics.logStatistics();
    }

    private TestResult processTestFile(TestCase testCase) {
        try {
            File file = testCase.getFile();
            TestResult result = testExecutor.executeTest(file);
            if (!result.isSuccess()) {
                statistics.recordResult(TestResultKind.PASS);
                return new TestResult(TestResultKind.PASS);
            }
            Map<String, Object> dataModel = new HashMap<>();
            {// setup data model
                String apiDocs = apiDocProcessor.processApiDocs(file);
                dataModel.put("apiDocs", apiDocs);
                dataModel.put("testcase", testCase.getTestcaseWithLineNumber());
                testCase.setApiDocs(apiDocs);
            }

            String prompt = PromptGen.generatePrompt(TEMPLATE_MODE, dataModel);
            String generatedCode = processPrompt(prompt);
            if (generatedCode.isEmpty()) {
                return new TestResult(TestResultKind.UNKNOWN);
            }
            testCase.writeTestCaseToFile(generatedCode);

            result = testExecutor.executeTest(file);
            testCase.setResult(result);

            for (int i = 0; i < 3; i++) {
                if (!result.isFail()) {
                    break;
                }
                testCase.fix();
                testCase.setResult(testExecutor.executeTest(file));
                result = testCase.getResult();
            }
            testCase.verifyTestFail();
            statistics.recordResult(result.getKind());
            return result;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Processing test file failed: " + testCase.file +  "\n" + e.getMessage());
            statistics.recordResult(TestResultKind.UNKNOWN);
        }
        return new TestResult(TestResultKind.UNKNOWN);
    }

    private String processPrompt(String prompt) {
        String text = OpenAI.messageCompletion(prompt);
        ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
        if (codeBlocks.isEmpty()) {
            return "";
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




package org.example;


import edu.tju.ista.llm4test.execute.TestExecutor;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.execute.TestResultKind;

import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.*;


import java.io.File;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;



class TestStatistics {
    private final AtomicInteger success = new AtomicInteger(0);
    private final AtomicInteger compileFail = new AtomicInteger(0);
    private final AtomicInteger testFail = new AtomicInteger(0);
    private final AtomicInteger unknownFail = new AtomicInteger(0);
    private final AtomicInteger timeout = new AtomicInteger(0);
    private final AtomicInteger diff = new AtomicInteger(0);
    public void recordResult(TestResultKind result) {
        switch (result) {
            case SUCCESS -> success.incrementAndGet();
            case COMPILE_FAIL -> compileFail.incrementAndGet();
            case TEST_FAIL -> testFail.incrementAndGet();
            case COMPILE_TIMEOUT, EXECUTE_TIMEOUT -> timeout.incrementAndGet();
            case DIFF -> diff.incrementAndGet();
            case UNKNOWN -> unknownFail.incrementAndGet();
        }
    }

    public void logStatistics() {
        LoggerUtil.logResult(Level.INFO,
                "Success: " + success.get() +
                        " Compile Fail: " + compileFail.get() +
                        " Test Fail: " + testFail.get() +
                        " Timeout: " + timeout.get() +
                        " Diff: " + diff.get() +
                        " Unknown Fail: " + unknownFail.get());
    }
}

class Main {
    private static final String TEMPLATE_MODE = "SpecTest";
    private final TestExecutor testExecutor;
    private final TestStatistics statistics;
    private final ApiDocProcessor apiDocProcessor;
    private final FileProcessor fileProcessor;

    private final File ResultDir;

    private static String [] jars;

    static {
        jars = new String[]{"C:\\Users\\Administrator\\.m2\\repository\\junit\\junit\\4.13.1\\junit-4.13.1.jar",
                "C:\\Users\\Administrator\\.m2\\repository\\org\\testng\\testng\\6.7\\testng-6.7.jar"};
    }

    public static void main(String args []) {
        String jarPath = String.join(File.pathSeparator, jars);
        File ResultDir = new File("Results");
        String baseDocPath = "H:\\research\\JavaOracle\\JavaDoc\\docs\\api\\java.base";
        Main instance = new Main(jarPath, ResultDir, baseDocPath);
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\String"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\StringBuffer"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\StringBuilder"));
//
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Boolean"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Byte"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Character"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Double"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Float"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Integer"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\Long"));
//
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\math\\BigDecimal"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\math\\BigInteger"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\math\\RoundingMode"));
//
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\ArrayList"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\Arrays"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\Collection"));
        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\Collections"));
//        instance.runTestSuiteMultiThread(Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\Currency"));

    }
    public Main(String jarPath, File resultDir, String baseDocPath) {
        this.testExecutor = new TestExecutor(jarPath, resultDir);
        this.statistics = new TestStatistics();
        this.apiDocProcessor = new ApiDocProcessor(baseDocPath);
        this.fileProcessor = new FileProcessor(resultDir);
        this.ResultDir = resultDir;
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
                    try {
                        if (Files.size(file.toPath()) <= 100000) {
                            LoggerUtil.logExec(Level.INFO,"Processing file: " + file);
                            File targetFile = new File(ResultDir, file.getName());
                            TestResult result = processTestFile(targetFile);
                            LoggerUtil.logResult(Level.INFO, file + " " + result.getKind());
                        }
                    } catch (Exception e) {

                    }
                }
                latch.countDown();
            });

        }


        waitForCompletion(executor, latch);
        statistics.logStatistics();
    }

    private TestResult processTestFile(File file) {
        try {
            TestResult result = testExecutor.executeTest(file);
            if (!result.isSuccess()) {
                return new TestResult(TestResultKind.PASS);
            }
            String apiDocs = apiDocProcessor.processApiDocs(file);
            String testcase = Files.readString(file.toPath());

            Map<String, Object> dataModel = new HashMap<>();
            dataModel.put("apiDocs", apiDocs);
            dataModel.put("testcase", testcase);

            String prompt = PromptGen.generatePrompt(TEMPLATE_MODE, dataModel);
            String generatedCode = processPrompt(prompt);

            String className = file.getName().replace(".java", "");
            fileProcessor.writeTestFile(className, generatedCode);

            result = testExecutor.executeTest(file);
            if (result.isTestFail()) {

            }

            statistics.recordResult(result.getKind());


            return result;
        } catch (Exception e) {
            statistics.recordResult(TestResultKind.UNKNOWN);
        }
        return new TestResult(TestResultKind.UNKNOWN);
    }

    private String processPrompt(String prompt) throws Exception {
        String text = OpenAI.messageCompletion(prompt);
        ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
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




//
//class Main {
//
//    public static String template_mode = "ApiTest";
//    public static int success = 0;
//    public static int other_fail = 0;
//    public static int compile_fail = 0;
//    public static int test_fail = 0;
//
//    public static void clear() {
//        success = 0;
//        other_fail = 0;
//        compile_fail = 0;
//        test_fail = 0;
//    }
//
//
//    //write a function to execute All classes under a directory
//    public static void runTests(String directoryPath) {
//        File directory = new File(directoryPath);
//        File[] files = directory.listFiles();
//
//        for (File file : files) {
//
//            if (file.toString().endsWith(".java")) {
//                System.out.println(file.toString());
//                TestResult result = compileAndExecute(file, directory);
//                if (result == TestResult.SUCCESS) {
//                    System.out.println("Test passed for " + file);
//                    success++;
//                } else {
//
//                    switch (result) {
//                        case COMPILE_FAIL:
//                            System.out.println("Compilation failed for " + file);
//                            compile_fail++;
//                            break;
//                        case TEST_FAIL:
//                            System.out.println("Test failed for " + file);
//                            test_fail++;
//                            break;
//                        case OTHER_FAIL:
//                            System.out.println("Other failure for " + file);
//                            other_fail++;
//                            break;
//                    }
//                }
//            }
//        }
//        System.out.println("Success: " + success + " Compile Fail: " + compile_fail + " Test Fail: " + test_fail + " Other Fail: " + other_fail);
//    }
//
//    private static TestResult compileAndExecute(File file, File resultDir) {
//        try {
//            // 指定 JAR 包路径
//            String jarPath = "C:\\Users\\Administrator\\.m2\\repository\\org\\testng\\testng\\6.7\\testng-6.7.jar";
//
//            String className = file.getName().replace(".java", "");
//
//            // 构建编译命令，包含类路径和 JAR 包
//            String compileCommand = "javac -cp " + resultDir.getPath() + ";" + jarPath + " " + file.getPath();
//
//            Process compileProcess = Runtime.getRuntime().exec(compileCommand);
//            boolean compileSuccess = compileProcess.waitFor(30, TimeUnit.SECONDS);
//            // 编译失败，记录错误信息并返回
//            if (!compileSuccess || compileProcess.exitValue() != 0) {
////                String error = new String(compileProcess.getErrorStream().readAllBytes());
////                LoggerUtil.logResult(Level.INFO, "Compilation failed: \n" + error);
//                return TestResult.COMPILE_FAIL;
//            }
//            // 构建执行命令，包含类路径和 JAR 包
//            String execCommand = "java -cp " + resultDir.getPath() + ";" + jarPath + " " + className;
//
//            Process execProcess = Runtime.getRuntime().exec(execCommand);
//
//            boolean execSuccess = execProcess.waitFor(60, TimeUnit.SECONDS);
//
//            String output = new String(execProcess.getInputStream().readAllBytes());
//            if (!execSuccess || execProcess.exitValue() != 0) {
//                String error = new String(execProcess.getErrorStream().readAllBytes());
//                LoggerUtil.logResult(Level.INFO, "Execution failed: \n" + error);
//                return TestResult.TEST_FAIL;
//            }
////            System.out.println(output);
//            return TestResult.SUCCESS;
//        } catch (IOException | InterruptedException e) {
////            System.err.println("Failed to execute code: " + e.getMessage());
//            return TestResult.OTHER_FAIL;
//        }
//    }
//
//
//
//
//    public static void runTestSuiteMultiThread(Path testSuitePath) {
//        File resultDir = new File("Results");
//        String jarPath = "C:\\Users\\Administrator\\.m2\\repository\\org\\testng\\testng\\6.7\\testng-6.7.jar";
//
//        // Copy test suite files to resultDir
//        Arrays.stream(testSuitePath.toFile().listFiles())
//                .forEach(file -> {
//                    try {
//                        if (file.isFile()) {
//                            FileUtils.copyFileToDirectory(file, resultDir);
//                        } else {
//                            FileUtils.copyDirectoryToDirectory(file, resultDir);
//                        }
//                    } catch (IOException e) {
//                        System.err.println("Failed to copy test suite files to result directory");
//                    }
//                });
//
//        // 使用原子计数器替代普通计数器
//        AtomicInteger success = new AtomicInteger(0);
//        AtomicInteger compile_fail = new AtomicInteger(0);
//        AtomicInteger test_fail = new AtomicInteger(0);
//        AtomicInteger other_fail = new AtomicInteger(0);
//
//        File[] files = testSuitePath.toFile().listFiles();
//        int threadCount = Math.min(Runtime.getRuntime().availableProcessors() * 2, files.length);
//        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
//        CountDownLatch latch = new CountDownLatch(files.length);
//
//        for (File file : files) {
//            if (file.isFile() && file.getName().endsWith(".java")) {
//                executor.submit(() -> {
//                    try {
//                        processTestFile(file, resultDir, jarPath, success, compile_fail, test_fail, other_fail);
//                    } finally {
//                        latch.countDown();
//                    }
//                });
//            } else {
//                latch.countDown();
//            }
//        }
//        executor.shutdown();
//
//
//        try {
//            latch.await(); // 等待所有任务完成
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            System.err.println("Interrupted while waiting for tasks to complete");
//        }
//
//        try {
//            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
//                executor.shutdownNow();
//            }
//        } catch (InterruptedException e) {
//            executor.shutdownNow();
//            Thread.currentThread().interrupt();
//        }
//
//        LoggerUtil.logResult(Level.INFO,
//                "Success: " + success.get() +
//                        " Compile Fail: " + compile_fail.get() +
//                        " Test Fail: " + test_fail.get() +
//                        " Other Fail: " + other_fail.get());
//    }
//
//    private static void processTestFile(
//            File file,
//            File resultDir,
//            String jarPath,
//            AtomicInteger success,
//            AtomicInteger compile_fail,
//            AtomicInteger test_fail,
//            AtomicInteger other_fail) {
//
//        String className = file.getName().replace(".java", "");
//        // 首先测试原始文件
//        try {
//            String testcase = Files.readString(file.toPath());
//
//
//            synchronized (resultDir) {
//                if (!Files.exists(resultDir.toPath())) {
//                    Files.createDirectories(resultDir.toPath());
//                }
//            }
//            File outputFile = new File(resultDir, className + ".java");
//            Files.writeString(outputFile.toPath(), testcase);
//
//            // Compile and execute the original code
//            String compileCommand = "javac -cp " + resultDir + ";" + jarPath + " " + outputFile.getPath();
//            Process compileProcess = Runtime.getRuntime().exec(compileCommand);
//            boolean flag = compileProcess.waitFor(30, TimeUnit.SECONDS);
//
//            if (!flag || compileProcess.exitValue() != 0) {
//                if (compileProcess.isAlive()) {
//                    compileProcess.destroyForcibly();
//                }
//
//                System.out.println("Original file compile failed, skipping: " + file);
//                other_fail.incrementAndGet();
//                return; // 如果编译失败，直接返回
//            }
//
//            String execCommand = "java -cp " + resultDir + ";" + jarPath + " " + className;
//            Process execProcess = Runtime.getRuntime().exec(execCommand);
//            flag = execProcess.waitFor(60, TimeUnit.SECONDS);
//
//            if (!flag || execProcess.exitValue() != 0) {
//                if (execProcess.isAlive()) {
//                    execProcess.destroyForcibly();
//                }
//                System.out.println("Original file test failed, skipping: " + file);
//                other_fail.incrementAndGet();
//                return; // 如果测试失败，直接返回
//            }
//
//        } catch (Exception e) {
//            System.out.println("Error testing original file, skipping: " + file);
//            return; // 如果出现任何异常，直接返回
//        }
//
//        APISignatureExtractor extractor = new APISignatureExtractor();
//        String base = "H:\\research\\JavaOracle\\JavaDoc\\docs\\api\\java.base";
//        StringBuilder sb = new StringBuilder();
//        System.out.println("File : " + file);
//
//
//
//
//        try {
//            extractor.extractSignatures(String.valueOf(file)).forEach(signature -> {
////                System.out.println(signature.getPackageName() + " " + signature.getClassName() + " " + signature.getMethodName());
//                try {
//                    Document doc = HtmlParser.getDocument(base, signature.getPackageName(), signature.getClassName());
//                    sb.append(signature.getSignature()).append("\n")
//                            .append(HtmlParser.getMethodDetails(doc).get(signature.getMethodName()))
//                            .append("\n");
//                } catch (IOException e) {
////                    System.err.println("Failed to extract API docs for " + signature.getSignature());
//                }
//            });
//
//            String apiDocs = sb.toString();
//            String testcase = Files.readString(file.toPath());
//            Map<String, Object> dataModel = new HashMap<>();
//            dataModel.put("apiDocs", apiDocs);
//            dataModel.put("testcase", testcase);
//            String prompt = PromptGen.generatePrompt(template_mode, dataModel);
//            String text = OpenAI.call(prompt);
//
//            ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
//            for (String code: codeBlocks) {
//                LoggerUtil.logExec(Level.INFO, code);
//            }
//            String code = codeBlocks.get(codeBlocks.size() - 1);
//
//
//
//            File outputFile = new File(resultDir, className + ".java");
//            Files.writeString(outputFile.toPath(), code);
////            String versionInfo = new String(Runtime.getRuntime().exec("java --version").getInputStream().readAllBytes());
////            System.out.println(versionInfo);
//
//            // Compile and execute the code
//            String compileCommand = "javac -cp " + resultDir + ";" + jarPath + " " + outputFile.getPath();
//            Process compileProcess = Runtime.getRuntime().exec(compileCommand);
//            boolean flag = compileProcess.waitFor(30, TimeUnit.SECONDS);
//
//            if (flag && compileProcess.exitValue() == 0) {
//                String execCommand = "java -cp " + resultDir + ";" + jarPath + " " + className;
//                System.out.println(execCommand);
//                Process execProcess = Runtime.getRuntime().exec(execCommand);
//                flag = execProcess.waitFor(60, TimeUnit.SECONDS);
//
//                String result = new String(execProcess.getInputStream().readAllBytes());
//                LoggerUtil.logResult(Level.INFO, file + "\n" + result);
//                System.out.println(result);
//
//                if (flag && execProcess.exitValue() == 0) {
//                    success.incrementAndGet();
//                } else {
//                    if (execProcess.isAlive()) {
//                        execProcess.destroyForcibly();
//                    }
//                    LoggerUtil.logResult(Level.SEVERE, file + " test failed, flag is " + flag + " ,exit value is " + execProcess.exitValue());
//                    test_fail.incrementAndGet();
//                }
//            } else {
//                if (compileProcess.isAlive()) {
//                    compileProcess.destroyForcibly();
//                }
//                compile_fail.incrementAndGet();
//                String error = new String(compileProcess.getErrorStream().readAllBytes());
//                LoggerUtil.logResult(Level.INFO, file + "\n" + error);
//                System.err.println(error);
//            }
//
//        } catch (IOException e) {
//            System.err.println("Failed to read file " + file);
//            other_fail.incrementAndGet();
//        } catch (TemplateException e) {
//            System.err.println("Failed to generate prompt " + e);
//            other_fail.incrementAndGet();
//        } catch (InterruptedException e) {
//            System.err.println("Failed to execute command " + e);
//            other_fail.incrementAndGet();
//        } catch (Exception e) {
//            System.err.println("Failed to extract code " + e);
//            other_fail.incrementAndGet();
//        }
//    }
//
//
//
//    public static void main(String[] args) {
////        runTests("Results");
////        Path collectionTests = Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\Collection");
////        runTestSuite(collectionTests);
////        Path arraysTests = Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\Arrays");
////        runTestSuite(arraysTests);
////        Path objectsTests = Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\Objects");
////        runTestSuite(objectsTests);
////        Path hashSetTests = Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\HashSet");
////        runTestSuite(hashSetTests);
////          Path hashSetTests = Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\BitSet");
////          runTestSuite(hashSetTests);
////        Path hashSetTests = Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\List");
////        runTestSuite(hashSetTests);
////        Path hashSetTests = Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\util\\AbstractList");
//        Path hashSetTests = Path.of("H:\\research\\JavaOracle\\JavaTest\\jdk\\java\\lang\\String");
//        runTestSuiteMultiThread(hashSetTests);
//    }
//
//
//}
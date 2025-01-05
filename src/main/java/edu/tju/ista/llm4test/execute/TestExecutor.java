package edu.tju.ista.llm4test.execute;


import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class TestExecutor {
    private final String jarPath;
    private final File resultDir;

    private static final String[] execFlags = {"-Xint", "-Xcomp", "-Xmixed"};

    public TestExecutor(String jarPath, File resultDir) {
        this.jarPath = jarPath;
        this.resultDir = resultDir;
    }

    public TestResult executeTest(File file) {
        try {
            TestResult result = compileTest(file);
            if (!result.isCompileSuccess()) {
                return result;
            }
            TestOutput output = runTest(file);
            result.setResult("java -ea -cp " + file.getParentFile().toPath() +  File.pathSeparator + jarPath +  " " + file.getName().replace(".java", ""), output);
            if (output.exitValue != 0) {
                return result;
            }
            Map<String, TestOutput> results = runTestDifferential(file);
            result.mergeResults(results);
            return result;
        } catch (Exception e) {
            return new TestResult(TestResultKind.UNKNOWN);
        }
    }

    public TestResult compileTest(File file) throws IOException, InterruptedException {
        String compileCommand = "javac -cp " + file.getParentFile().toPath() + ";" + jarPath + " " + file.getPath();
        Process process = Runtime.getRuntime().exec(compileCommand);
        boolean flag = process.waitFor(30, TimeUnit.SECONDS);
        if (!flag) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            LoggerUtil.logExec(Level.INFO, "Compile: " + file.getPath() + "\n" + compileCommand + "\n" + "timeout");
            return new TestResult(new TestOutput("", "", 124));
        }
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        TestOutput output = new TestOutput(stdout, stderr, process.exitValue());
        LoggerUtil.logExec(Level.INFO, "Compile: " + file.getPath() + "\n" + compileCommand + "\n" + output);
        return new TestResult(output);
    }

    public TestOutput runTest(File file) throws IOException, InterruptedException {
        String className = file.getName().replace(".java", "");
        String execCommand = "java -ea -cp " + file.getParentFile().toPath() +  File.pathSeparator + jarPath +  " " + className;
        System.out.println("execCommand: " + execCommand);
        Process process = Runtime.getRuntime().exec(execCommand);

        boolean flag = process.waitFor(60, TimeUnit.SECONDS);
        if (!flag) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            LoggerUtil.logExec(Level.INFO, "Run: " + file.getPath() + "\n" + execCommand + "\n" + "timeout");
            return new TestOutput("", "", 124);
        }
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        TestOutput output = new TestOutput(stdout, stderr, process.exitValue());
        LoggerUtil.logExec(Level.INFO, "Run: " + file.getPath() + "\n" + execCommand + "\n" + output);
        return output;
    }

    // write a differential run Test Function
    private Map<String, TestOutput> runTestDifferential(File file) throws IOException, InterruptedException {
        String className = file.getName().replace(".java", "");
        HashMap<String, TestOutput> execResults = new HashMap<>();
        for (String execflag: execFlags) {
            String execCommand = "java -ea " + execflag + " -cp " + file.getParentFile().toPath() +  File.pathSeparator + jarPath +  " " + className;
            System.out.println("execCommand: " + execCommand);
            Process process = Runtime.getRuntime().exec(execCommand);
            boolean flag = process.waitFor(300, TimeUnit.SECONDS);
            if (!flag) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                execResults.put(execflag, new TestOutput("", "", 124));
                LoggerUtil.logExec(Level.INFO, "Run " + execflag + " : " + file.getPath() + "\n" + execCommand + "\n" + "timeout");
                continue;
            }
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            TestOutput output = new TestOutput(stdout, stderr, process.exitValue());
            LoggerUtil.logExec(Level.INFO, "Run " + execflag + " : " + file.getPath() + "\n" + execCommand + "\n" + output);
            execResults.put(execCommand, output);
        }
        return execResults;
    }
}
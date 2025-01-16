package edu.tju.ista.llm4test.execute;


import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class TestExecutor {
    private final String jarPath;
    private final File resultDir;

    private static final String[] execFlags = {"-Xint", "-Xcomp", "-Xmixed"};

    private static final HashMap<String, String> env = new HashMap<>();

    static {
        env.put("LANG", "en_US.UTF-8");
    }

    public TestExecutor(String jarPath, File resultDir) {
        this.jarPath = jarPath;
        this.resultDir = resultDir;
    }

    public TestResult executeTest(File file) {
        try {
            TestResult result = new TestResult(runjtreg(file));
            return result;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Execute: " + file.getPath() + "\n" + e.getMessage());
            e.printStackTrace();
            return new TestResult(TestResultKind.UNKNOWN);
        }
    }


    private TestOutput runjtreg(File file) throws IOException, InterruptedException {
        clearJTworkFiles(file);
        String jtregCommand = "jtreg -ea -va " + file.getPath();
        System.out.println("execCommand: " + jtregCommand);
        Process process = Runtime.getRuntime().exec(jtregCommand);

        boolean flag = process.waitFor(300, TimeUnit.SECONDS);
        if (!flag) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            LoggerUtil.logExec(Level.INFO, "Run: " + file.getPath() + "\n" + jtregCommand + "\n" + "timeout");
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            return new TestOutput(stdout, stderr, 124);
        }
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        TestOutput output = new TestOutput(stdout, stderr, process.exitValue());
        LoggerUtil.logExec(Level.INFO, "Run: " + file.getPath() + "\n" + jtregCommand + "\n" + output);
        return output;
    }

    public void clearJTworkFiles(File file) {
        try {
            String baseName = file.getName().replace(".java", "");
            File jtWork = new File("JTwork");
            Path relateivePath = resultDir.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath()).getParent();
            File classCache = jtWork.toPath().resolve("classes").resolve(relateivePath).resolve(baseName + ".class").toFile();
            File jtrCache = jtWork.toPath().resolve(relateivePath).resolve(baseName + ".jtr").toFile();
            File dCache = jtWork.toPath().resolve(relateivePath).resolve(baseName + ".d").toFile();
            classCache.delete();
            jtrCache.delete();
            dCache.delete();
        } catch (Exception e) {

        }
    }
}
package edu.tju.ista.llm4test;

import org.junit.Test;

import java.io.File;
import java.util.concurrent.TimeUnit;

public class runTest {

    @Test
    public void runTestCases() throws Exception {

        String className = "Regex";

        File resultDir = new File("Results");
        String jarPath = "C:\\Users\\Administrator\\.m2\\repository\\org\\testng\\testng\\6.7\\testng-6.7.jar";


        File outputFile = new File(resultDir, className + ".java");

        // Compile and execute the code
        String compileCommand = "javac -cp " + resultDir + ";" + jarPath + " " + outputFile.getPath();
        Process compileProcess = Runtime.getRuntime().exec(compileCommand);
        boolean flag = compileProcess.waitFor(30, TimeUnit.SECONDS);
        System.out.println("return code " + compileProcess.exitValue());
        if (flag && compileProcess.exitValue() == 0) {
            String execCommand = "java -cp " + resultDir.getPath() + " " + className;
            Process execProcess = Runtime.getRuntime().exec(execCommand);
            flag = execProcess.waitFor(60, TimeUnit.SECONDS);

            String result = new String(execProcess.getInputStream().readAllBytes());

            System.out.println(result);

            if (flag && execProcess.exitValue() == 0) {
                String error = new String(execProcess.getErrorStream().readAllBytes());
                System.err.println(error);
            } else {
                if (execProcess.isAlive()) {
                    execProcess.destroyForcibly();
                }
            }
            System.out.println("return code " + execProcess.exitValue());
        } else {
            if (compileProcess.isAlive()) {
                compileProcess.destroyForcibly();
            }

            String error = new String(compileProcess.getErrorStream().readAllBytes());

            System.err.println(error);
        }
    }
}

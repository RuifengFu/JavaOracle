package edu.tju.ista.llm4test.execute;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

public class executeTest {
    @Test
    public void testcompile() throws IOException, InterruptedException {
        String [] jars = new String[]{"C:\\Users\\Administrator\\.m2\\repository\\junit\\junit\\4.13.1\\junit-4.13.1.jar",
                "C:\\Users\\Administrator\\.m2\\repository\\org\\testng\\testng\\6.7\\testng-6.7.jar"};
        String jarPath = String.join(File.pathSeparator, jars);
        File ResultDir = new File("Results");
        TestExecutor executor = new TestExecutor(jarPath, ResultDir);
        File file = new File("Results\\NCopies.java");
        TestResult result = executor.executeTest(file);
        System.out.println(result.getKind());
    }
}

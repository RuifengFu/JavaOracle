package edu.tju.ista.llm4test.execute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

public class TestSuite {


    private final String rootPath;
    private final ArrayList<String> testCases;

    public TestSuite(String rootPath) {
        this.rootPath = rootPath;
        this.testCases = jtregTestSuiteFinder();
    }

    public ArrayList<String> jtregTestSuiteFinder() {
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command("jreg", "-l", rootPath);
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes());
            String[] lines = stdout.split("\n");
            return new ArrayList<>(Arrays.asList(lines).subList(1, lines.length - 1));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }
    public String getRootPath() {
        return rootPath;
    }

    public ArrayList<String> getTestCases() {
        return testCases;
    }

}

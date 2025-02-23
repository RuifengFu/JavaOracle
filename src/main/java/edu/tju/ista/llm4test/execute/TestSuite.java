package edu.tju.ista.llm4test.execute;

import edu.tju.ista.llm4test.utils.PathUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

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
            builder.command("jtreg", "-l", rootPath);
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes());
            String[] lines = stdout.split("\n");
            var list = Arrays.asList(lines).subList(1, lines.length - 1).stream().filter(s -> s.endsWith(".java")).collect(Collectors.toCollection(ArrayList::new));
            return list;
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

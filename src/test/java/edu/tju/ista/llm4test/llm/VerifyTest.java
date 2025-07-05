package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.execute.TestResultKind;
import edu.tju.ista.llm4test.llm.agents.TestCaseMinimizationAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VerifyTest {

    @TempDir
    Path tempDir;

    @Test
    public void testTestCaseMinimizationAgent() throws IOException {
        // 1. Create a dummy failing test case file programmatically in a temporary directory
        File testFile = tempDir.resolve("DummyFailingTest.java").toFile();
        String dummyCode = "public class DummyFailingTest {\n" +
                "    public static void main(String[] args) {\n" +
                "        System.out.println(\"This is an unnecessary method call.\");\n" +
                "        testMethod();\n" +
                "    }\n" +
                "    public static void testMethod() {\n" +
                "        if (!\"Hello\".equals(\"World\")) {\n" +
                "            throw new AssertionError(\"Test Failed: Expected 'Hello' but got 'World'\");\n" +
                "        }\n" +
                "    }\n" +
                "}";
        Files.writeString(testFile.toPath(), dummyCode);
        
        TestCase testCase = new TestCase(testFile);

        // 2. Simulate a failure result
        TestResult failResult = new TestResult(TestResultKind.TEST_FAIL);
        failResult.setOutput("Test Failed: Expected 'Hello' but got 'World'");
        testCase.setResult(failResult);

        // 3. Run the agent, passing the temporary directory as the parent workspace
        TestCaseMinimizationAgent agent = new TestCaseMinimizationAgent();
        File minimizedFileResult = agent.minimize(testCase, tempDir);

        // 4. Verify the output
        assertNotNull(minimizedFileResult, "Minimization should return a valid file object.");
        assertTrue(minimizedFileResult.exists(), "Minimized file was not created.");
        
        // More detailed check on the path
        Path agentWorkspace = tempDir.resolve("minimization");
        Path expectedMinimizedPath = agentWorkspace.resolve("DummyFailingTest_minimized.java");
        assertTrue(minimizedFileResult.toPath().equals(expectedMinimizedPath), "Minimized file path is not what was expected.");

        // Optional: Read and assert content of the minimized file
        String minimizedContent = Files.readString(minimizedFileResult.toPath());
        assertTrue(!minimizedContent.contains("System.out.println"), "Minimized file should not contain the unnecessary method call.");
    }
}

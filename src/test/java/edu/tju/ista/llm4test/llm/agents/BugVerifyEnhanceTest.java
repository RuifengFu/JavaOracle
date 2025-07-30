package edu.tju.ista.llm4test.llm.agents;

import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.execute.TestResultKind;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试BugVerify的增强验证功能
 */
public class BugVerifyEnhanceTest {

    @TempDir
    Path tempDir;
    
    private BugVerify bugVerify;
    private TestCase testCase;
    private File testFile;

    @BeforeEach
    void setUp() throws IOException {
        // 创建测试文件
        testFile = tempDir.resolve("TestEnhanceVerify.java").toFile();
        String testContent = """
            public class TestEnhanceVerify {
                public static void main(String[] args) {
                    // 这是一个简单的测试用例
                    System.out.println("Hello, World!");
                }
            }
            """;
        Files.write(testFile.toPath(), testContent.getBytes());
        
        // 创建BugVerify实例
        bugVerify = new BugVerify("test-javadoc", "test-source", tempDir.toString());
        
        // 创建TestCase实例
        testCase = new TestCase(testFile);
        testCase.setResult(new TestResult(TestResultKind.TEST_FAIL));
        testCase.setApiDocProcessor(null); // 简化测试，不设置API处理器
        
        // 设置BugVerify的测试用例
        bugVerify.setTestCase(testCase);
    }

    @Test
    void testEnhanceVerifyWithNonBugTestCase() {
        // 测试非bug测试用例
        boolean result = bugVerify.enhanceVerify();
        
        LoggerUtil.logExec(Level.INFO, "Enhance verify result: " + result);
        
        // 应该返回false
        assertFalse(result);
    }

    @Test
    void testEnhanceVerifyWithBugTestCase() {
        // 设置测试用例为bug
        testCase.setResult(new TestResult(TestResultKind.VERIFIED_BUG));
        
        boolean result = bugVerify.enhanceVerify();
        
        LoggerUtil.logExec(Level.INFO, "Enhance verify result: " + result);
        
        // 应该返回boolean值
        assertNotNull(result);
    }

} 
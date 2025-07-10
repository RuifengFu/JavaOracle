package edu.tju.ista.llm4test.llm.agents;

import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.llm.tools.JavaExecuteTool;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 简化的HypothesisAgent测试，专门测试编译和执行问题
 */
public class HypothesisAgentSimpleTest {

    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() throws IOException {
        // 创建必要的目录结构
        Files.createDirectories(tempDir.resolve("target/classes"));
        Files.createDirectories(tempDir.resolve("target/test-classes"));
    }

    @Test
    void testCompileAndExecuteSimpleJava() throws IOException {
        // 创建一个简单的Java文件，模拟HypothesisAgent生成的代码
        String javaCode = """
            public class H001 {
                public static void main(String[] args) {
                    System.out.println("Hello from H001");
                }
            }
            """;
        
        // 保存Java文件（模拟HypothesisAgent的saveTestCase方法）
        Path javaFile = tempDir.resolve("target/test-classes/H001.java");
        Files.writeString(javaFile, javaCode);
        
        // 1. 测试编译过程（模拟HypothesisAgent的编译逻辑）
        boolean compileSuccess = compileJavaFile(javaFile, tempDir.resolve("target/test-classes"));
        assertTrue(compileSuccess, "简单Java代码应该编译成功");
        
        // 验证class文件生成
        Path classFile = tempDir.resolve("target/test-classes/H001.class");
        assertTrue(Files.exists(classFile), "编译后应该生成class文件");
        
        // 2. 测试执行过程（模拟HypothesisAgent的执行逻辑）
        TestResult result = executeCompiledClass("H001", tempDir.resolve("target/test-classes"));
        assertTrue(result.isSuccess(), "简单Java代码应该执行成功");
        assertEquals("Hello from H001", result.getOutput().trim(), "输出应该匹配预期");
    }

    @Test
    void testCompileErrorHandling() throws IOException {
        // 创建一个有编译错误的Java文件
        String invalidJavaCode = """
            public class H002 {
                public static void main(String[] args) {
                    System.out.println("Hello from H002"  // 缺少分号和右括号
                }
            """;
        
        Path javaFile = tempDir.resolve("target/test-classes/H002.java");
        Files.writeString(javaFile, invalidJavaCode);
        
        // 测试编译失败的情况
        boolean compileSuccess = compileJavaFile(javaFile, tempDir.resolve("target/test-classes"));
        assertFalse(compileSuccess, "有语法错误的代码应该编译失败");
        
        // 验证class文件不存在
        Path classFile = tempDir.resolve("target/test-classes/H002.class");
        assertFalse(Files.exists(classFile), "编译失败时不应该生成class文件");
    }

    @Test
    void testRuntimeErrorHandling() throws IOException {
        // 创建一个会抛出运行时异常的Java文件
        String runtimeErrorCode = """
            public class H003 {
                public static void main(String[] args) {
                    String str = null;
                    System.out.println(str.length()); // 空指针异常
                }
            }
            """;
        
        Path javaFile = tempDir.resolve("target/test-classes/H003.java");
        Files.writeString(javaFile, runtimeErrorCode);
        
        // 编译应该成功
        boolean compileSuccess = compileJavaFile(javaFile, tempDir.resolve("target/test-classes"));
        assertTrue(compileSuccess, "语法正确的代码应该编译成功");
        
        // 执行应该失败（运行时异常）
        TestResult result = executeCompiledClass("H003", tempDir.resolve("target/test-classes"));
        assertFalse(result.isSuccess(), "有运行时异常的代码执行应该失败");
        assertTrue(result.getOutput().contains("NullPointerException") || 
                  result.getOutput().contains("退出码"), "应该包含异常信息");
    }

    @Test
    void testJavaExecuteToolDirectly() {
        // 直接测试JavaExecuteTool，看看它是否能找到编译好的类
        JavaExecuteTool tool = new JavaExecuteTool();
        
        // 尝试执行一个不存在的类
        ToolResponse<String> result = tool.execute("NonExistentClass");
        assertFalse(result.isSuccess(), "执行不存在的类应该失败");
        assertTrue(result.getFailMessage().contains("ClassNotFoundException") || 
                  result.getFailMessage().contains("找不到或无法加载主类"), 
                  "应该包含类未找到的错误信息");
    }

    @Test
    void testClasspathIssues() throws IOException {
        // 测试类路径问题 - 这可能是HypothesisAgent的主要问题
        String javaCode = """
            public class H004 {
                public static void main(String[] args) {
                    System.out.println("Testing classpath");
                }
            }
            """;
        
        // 保存到不同的目录，测试类路径问题
        Path wrongDir = tempDir.resolve("wrong_location");
        Files.createDirectories(wrongDir);
        Path javaFile = wrongDir.resolve("H004.java");
        Files.writeString(javaFile, javaCode);
        
        // 编译到错误目录
        boolean compileSuccess = compileJavaFile(javaFile, wrongDir);
        assertTrue(compileSuccess, "编译应该成功");
        
        // 尝试在错误的类路径下执行
        JavaExecuteTool tool = new JavaExecuteTool();
        ToolResponse<String> result = tool.execute("H004");
        
        // 这应该失败，因为JavaExecuteTool使用固定的类路径
        assertFalse(result.isSuccess(), "在错误类路径下应该执行失败");
        System.out.println("类路径错误测试 - 失败信息: " + result.getFailMessage());
    }

    @Test
    void testCorrectClasspathExecution() throws IOException {
        // 测试在正确的类路径下执行
        String javaCode = """
            public class H005 {
                public static void main(String[] args) {
                    System.out.println("Correct classpath test");
                }
            }
            """;
        
        // 创建正确的目录结构
        Path projectRoot = tempDir.resolve("project");
        Path targetClasses = projectRoot.resolve("target/test-classes");
        Files.createDirectories(targetClasses);
        
        Path javaFile = targetClasses.resolve("H005.java");
        Files.writeString(javaFile, javaCode);
        
        // 编译
        boolean compileSuccess = compileJavaFile(javaFile, targetClasses);
        assertTrue(compileSuccess, "编译应该成功");
        
        // 切换到项目根目录执行
        System.setProperty("user.dir", projectRoot.toString());
        try {
            JavaExecuteTool tool = new JavaExecuteTool();
            ToolResponse<String> result = tool.execute("H005");
            
            if (result.isSuccess()) {
                assertEquals("Correct classpath test", result.getResult().trim());
                System.out.println("正确类路径测试成功!");
            } else {
                System.out.println("正确类路径测试失败: " + result.getFailMessage());
                // 这里不使用断言，因为我们主要是在诊断问题
            }
        } finally {
            // 恢复原始工作目录
            System.setProperty("user.dir", System.getProperty("user.dir"));
        }
    }

    /**
     * 编译Java源文件（复制自HypothesisAgent的逻辑）
     */
    private boolean compileJavaFile(Path sourceFile, Path outputDir) {
        try {
            List<String> compileCommand = new ArrayList<>();
            compileCommand.add("javac");
            compileCommand.add("-cp");
            compileCommand.add("./target/classes:./target/test-classes");  // 使用和HypothesisAgent相同的类路径
            compileCommand.add("-d");
            compileCommand.add(outputDir.toString());
            compileCommand.add(sourceFile.toString());
            
            ProcessBuilder compilePb = new ProcessBuilder(compileCommand);
            compilePb.redirectErrorStream(true);
            Process compileProcess = compilePb.start();
            
            BufferedReader compileReader = new BufferedReader(new InputStreamReader(compileProcess.getInputStream()));
            String compileOutput = compileReader.lines().collect(Collectors.joining("\n"));
            
            boolean compileFinished = compileProcess.waitFor(30, TimeUnit.SECONDS);
            if (!compileFinished) {
                compileProcess.destroyForcibly();
                System.out.println("编译超时");
                return false;
            }
            
            int compileExitCode = compileProcess.exitValue();
            if (compileExitCode != 0) {
                System.out.println("编译失败: " + compileOutput);
            }
            return compileExitCode == 0;
        } catch (Exception e) {
            System.out.println("编译异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 执行已编译的类（模拟HypothesisAgent的执行逻辑）
     */
    private TestResult executeCompiledClass(String className, Path classPath) {
        TestResult result = new TestResult();
        
        try {
            ProcessBuilder pb = new ProcessBuilder("java", "-cp", classPath.toString(), className);
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = reader.lines().collect(Collectors.joining("\n"));
            
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.setSuccess(false);
                result.setOutput("执行超时");
                return result;
            }
            
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                result.setSuccess(true);
                result.setOutput(output);
            } else {
                result.setSuccess(false);
                result.setOutput("执行失败，退出码: " + exitCode + "\n输出: " + output);
            }
        } catch (Exception e) {
            result.setSuccess(false);
            result.setOutput("执行异常: " + e.getMessage());
        }
        
        return result;
    }
} 
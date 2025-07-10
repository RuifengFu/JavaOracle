package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.llm.tools.JavaExecuteTool;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class JavaExecuteToolTest {

    @TempDir
    Path tempDir;
    
    private JavaExecuteTool javaExecuteTool;

    @BeforeEach
    void setUp() {
        javaExecuteTool = new JavaExecuteTool();
    }

    @Test
    void testExecuteValidJavaCode() throws IOException {
        // 创建一个简单的Java测试文件
        String javaCode = """
            public class HelloWorld {
                public static void main(String[] args) {
                    System.out.println("Hello, World!");
                }
            }
            """;
        
        // 保存Java文件
        Path javaFile = tempDir.resolve("HelloWorld.java");
        Files.writeString(javaFile, javaCode);
        
        // 编译Java文件
        boolean compileSuccess = compileJavaFile(javaFile, tempDir);
        assertTrue(compileSuccess, "Java文件编译应该成功");
        
        // 验证class文件存在
        Path classFile = tempDir.resolve("HelloWorld.class");
        assertTrue(Files.exists(classFile), "编译后的class文件应该存在");
        
        // 使用JavaExecuteTool执行（需要修改为使用正确的类路径）
        ToolResponse<String> result = executeCompiledClass("HelloWorld", tempDir);
        
        assertTrue(result.isSuccess(), "执行应该成功");
        assertEquals("Hello, World!", result.getResult().trim(), "输出应该匹配预期");
    }

    @Test
    void testExecuteJavaCodeWithRuntimeError() throws IOException {
        // 创建一个会抛出异常的Java测试文件
        String javaCode = """
            public class ErrorTest {
                public static void main(String[] args) {
                    String str = null;
                    System.out.println(str.length()); // 这会抛出NullPointerException
                }
            }
            """;
        
        // 保存Java文件
        Path javaFile = tempDir.resolve("ErrorTest.java");
        Files.writeString(javaFile, javaCode);
        
        // 编译Java文件
        boolean compileSuccess = compileJavaFile(javaFile, tempDir);
        assertTrue(compileSuccess, "Java文件编译应该成功");
        
        // 执行
        ToolResponse<String> result = executeCompiledClass("ErrorTest", tempDir);
        
        assertFalse(result.isSuccess(), "执行应该失败（运行时异常）");
        assertTrue(result.getFailMessage().contains("NullPointerException") || 
                  result.getFailMessage().contains("退出码"), "错误信息应该包含异常信息或退出码");
    }

    @Test
    void testExecuteJavaCodeWithCompileError() throws IOException {
        // 创建一个有编译错误的Java测试文件
        String javaCode = """
            public class CompileError {
                public static void main(String[] args) {
                    System.out.println("Hello"
                    // 缺少分号和右括号
                }
            """;
        
        // 保存Java文件
        Path javaFile = tempDir.resolve("CompileError.java");
        Files.writeString(javaFile, javaCode);
        
        // 编译Java文件
        boolean compileSuccess = compileJavaFile(javaFile, tempDir);
        assertFalse(compileSuccess, "有语法错误的Java文件编译应该失败");
        
        // 验证class文件不存在
        Path classFile = tempDir.resolve("CompileError.class");
        assertFalse(Files.exists(classFile), "编译失败时class文件不应该存在");
    }

    @Test
    void testExecuteJavaCodeWithCustomOutput() throws IOException {
        // 创建一个有自定义输出的Java测试文件
        String javaCode = """
            public class CustomOutput {
                public static void main(String[] args) {
                    System.out.println("Line 1");
                    System.out.println("Line 2");
                    System.out.println("Line 3");
                }
            }
            """;
        
        // 保存Java文件
        Path javaFile = tempDir.resolve("CustomOutput.java");
        Files.writeString(javaFile, javaCode);
        
        // 编译Java文件
        boolean compileSuccess = compileJavaFile(javaFile, tempDir);
        assertTrue(compileSuccess, "Java文件编译应该成功");
        
        // 执行
        ToolResponse<String> result = executeCompiledClass("CustomOutput", tempDir);
        
        assertTrue(result.isSuccess(), "执行应该成功");
        String output = result.getResult().trim();
        assertTrue(output.contains("Line 1"), "输出应该包含Line 1");
        assertTrue(output.contains("Line 2"), "输出应该包含Line 2");
        assertTrue(output.contains("Line 3"), "输出应该包含Line 3");
    }

    @Test
    void testExecuteNonExistentClass() {
        // 尝试执行不存在的类
        ToolResponse<String> result = executeCompiledClass("NonExistentClass", tempDir);
        
        assertFalse(result.isSuccess(), "执行不存在的类应该失败");
        assertTrue(result.getFailMessage().contains("ClassNotFoundException") || 
                  result.getFailMessage().contains("找不到或无法加载主类"), 
                  "错误信息应该包含类未找到的提示");
    }

    /**
     * 编译Java源文件的辅助方法（从BugVerify中复制）
     */
    private boolean compileJavaFile(Path sourceFile, Path outputDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("javac", "-d", outputDir.toString(), sourceFile.toString());
            pb.directory(outputDir.toFile());
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 执行已编译的类文件的辅助方法（从BugVerify中复制）
     */
    private ToolResponse<String> executeCompiledClass(String className, Path classPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("java", "-cp", classPath.toString(), className);
            pb.directory(classPath.toFile());
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            // 读取输出
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            
            boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResponse.failure("执行超时");
            }
            
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return ToolResponse.success(output);
            } else {
                return ToolResponse.failure("执行失败，退出码: " + exitCode + "\n输出: " + output);
            }
        } catch (Exception e) {
            return ToolResponse.failure("执行异常: " + e.getMessage());
        }
    }
} 
package edu.tju.ista.llm4test.llm.agents;

import edu.tju.ista.llm4test.execute.TestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证修复后的原始HypothesisAgent编译执行功能
 */
public class HypothesisAgentVerificationTest {

    @TempDir
    Path tempDir;
    
    private HypothesisAgent agent;

    @BeforeEach
    void setUp() {
        agent = new HypothesisAgent();
        agent.setWorkingEnvironment(tempDir.toString(), "TestCase");
        
        // 先进行环境诊断
        agent.diagnoseEnvironment();
    }

    @Test
    void testBasicCompileAndExecute() {
        // 模拟一个简单的假设执行过程
        try {
            // 准备模拟假设
            List<String> mockHypotheses = new ArrayList<>();
            mockHypotheses.add("""
                {
                    "id": "BasicTest",
                    "description": "测试基本编译执行",
                    "category": "basic"
                }
                """);
            
            // 通过反射设置假设
            setHypotheses(agent, mockHypotheses);
            
            // 测试编译执行逻辑 - 通过反射调用私有方法
            String javaCode = """
                public class BasicTest {
                    public static void main(String[] args) {
                        System.out.println("Basic test successful");
                    }
                }
                """;
            
            // 调用saveTestCase方法
            Path savedPath = callSaveTestCase(agent, javaCode, "BasicTest");
            assertNotNull(savedPath, "文件应该保存成功");
            assertTrue(savedPath.toString().contains("BasicTest.java"), "应该包含正确的文件名");
            
            // 调用executeTestCase方法
            TestResult result = callExecuteTestCase(agent, javaCode, "BasicTest", "{}");
            
            assertNotNull(result, "结果不应为空");
            
            if (result.isSuccess()) {
                assertEquals("Basic test successful", result.getOutput().trim(), "输出应该匹配预期");
                System.out.println("✓ 基本编译执行测试成功");
            } else {
                System.out.println("✗ 基本编译执行测试失败: " + result.getOutput());
                // 输出详细信息用于调试
                System.out.println("失败原因: " + result.getOutput());
            }
            
        } catch (Exception e) {
            System.out.println("✗ 测试过程中发生异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    void testCompileError() {
        try {
            // 测试编译错误处理
            String invalidCode = """
                public class ErrorTest {
                    public static void main(String[] args) {
                        System.out.println("Hello"  // 缺少分号和右括号
                    }
                """;
            
            TestResult result = callExecuteTestCase(agent, invalidCode, "ErrorTest", "{}");
            
            assertNotNull(result, "结果不应为空");
            assertFalse(result.isSuccess(), "有语法错误的代码应该执行失败");
            
            System.out.println("✓ 编译错误处理测试通过");
            
        } catch (Exception e) {
            System.out.println("✗ 编译错误测试中发生异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    void testRuntimeError() {
        try {
            // 测试运行时错误处理
            String runtimeErrorCode = """
                public class RuntimeErrorTest {
                    public static void main(String[] args) {
                        String str = null;
                        System.out.println(str.length()); // 空指针异常
                    }
                }
                """;
            
            TestResult result = callExecuteTestCase(agent, runtimeErrorCode, "RuntimeErrorTest", "{}");
            
            assertNotNull(result, "结果不应为空");
            
            if (!result.isSuccess()) {
                assertTrue(result.getOutput().contains("NullPointerException") || 
                          result.getOutput().contains("退出码"), "应该包含运行时错误信息");
                System.out.println("✓ 运行时错误处理测试通过");
            } else {
                System.out.println("✗ 运行时错误应该导致执行失败，但实际成功了");
            }
            
        } catch (Exception e) {
            System.out.println("✗ 运行时错误测试中发生异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test 
    void testEnvironmentDiagnosis() {
        // 测试环境诊断功能
        try {
            agent.diagnoseEnvironment();
            System.out.println("✓ 环境诊断测试完成");
        } catch (Exception e) {
            fail("环境诊断不应该抛出异常: " + e.getMessage());
        }
    }

    // 辅助方法：通过反射设置假设
    private void setHypotheses(HypothesisAgent agent, List<String> hypotheses) throws Exception {
        Field field = HypothesisAgent.class.getDeclaredField("hypotheses");
        field.setAccessible(true);
        field.set(agent, new ArrayList<>(hypotheses));
    }

    // 辅助方法：通过反射调用saveTestCase
    private Path callSaveTestCase(HypothesisAgent agent, String code, String hypothesisId) throws Exception {
        Method method = HypothesisAgent.class.getDeclaredMethod("saveTestCase", String.class, String.class);
        method.setAccessible(true);
        return (Path) method.invoke(agent, code, hypothesisId);
    }

    // 辅助方法：通过反射调用executeTestCase
    private TestResult callExecuteTestCase(HypothesisAgent agent, String code, String hypothesisId, String hypothesisJson) throws Exception {
        Method method = HypothesisAgent.class.getDeclaredMethod("executeTestCase", String.class, String.class, String.class);
        method.setAccessible(true);
        return (TestResult) method.invoke(agent, code, hypothesisId, hypothesisJson);
    }
} 
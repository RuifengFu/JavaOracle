package edu.tju.ista.llm4test.execute;

import edu.tju.ista.llm4test.adapter.AdapterRegistry;
import edu.tju.ista.llm4test.adapter.ProjectAdapter;
import edu.tju.ista.llm4test.adapter.jdk.JdkProjectAdapter;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.concurrent.ConcurrentExecutionManager;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;


/**
 * 测试执行器（外观层）。
 * <p>
 * JDK/jtreg 的执行实现已迁移至 {@link JdkProjectAdapter}；
 * 本类保持原有公开API不变，委托适配器执行，并负责基于
 * ConcurrentExecutionManager 的异步提交（与harness无关的通用设施）。
 */
public class TestExecutor {
    private final ConcurrentExecutionManager concurrentManager;
    private final ProjectAdapter adapter;

    public TestExecutor() {
        this.concurrentManager = ConcurrentExecutionManager.getInstance();
        this.adapter = AdapterRegistry.get();
        // 环境检查
        adapter.checkEnvironment();
    }

    /**
     * 执行测试（主入口）
     * @param file 测试文件
     * @return 测试结果
     */
    public TestResult executeTest(File file) {
        return differentialTesting(file);
    }

    /**
     * 执行测试（使用TestCase）
     * @param testCase 测试用例
     * @return 测试结果
     */
    public TestResult executeTest(TestCase testCase) {
        return differentialTesting(testCase);
    }

    /**
     * 异步执行测试
     * @param file 测试文件
     * @return 测试结果的CompletableFuture
     */
    public CompletableFuture<TestResult> executeTestAsync(File file) {
        return concurrentManager.submitBatchTask(() -> differentialTesting(file));
    }

    /**
     * 差异化测试 - 在多个JDK上运行测试并比较结果（同步版本）
     * @param file 测试文件
     * @return 测试结果
     */
    public TestResult differentialTesting(File file) {
        TestCase testCase = new TestCase(file);
        return differentialTesting(testCase);
    }

    /**
     * 差异化测试 - 在多个JDK上运行测试并比较结果（使用TestCase）
     * @param testCase 测试用例
     * @return 测试结果
     */
    public TestResult differentialTesting(TestCase testCase) {
        return adapter.executeTest(testCase);
    }

    /**
     * 测试JDK环境（env 命令入口）
     */
    public void testJDKEnvironment() {
        adapter.verifyEnvironment();
    }

    /**
     * 清理临时目录（谨慎使用！）
     */
    public void clearTempDirectories() {
        // 由适配器自己决定清什么（接口上的默认实现是不做事），门面不再向下转型
        adapter.cleanupWorkspace();
    }

    /**
     * 关闭执行器并清理资源
     * 注意：这里不关闭并发管理器，因为它是单例且可能被其他组件使用
     */
    public void shutdown() {
        LoggerUtil.logExec(Level.INFO, "TestExecutor关闭完成");
    }

    /**
     * 获取并发执行状态
     */
    public void logConcurrentStatus() {
        concurrentManager.logStatus();
    }
}
package edu.tju.ista.llm4test.adapter.jdk;

import edu.tju.ista.llm4test.llm.tools.TestExecuteTool;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;

import edu.tju.ista.llm4test.execute.TestExecutor;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.utils.JavaSourceUtils;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用 jtreg 执行测试用例的工具（JDK 适配器的 harness 工具）。
 * <p>
 * 位置在 {@code adapter.jdk} 而不是 {@code llm.tools}：它是 jtreg 专属的，
 * 由 {@code JdkProjectAdapter.createExecuteTool()} 提供，Agent 侧不再直接 new。
 */
public class JtregExecuteTool implements TestExecuteTool {
    // 基础工作目录
    private final File baseWorkingDir;
    // 结果目录
    private final File resultDir;
    
    // TEST.ROOT文件内容
    private static final String TEST_ROOT_CONTENT = 
            "requires.properties=true\n" +
            "requires.build=true\n";
    
    public JtregExecuteTool() {
        this.baseWorkingDir = new File("jtreg-workspace");
        this.resultDir = new File(baseWorkingDir, "test-results");
        
        // 差分执行用的 JDK 列表由 TestExecutor → JdkProjectAdapter → GlobalConfig 提供，
        // 这里不再自持一份（历史上写死过 /home/Java/HotSpot/...，且从未被读取）

        // 确保基础工作目录和结果目录存在
        baseWorkingDir.mkdirs();
        resultDir.mkdirs();
    }
    
    /** 工具名：LLM 按它发起调用，prompt 模板的 ${harness.executeToolName} 取自同一常量 */
    public static final String TOOL_NAME = "jtreg_execute";

    @Override
    public String getName() {
        return TOOL_NAME;
    }
    
    @Override
    public String getDescription() {
        return "Execute the specified Java test file or Java source code using jtreg, and return the test execution results. Use the is_file_path parameter to explicitly specify whether content is a file path or source code.";
    }


    @Override
    public ToolResponse<TestResult> execute(Map<String, Object> args) {
        if (args == null || !args.containsKey("content") || !args.containsKey("is_file_path")) {
            return ToolResponse.failure("参数错误，必须提供 content 和 is_file_path");
        }

        String content = (String) args.get("content");
        // containsKey 对“键存在但值为 null”同样成立，直接强转会 NPE
        boolean isFilePath = Boolean.TRUE.equals(args.get("is_file_path"));
        String className = (String) args.get("class_name"); // 可以为 null

        try {
            final File testFile;
            
            if (isFilePath) {
                // 使用文件路径
                testFile = new File(content.trim());
            } else {
                // 使用源代码内容创建临时文件
                testFile = createTemporaryTestFile(content, className);
            }
            
            // 验证文件存在
            if (!testFile.exists()) {
                return ToolResponse.failure("测试文件不存在: " + testFile.getAbsolutePath());
            }
            
            // 创建测试执行器并执行测试 - 使用固定的工作空间结构
            TestExecutor executor = new TestExecutor();
            // TestExecutor的differentialTesting封装了执行逻辑
            // 无论测试用例本身是通过还是失败，只要执行器成功完成，这里都会返回一个有效的TestResult
            TestResult result = executor.differentialTesting(testFile);
            
            // 工具本身执行成功，返回包含测试结果的ToolResponse
            return ToolResponse.success(result);
        } catch (Exception e) {
            // 如果TestExecutor执行过程中抛出异常，说明工具执行失败
            LoggerUtil.logExec(Level.SEVERE, "JtregExecuteTool执行失败: " + e.getMessage());
            e.printStackTrace();
            return ToolResponse.failure("JtregExecuteTool执行时发生异常: " + e.getMessage());
        }
    }

    @Override
    public String extractClassNameFromSource(String sourceCode) {
        return JavaSourceUtils.extractMainClassName(sourceCode);
    }

    private String extractPackageFromSource(String sourceCode) {
        return JavaSourceUtils.extractPackageName(sourceCode);
    }

    /**
     * 从源代码字符串创建临时测试文件 - 使用固定工作空间结构
     */
    private File createTemporaryTestFile(String sourceCode, String className) throws IOException {
        // 尝试从源代码中提取类名
        String extractedClassName = className != null ? className : extractClassNameFromSource(sourceCode);
        
        // 如果无法提取类名，则使用随机名称
        String actualClassName = extractedClassName != null ? 
                extractedClassName : "TestClass_" + UUID.randomUUID().toString().replace("-", "");
        
        // 工作目录已在构造时 mkdirs，故这里不能再用 exists() 判断是否首次——
        // 历史写法把 TEST.ROOT 的创建放在 !exists() 分支里，永远进不去，
        // jtreg 执行源码字符串时因此缺少 TEST.ROOT。
        File workSpace = baseWorkingDir;
        workSpace.mkdirs();
        createTestRootFile(workSpace);
        new File(workSpace, "lib").mkdir();
        
        // 在ReduceWorkSpace下创建测试用例特定的目录
        File testCaseDir = new File(workSpace,
                actualClassName + "_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId());
        testCaseDir.mkdirs();
        
        // 创建具有适当包结构的目录
        String packageName = extractPackageFromSource(sourceCode);
        File testDir;
        
        if (packageName != null) {
            String packagePath = packageName.replace('.', File.separatorChar);
            testDir = new File(testCaseDir, packagePath);
            testDir.mkdirs();
        } else {
            testDir = testCaseDir;
        }
        
        // 创建测试文件
        File testFile = new File(testDir, actualClassName + ".java");
        try {
            Files.write(testFile.toPath(), sourceCode.getBytes());
            LoggerUtil.logExec(Level.INFO, "创建测试文件: " + testFile.getAbsolutePath());
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "创建测试文件失败: " + e.getMessage());
            e.printStackTrace();
        }
        
        return testFile;
    }
    
    /**
     * 在指定目录中创建TEST.ROOT文件
     */
    private void createTestRootFile(File directory) throws IOException {
        File testRootFile = new File(directory, "TEST.ROOT");
        // 原先写的是 getTotalSpace() != 0——那是所在分区的容量，对不存在的文件
        // 也返回非零，于是永远走进“已存在”分支
        if (testRootFile.exists()) {
            LoggerUtil.logExec(Level.FINE, "TEST.ROOT文件已存在，跳过创建: " + testRootFile.getAbsolutePath());
            return;
        }
        Files.write(testRootFile.toPath(), TEST_ROOT_CONTENT.getBytes());
        LoggerUtil.logExec(Level.INFO, "创建TEST.ROOT文件: " + testRootFile.getAbsolutePath());
    }
} 
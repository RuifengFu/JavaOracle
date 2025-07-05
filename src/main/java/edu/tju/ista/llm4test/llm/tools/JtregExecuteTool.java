package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.execute.TestExecutor;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用jtreg执行测试用例的工具
 */
public class JtregExecuteTool implements Tool<TestResult> {
    // 基础工作目录
    private final File baseWorkingDir;
    // 结果目录
    private final File resultDir;
    
    // JDK配置，与TestExecutor中保持一致
    private final List<String> jdkPaths;
    
    // TEST.ROOT文件内容
    private static final String TEST_ROOT_CONTENT = 
            "requires.properties=true\n" +
            "requires.build=true\n";
    
    public JtregExecuteTool() {
        this.baseWorkingDir = new File("jtreg-workspace");
        this.resultDir = new File(baseWorkingDir, "test-results");
        
        this.jdkPaths = new ArrayList<>();
        this.jdkPaths.add("/home/Java/HotSpot/jdk-17.0.14+7");
        this.jdkPaths.add("/home/Java/HotSpot/jdk-21.0.6+7");
        
        // 确保基础工作目录和结果目录存在
        baseWorkingDir.mkdirs();
        resultDir.mkdirs();
    }
    
    @Override
    public String getName() {
        return "jtreg_execute";
    }
    
    @Override
    public String getDescription() {
        return "使用jtreg执行指定的Java测试文件或Java源代码，返回测试执行结果。" +
               "通过 is_file_path 参数明确指定 content 是文件路径还是源代码。";
    }

    @Override
    public List<String> getParameters() {
        return List.of("content", "is_file_path", "class_name");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                "content", "Java源代码或测试文件的路径。",
                "is_file_path", "如果 'content' 是文件路径则为 true，如果是源代码则为 false。",
                "class_name", "（可选）当提供源代码时，指定主类名。"
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
                "content", "string",
                "is_file_path", "boolean",
                "class_name", "string"
        );
    }

    @Override
    public ToolResponse<TestResult> execute(Map<String, Object> args) {
        if (args == null || !args.containsKey("content") || !args.containsKey("is_file_path")) {
            return ToolResponse.failure("参数错误，必须提供 content 和 is_file_path");
        }

        String content = (String) args.get("content");
        boolean isFilePath = (Boolean) args.get("is_file_path");
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
            File reduceWorkSpace = new File(baseWorkingDir, "ReduceWorkSpace");
            TestExecutor executor = new TestExecutor();
            LoggerUtil.logExec(Level.INFO, "开始执行差分测试: " + testFile.getPath());
            TestResult result = executor.differentialTesting(testFile);
            
            return ToolResponse.success(result);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "测试执行失败: " + e.getMessage());
            e.printStackTrace();
            return ToolResponse.failure("测试执行失败: " + e.getMessage());
        }
    }

    public ToolResponse<TestResult> execute(String code) {
        return execute(Map.of(
                "content", code,
                "is_file_path", false,
                "class_name", extractClassNameFromSource(code)
        ));
    }

    /**
     * 从源代码中提取类名 - 改进版
     */
    public String extractClassNameFromSource(String sourceCode) {
        // 移除注释
        String codeWithoutComments = removeComments(sourceCode);
        
        // 更复杂的正则表达式匹配public类声明，包括修饰符和泛型
        Pattern classPattern = Pattern.compile(
            "public\\s+(?:final|abstract|strictfp|\\s)*class\\s+(\\w+)(?:<[^>]*>)?",
            Pattern.DOTALL
        );
        Matcher matcher = classPattern.matcher(codeWithoutComments);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // 如果没有public类，尝试匹配默认访问级别的类
        Pattern defaultClassPattern = Pattern.compile(
            "(?<!private|protected|public)\\s+(?:final|abstract|strictfp|\\s)*class\\s+(\\w+)(?:<[^>]*>)?",
            Pattern.DOTALL
        );
        matcher = defaultClassPattern.matcher(codeWithoutComments);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        return null;
    }


    
    /**
     * 移除Java代码中的注释
     */
    private String removeComments(String code) {
        // 移除块注释 /* ... */
        String noBlockComments = code.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        
        // 移除行注释 // ...
        StringBuilder result = new StringBuilder();
        String[] lines = noBlockComments.split("\n");
        for (String line : lines) {
            int slashIndex = line.indexOf("//");
            if (slashIndex >= 0) {
                result.append(line.substring(0, slashIndex));
            } else {
                result.append(line);
            }
            result.append("\n");
        }
        
        return result.toString();
    }
    
    /**
     * 从源代码中提取包名
     */
    private String extractPackageFromSource(String sourceCode) {
        // 匹配包声明
        Pattern packagePattern = Pattern.compile("package\\s+([\\w.]+);");
        Matcher matcher = packagePattern.matcher(sourceCode);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
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
        
        // 创建固定的ReduceWorkSpace工作目录
        File reduceWorkSpace = new File(baseWorkingDir, "ReduceWorkSpace");
        if (!reduceWorkSpace.exists()) {
            reduceWorkSpace.mkdirs();
            
            // 只在第一次创建时创建TEST.ROOT文件
            createTestRootFile(reduceWorkSpace);
            
            // 可选：创建lib目录用于共享库
            File libDir = new File(reduceWorkSpace, "lib");
            libDir.mkdir();
        }
        
        // 在ReduceWorkSpace下创建测试用例特定的目录
        File testCaseDir = new File(reduceWorkSpace, 
                actualClassName + "_" + System.currentTimeMillis());
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
        Files.write(testRootFile.toPath(), TEST_ROOT_CONTENT.getBytes());
        LoggerUtil.logExec(Level.INFO, "创建TEST.ROOT文件: " + testRootFile.getAbsolutePath());
    }
} 
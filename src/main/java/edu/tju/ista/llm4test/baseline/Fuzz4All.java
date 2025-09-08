package edu.tju.ista.llm4test.baseline;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Fuzz4All baseline工具集成类
 * 负责调用外部的Fuzz4All工具并返回结果
 */
public class Fuzz4All {

    // 静态常量
    private static final String FUZZ4ALL_DIR = "fuzz4all";
    private static final String CONFIG_DIR = FUZZ4ALL_DIR + "/config";
    private static final String DOC_DIR = FUZZ4ALL_DIR + "/doc";
    private static final String OUTPUT_DIR = FUZZ4ALL_DIR + "/outputs";
    private static final String TEMPLATE_RESOURCE_PATH = "/fuzz4all/base_template.yaml";
    private static final String FUZZ4ALL_SCRIPT = "fuzz4all/Fuzz4All/fuzz.py";

    /**
     * 静态初始化方法，创建必要的目录结构
     */
    static {
        try {
            // 创建目录结构
            Files.createDirectories(Paths.get(CONFIG_DIR));
            Files.createDirectories(Paths.get(DOC_DIR));
            Files.createDirectories(Paths.get(OUTPUT_DIR));

            LoggerUtil.logExec(Level.INFO, "Fuzz4All目录结构初始化完成");
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "Fuzz4All目录初始化失败: " + e.getMessage());
            throw new RuntimeException("Fuzz4All初始化失败", e);
        }
    }

    /**
     * 处理测试用例并返回Fuzz4All的结果
     *
     * @param testCase 测试用例对象
     * @return Fuzz4All处理后的文本结果
     */
    public static String processTestCase(TestCase testCase) {
        return processTestCase(testCase, testCase.apiDocMap);
    }

    /**
     * 处理测试用例并返回Fuzz4All的结果
     *
     * @param testCase 测试用例对象
     * @param apiDocs API文档映射
     * @return Fuzz4All处理后的文本结果
     */
    public static String processTestCase(TestCase testCase, Map<String, String> apiDocs) {
        // 生成唯一时间戳
        long threadId = Thread.currentThread().threadId();
        long timestamp = System.nanoTime();
        String stamp = String.format("%d_%d", threadId, timestamp);
        
        try {
            LoggerUtil.logExec(Level.INFO, "开始处理Fuzz4All测试用例，stamp: " + stamp);
            
            // 1. 创建API文档文件
            String docFilePath = createApiDocFile(apiDocs, stamp);
            
            // 2. 创建配置文件
            String configFilePath = createConfigFile(testCase, docFilePath, stamp);
            
            // 3. 创建输出文件夹
            String outputFolder = OUTPUT_DIR + "/output_" + stamp;
            
            // 4. 执行Fuzz4All命令
            String result = executeFuzz4AllCommand(configFilePath, outputFolder, stamp);
            
            // 5. 提取和处理代码
            result = extractAndProcessCode(result, testCase);

            // 6. 清理临时文件
            cleanupTempFiles(docFilePath, configFilePath);
            
            LoggerUtil.logExec(Level.INFO, "Fuzz4All处理完成，stamp: " + stamp);
            return result;
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Fuzz4All处理失败，stamp: " + stamp + " - " + e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    /**
     * 创建API文档文件
     */
    private static String createApiDocFile(Map<String, String> apiDocs, String stamp) throws IOException {
        String docFilePath = DOC_DIR + "/api_docs_" + stamp + ".md";
        
        StringBuilder docContent = new StringBuilder();
        docContent.append("# API Documentation\n\n");
        
        if (apiDocs != null && !apiDocs.isEmpty()) {
            for (Map.Entry<String, String> entry : apiDocs.entrySet()) {
                docContent.append("## ").append(entry.getKey()).append("\n");
                docContent.append(entry.getValue()).append("\n\n");
            }
        } else {
            docContent.append("No API documentation available.\n");
        }
        
        Files.write(Paths.get(docFilePath), docContent.toString().getBytes());
        LoggerUtil.logExec(Level.FINE, "创建API文档文件: " + docFilePath);
        
        return docFilePath;
    }

    /**
     * 创建配置文件
     */
    private static String createConfigFile(TestCase testCase, String docFilePath, String stamp) throws IOException {
        String configFilePath = CONFIG_DIR + "/config_" + stamp + ".yaml";
        String outputFolder = "outputs/output_" + stamp;

        // 从资源目录读取模板文件
        String template = readTemplateFromResources();

        // 获取测试用例的类名（去掉.java扩展名）
        String className = testCase.getFile().getName();
        if (className.endsWith(".java")) {
            className = className.substring(0, className.length() - 5);
        }

        // 替换占位符
        String config = template
                .replace("{OUTPUT_FOLDER}", outputFolder)
                .replace("{PATH_DOCUMENTATION}", docFilePath)
                .replace("{PATH_EXAMPLE_CODE}", testCase.getFile().getAbsolutePath())
                .replace("{CLASS_NAME}", className);

        Files.write(Paths.get(configFilePath), config.getBytes());
        LoggerUtil.logExec(Level.FINE, "创建配置文件: " + configFilePath + ", 类名: " + className);

        return configFilePath;
    }

    /**
     * 从资源目录读取模板文件
     */
    private static String readTemplateFromResources() throws IOException {
        URL resourceUrl = Fuzz4All.class.getResource(TEMPLATE_RESOURCE_PATH);
        if (resourceUrl == null) {
            throw new IOException("模板文件不存在: " + TEMPLATE_RESOURCE_PATH);
        }

        try (InputStream inputStream = resourceUrl.openStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        }
    }

    /**
     * 执行Fuzz4All命令
     */
    private static String executeFuzz4AllCommand(String configFilePath, String outputFolder, String stamp) throws Exception {
        String pythonPath = GlobalConfig.getFuzz4AllPythonPath();
        
        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add(FUZZ4ALL_SCRIPT);
        command.add("--config");
        command.add(configFilePath);
        command.add("main_with_config");
        command.add("--folder=" + outputFolder);
        
        LoggerUtil.logExec(Level.INFO, "执行Fuzz4All命令: " + String.join(" ", command));
        
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File("."));
        
        // 设置环境变量
        Map<String, String> env = processBuilder.environment();
        String openaiApiKey = System.getProperty("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));
        String openaiBaseUrl = System.getProperty("OPENAI_BASE_URL", System.getenv("OPENAI_BASE_URL"));
        
        if (openaiApiKey != null && !openaiApiKey.isEmpty()) {
            env.put("OPENAI_API_KEY", openaiApiKey);
            LoggerUtil.logExec(Level.FINE, "设置环境变量 OPENAI_API_KEY");
        } else {
            LoggerUtil.logExec(Level.WARNING, "未找到 OPENAI_API_KEY 环境变量");
        }
        
        if (openaiBaseUrl != null && !openaiBaseUrl.isEmpty()) {
            env.put("OPENAI_BASE_URL", openaiBaseUrl);
            LoggerUtil.logExec(Level.FINE, "设置环境变量 OPENAI_BASE_URL: " + openaiBaseUrl);
        } else {
            LoggerUtil.logExec(Level.INFO, "未设置 OPENAI_BASE_URL 环境变量，使用默认值");
        }
        
        Process process = processBuilder.start();
        boolean finished = process.waitFor(600, TimeUnit.SECONDS); // 10分钟超时
        
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Fuzz4All执行超时");
        }
        
        int exitCode = process.exitValue();
        
        // 读取stdout和stderr
        String stderr = readStream(process.getErrorStream());
        
        // 记录执行结果
        LoggerUtil.logExec(Level.INFO, "Fuzz4All执行完成, exitCode: " + exitCode + ", stamp: " + stamp);
        if (!stderr.isEmpty()) {
            if (exitCode != 0) {
                LoggerUtil.logExec(Level.WARNING, "Fuzz4All stderr: " + stderr);
            } else {
                LoggerUtil.logExec(Level.INFO, "Fuzz4All stderr: " + stderr);
            }
        }
        
        if (exitCode != 0) {
            LoggerUtil.logExec(Level.WARNING, "Fuzz4All执行返回非零退出码: " + exitCode);
        }
        
        // 读取结果文件
        return readResultFile(outputFolder);
    }

    /**
     * 读取结果文件
     */
    private static String readResultFile(String outputFolder) throws IOException {
        Path resultPath = Paths.get(outputFolder, "0.fuzz");
        
        if (Files.exists(resultPath)) {
            String content = Files.readString(resultPath);
            LoggerUtil.logExec(Level.FINE, "成功读取Fuzz4All结果文件: " + resultPath);
            return content;
        } else {
            LoggerUtil.logExec(Level.WARNING, "Fuzz4All结果文件不存在: " + resultPath);
            return "";
        }
    }

    /**
     * 读取输入流
     */
    private static String readStream(InputStream stream) throws IOException {
        return new String(stream.readAllBytes());
    }

    /**
     * 清理临时文件
     */
    private static void cleanupTempFiles(String... filePaths) {
        for (String filePath : filePaths) {
            try {
                Files.deleteIfExists(Paths.get(filePath));
                LoggerUtil.logExec(Level.FINE, "清理临时文件: " + filePath);
            } catch (IOException e) {
                LoggerUtil.logExec(Level.WARNING, "清理临时文件失败: " + filePath + " - " + e.getMessage());
            }
        }
    }

    /**
     * 检查Fuzz4All工具是否可用
     *
     * @return true如果工具可用，false否则
     */
    public static boolean isAvailable() {
        try {
            String pythonPath = GlobalConfig.getFuzz4AllPythonPath();
            File pythonFile = new File(pythonPath);
            File fuzzScript = new File(FUZZ4ALL_SCRIPT);
            
            boolean pythonExists = pythonFile.exists() && pythonFile.canExecute();
            boolean scriptExists = fuzzScript.exists();
            
            if (!pythonExists) {
                LoggerUtil.logExec(Level.WARNING, "Python路径不存在或不可执行: " + pythonPath);
            }
            if (!scriptExists) {
                LoggerUtil.logExec(Level.WARNING, "Fuzz4All脚本不存在: " + fuzzScript.getAbsolutePath());
            }
            
            return pythonExists && scriptExists;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "检查Fuzz4All可用性时出错: " + e.getMessage());
            return false;
        }
    }

    /**
     * 从Fuzz4All输出中提取代码并处理jtreg标签
     *
     * @param fuzz4allOutput Fuzz4All的输出结果
     * @param testCase 测试用例对象
     * @return 处理后的代码
     */
    private static String extractAndProcessCode(String fuzz4allOutput, TestCase testCase) {
        if (fuzz4allOutput == null || fuzz4allOutput.trim().isEmpty()) {
            return "";
        }

        try {
            // 提取代码块（类似TestCase.enhance()中的逻辑）
            ArrayList<String> codeBlocks = CodeExtractor.extractCode(fuzz4allOutput);
            String extractedCode;

            if (codeBlocks.isEmpty()) {
                // 如果没有找到代码块，直接使用原始输出
                extractedCode = fuzz4allOutput;
                LoggerUtil.logExec(Level.FINE, "未找到代码块，使用原始输出");
            } else {
                // 使用最后一个代码块（通常是最好的）
                extractedCode = codeBlocks.get(codeBlocks.size() - 1);
                LoggerUtil.logExec(Level.FINE, "提取到 " + codeBlocks.size() + " 个代码块，使用最后一个");
            }

            // 处理jtreg标签
            return processJtregTags(extractedCode, testCase);

        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "提取和处理代码时出错: " + e.getMessage());
            return fuzz4allOutput; // 出错时返回原始输出
        }
    }

    /**
     * 处理jtreg标签，如果缺少则自动添加
     *
     * @param code 测试代码
     * @param testCase 测试用例对象
     * @return 处理后的代码
     */
    private static String processJtregTags(String code, TestCase testCase) {
        if (code == null || code.trim().isEmpty()) {
            return code;
        }
        
        try {
            // 检查是否已经包含jtreg标签
            if (hasJtregTags(code)) {
                LoggerUtil.logExec(Level.FINE, "代码已包含jtreg标签，无需添加");
                return code;
            }
            
            // 检测使用的测试框架
            String framework = detectTestFramework(code);
            
            // 获取类名
            String className = extractClassName(code);
            if (className == null) {
                className = testCase.name;
            }
            
            // 生成合适的jtreg标签
            String jtregTags = generateJtregTags(framework, className, code);
            
            // 添加标签到代码中
            String result = addJtregTags(code, jtregTags);
            
            LoggerUtil.logExec(Level.INFO, "自动添加jtreg标签: " + framework + " framework, class: " + className);
            return result;
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "处理jtreg标签时出错: " + e.getMessage());
            return code; // 出错时返回原代码
        }
    }

    /**
     * 检查代码是否已包含jtreg标签
     */
    private static boolean hasJtregTags(String code) {
        return code.contains("@test") || code.contains("@run") || code.contains("@summary") || code.contains("@library");
    }

    /**
     * 检测使用的测试框架
     */
    private static String detectTestFramework(String code) {
        // 检查import语句和注解
        if (code.contains("import org.junit.jupiter.api.Test") || 
            code.contains("import org.junit.jupiter.") ||
            code.contains("@Test") && (code.contains("jupiter") || code.contains("JUnit5"))) {
            return "junit5";
        }
        
        if (code.contains("import org.junit.") && 
            (code.contains("@Test") || code.contains("@Before") || code.contains("@After"))) {
            return "junit4";
        }
        
        if (code.contains("import org.testng.") || code.contains("@Test") && code.contains("testng")) {
            return "testng";
        }
        
        // 检查是否有main方法
        if (code.contains("public static void main(")) {
            return "main";
        }
        
        // 如果有@Test注解但不确定框架，默认使用junit
        if (code.contains("@Test")) {
            return "junit4";
        }
        
        return "main"; // 默认使用main方法运行
    }

    /**
     * 从代码中提取类名
     */
    private static String extractClassName(String code) {
        // 使用正则表达式匹配 public class ClassName
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("public\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)");
        java.util.regex.Matcher matcher = pattern.matcher(code);
        
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        return null;
    }

    /**
     * 生成合适的jtreg标签
     */
    private static String generateJtregTags(String framework, String className, String code) {
        StringBuilder tags = new StringBuilder();
        
        tags.append("/*\n");
        tags.append(" * @test\n");
        tags.append(" * @summary Auto-generated test case\n");
        
        switch (framework.toLowerCase()) {
            case "junit4":
                tags.append(" * @run junit ").append(className).append("\n");
                break;
            case "junit5":
                tags.append(" * @run junit ").append(className).append("\n");
                break;
            case "testng":
                tags.append(" * @run testng ").append(className).append("\n");
                break;
            case "main":
            default:
                tags.append(" * @run main ").append(className).append("\n");
                break;
        }
        
        tags.append(" */\n");
        
        return tags.toString();
    }

    /**
     * 将jtreg标签添加到代码开头
     */
    private static String addJtregTags(String code, String jtregTags) {
        // 查找package声明或import声明的位置
        String[] lines = code.split("\n");
        int insertIndex = 0;
        
        // 跳过开头的注释和空行
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("/*") || line.startsWith("*") || line.equals("*/")) {
                insertIndex = i + 1;
            } else {
                break;
            }
        }
        
        // 构建新的代码
        StringBuilder result = new StringBuilder();
        
        // 添加前面的注释和空行
        for (int i = 0; i < insertIndex; i++) {
            result.append(lines[i]).append("\n");
        }
        
        // 添加jtreg标签
        result.append(jtregTags);
        
        // 如果有空行分隔，添加一个空行
        if (insertIndex < lines.length) {
            result.append("\n");
        }
        
        // 添加剩余的代码
        for (int i = insertIndex; i < lines.length; i++) {
            result.append(lines[i]).append("\n");
        }
        
        return result.toString();
    }
}

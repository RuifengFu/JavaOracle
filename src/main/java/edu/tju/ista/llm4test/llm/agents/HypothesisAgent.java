package edu.tju.ista.llm4test.llm.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.prompt.PromptGen;
import freemarker.template.TemplateException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static edu.tju.ista.llm4test.utils.FileUtils.saveToFile;

/**
 * 假设Agent - 负责形成和验证假设
 */
public class HypothesisAgent extends Agent {
    
    private final OpenAI llm;
    private final JavaExecuteTool executeTool;
    private final JtregExecuteTool jtregTool;
    
    // 假设相关状态
    private List<String> hypotheses = new ArrayList<>();
    private Map<String, TestResult> verificationResults = new HashMap<>();
    
    // 工作目录信息
    private String workingDir;
    private String testCaseName;
    
    // 项目根目录 - 确保所有路径基于正确的根目录
    private final String projectRoot;
    
    public HypothesisAgent() {
        this.llm = OpenAI.R1;
        this.executeTool = new JavaExecuteTool();
        this.jtregTool = new JtregExecuteTool();
        // 获取项目根目录
        this.projectRoot = System.getProperty("user.dir");
        LoggerUtil.logExec(Level.INFO, "项目根目录: " + projectRoot);
    }
    
    /**
     * 设置工作环境
     */
    public void setWorkingEnvironment(String workingDir, String testCaseName) {
        this.workingDir = workingDir;
        this.testCaseName = testCaseName;
    }
    
    /**
     * 形成假设
     * @param testCode 测试代码
     * @param testOutput 测试输出
     * @param collectedInformation 收集到的信息
     * @return 形成的假设列表
     */
    public List<String> formHypotheses(String testCode, String testOutput, String collectedInformation) {
        LoggerUtil.logExec(Level.INFO, "开始形成假设阶段");
        
        try {
            String prompt = PromptGen.generateBugVerifyFormHypothesesPrompt(testCode, testOutput, collectedInformation);
            
            // 记录prompt大小
            LoggerUtil.logExec(Level.INFO, "假设形成prompt大小: " + prompt.length() + " 字符");
            
            String response = llm.messageCompletion(prompt, 0.7, true);
            response = filterThinkingChain(response);

            hypotheses = extractJsonObjectArrayFromField(response, "hypotheses");
            
            LoggerUtil.logExec(Level.INFO, String.format("成功形成 %d 个假设", hypotheses.size()));
            
            // 保存假设
            saveHypotheses(response);
            
            return hypotheses;
        } catch (TemplateException | IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "生成假设形成prompt失败: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
    
    /**
     * 验证假设
     * @param testCode 原始测试代码
     * @return 验证结果映射
     */
    public Map<String, TestResult> verifyHypotheses(String testCode) {
        LoggerUtil.logExec(Level.INFO, "开始假设验证阶段，共有 " + hypotheses.size() + " 个假设");
        
        for (String hypothesisJson : hypotheses) {
            String hypothesisId = extractJsonFieldValue(hypothesisJson, "id");
            String hypothesisDescription = extractJsonFieldValue(hypothesisJson, "description");
            
            LoggerUtil.logExec(Level.INFO, "=== 处理假设 " + hypothesisId + " ===");
            LoggerUtil.logExec(Level.INFO, "假设描述: " + (hypothesisDescription != null ? hypothesisDescription : "无描述"));
            
            // 1. 实例化测试用例
            String testCaseCode = instantiateTestCase(testCode, hypothesisJson, hypothesisId);
            if (testCaseCode == null) {
                LoggerUtil.logExec(Level.WARNING, "✗ 实例化测试用例失败，跳过假设: " + hypothesisId);
                continue;
            }
            
            // 2. 保存测试文件
            Path testFilePath = saveTestCase(testCaseCode, hypothesisId);
            if (testFilePath == null) {
                LoggerUtil.logExec(Level.WARNING, "✗ 保存测试文件失败，跳过假设: " + hypothesisId);
                continue;
            }
            
            // 3. 执行测试用例
            TestResult result = executeTestCase(testCaseCode, hypothesisId, hypothesisJson);
            verificationResults.put(hypothesisId, result);
            
            LoggerUtil.logExec(Level.INFO, "假设 " + hypothesisId + " 验证完成: " + 
                              (result.isSuccess() ? "成功" : "失败"));
        }
        
        LoggerUtil.logExec(Level.INFO, "假设验证阶段完成，验证结果数: " + verificationResults.size());
        
        // 保存验证结果
        saveVerificationResults();
        
        return verificationResults;
    }
    
    /**
     * 获取假设列表
     */
    public List<String> getHypotheses() {
        return new ArrayList<>(hypotheses);
    }
    
    /**
     * 获取验证结果
     */
    public Map<String, TestResult> getVerificationResults() {
        return new HashMap<>(verificationResults);
    }
    
    /**
     * 实例化测试用例
     */
    private String instantiateTestCase(String testCode, String hypothesisJson, String hypothesisId) {
        try {
            String prompt = PromptGen.generateBugVerifyInstantiateTestCase(testCode, hypothesisJson);
            String text = filterThinkingChain(llm.messageCompletion(prompt));
            ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
            String code = codeBlocks.isEmpty() ? "" : codeBlocks.get(codeBlocks.size() - 1);
            
            if (code.isEmpty()) {
                LoggerUtil.logExec(Level.WARNING, "测试用例为空: " + hypothesisId);
                return null;
            }
            
            // 修改代码中的类名，使其与文件名一致
            String expectedClassName = hypothesisId;
            String actualClassName = extractClassNameFromCode(code);
            
            if (actualClassName != null && !actualClassName.equals(expectedClassName)) {
                code = code.replaceAll("public\\s+class\\s+" + Pattern.quote(actualClassName), 
                                     "public class " + expectedClassName);
                LoggerUtil.logExec(Level.INFO, "✓ 类名已修改: " + actualClassName + " → " + expectedClassName);
            }
            
            return code;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "✗ 实例化测试用例失败: " + hypothesisId + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 保存测试用例文件
     */
    private Path saveTestCase(String code, String hypothesisId) {
        try {
            // 确保目录结构存在
            Path targetDir = Paths.get(projectRoot, "target");
            Path classesDir = targetDir.resolve("classes");
            Path testClassesDir = targetDir.resolve("test-classes");
            
            Files.createDirectories(classesDir);
            Files.createDirectories(testClassesDir);
            
            // 保存到验证上下文目录（用于记录）
            if (workingDir != null) {
                Path verifyContextPath = Paths.get(workingDir);
                Path hypothesesDir = verifyContextPath.resolve("hypotheses");
                Files.createDirectories(hypothesesDir);
                
                String fileName = hypothesisId + ".java";
                Path testFilePath = hypothesesDir.resolve(fileName);
                saveToFile(testFilePath.toString(), code);
            }
            
            // 保存到target/test-classes目录，以便编译和执行
            String fileName = hypothesisId + ".java";
            Path targetTestFile = testClassesDir.resolve(fileName);
            saveToFile(targetTestFile.toString(), code);
            
            LoggerUtil.logExec(Level.INFO, "✓ 测试文件已保存: " + fileName + " (保存到target目录)");
            return targetTestFile;
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "✗ 保存测试文件失败: " + hypothesisId + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 执行测试用例
     */
    private TestResult executeTestCase(String code, String hypothesisId, String hypothesisJson) {
        TestResult result = new TestResult();
        
        // 检查是否为jtreg风格的测试
        if (code.contains("@test")) {
            LoggerUtil.logExec(Level.INFO, "使用 jtreg 执行测试: " + hypothesisId);
            ToolResponse<TestResult> jtregResult = jtregTool.execute(code);
            return jtregResult.getResult();
        }
        
        // 编译并执行普通Java测试
        LoggerUtil.logExec(Level.INFO, "编译并执行测试: " + hypothesisId);
        
        try {
            // 1. 确保目录结构存在
            Path targetDir = Paths.get(projectRoot, "target");
            Path classesDir = targetDir.resolve("classes");
            Path testClassesDir = targetDir.resolve("test-classes");
            
            Files.createDirectories(classesDir);
            Files.createDirectories(testClassesDir);
            
            // 2. 编译Java文件 - 使用绝对路径和正确的类路径
            Path javaFile = testClassesDir.resolve(hypothesisId + ".java");
            
            // 构建绝对路径的类路径
            String classpath = classesDir.toAbsolutePath() + File.pathSeparator + testClassesDir.toAbsolutePath();
            
            List<String> compileCommand = new ArrayList<>();
            compileCommand.add("javac");
            compileCommand.add("-cp");
            compileCommand.add(classpath);
            compileCommand.add("-d");
            compileCommand.add(testClassesDir.toAbsolutePath().toString());
            compileCommand.add(javaFile.toAbsolutePath().toString());
            
            LoggerUtil.logExec(Level.INFO, "编译命令: " + String.join(" ", compileCommand));
            
            ProcessBuilder compilePb = new ProcessBuilder(compileCommand);
            compilePb.directory(new File(projectRoot));  // 设置工作目录为项目根目录
            compilePb.redirectErrorStream(true);
            Process compileProcess = compilePb.start();
            
            BufferedReader compileReader = new BufferedReader(new InputStreamReader(compileProcess.getInputStream()));
            String compileOutput = compileReader.lines().collect(Collectors.joining("\n"));
            
            boolean compileFinished = compileProcess.waitFor(30, TimeUnit.SECONDS);
            if (!compileFinished) {
                compileProcess.destroyForcibly();
                result.setSuccess(false);
                result.setOutput("编译超时");
                return result;
            }
            
            int compileExitCode = compileProcess.exitValue();
            if (compileExitCode != 0) {
                LoggerUtil.logExec(Level.WARNING, "✗ 编译失败: " + hypothesisId + " - " + compileOutput);
                
                // 尝试修复测试用例
                String fixedCode = fixTestCase(code, hypothesisJson, "编译失败: " + compileOutput);
                if (fixedCode != null && !fixedCode.equals(code)) {
                    LoggerUtil.logExec(Level.INFO, "尝试使用修复后的测试用例: " + hypothesisId);
                    
                    // 保存修复后的代码并重新编译
                    saveTestCase(fixedCode, hypothesisId + "_fixed");
                    
                    // 重新编译修复后的代码
                    Path fixedJavaFile = testClassesDir.resolve(hypothesisId + "_fixed.java");
                    compileCommand.set(compileCommand.size() - 1, fixedJavaFile.toAbsolutePath().toString());
                    ProcessBuilder fixedCompilePb = new ProcessBuilder(compileCommand);
                    fixedCompilePb.directory(new File(projectRoot));  // 设置工作目录
                    fixedCompilePb.redirectErrorStream(true);
                    Process fixedCompileProcess = fixedCompilePb.start();
                    
                    BufferedReader fixedCompileReader = new BufferedReader(new InputStreamReader(fixedCompileProcess.getInputStream()));
                    String fixedCompileOutput = fixedCompileReader.lines().collect(Collectors.joining("\n"));
                    
                    boolean fixedCompileFinished = fixedCompileProcess.waitFor(30, TimeUnit.SECONDS);
                    if (!fixedCompileFinished) {
                        fixedCompileProcess.destroyForcibly();
                        result.setSuccess(false);
                        result.setOutput("修复后编译超时");
                        return result;
                    }
                    
                    int fixedCompileExitCode = fixedCompileProcess.exitValue();
                    if (fixedCompileExitCode != 0) {
                        result.setSuccess(false);
                        result.setOutput("原始编译错误: " + compileOutput + "\n修复后编译错误: " + fixedCompileOutput);
                        LoggerUtil.logExec(Level.WARNING, "✗ 修复后仍然编译失败: " + hypothesisId);
                        return result;
                    }
                    
                    // 使用修复后的类名执行
                    hypothesisId = hypothesisId + "_fixed";
                } else {
                    result.setSuccess(false);
                    result.setOutput("编译失败: " + compileOutput);
                    LoggerUtil.logExec(Level.WARNING, "✗ 编译失败且无法修复: " + hypothesisId);
                    return result;
                }
            }
            
            // 3. 使用JavaExecuteTool执行编译后的类
            LoggerUtil.logExec(Level.INFO, "✓ 编译成功，开始执行: " + hypothesisId);
            
            // 确保当前工作目录是项目根目录以便JavaExecuteTool正确工作
            String originalDir = System.getProperty("user.dir");
            System.setProperty("user.dir", projectRoot);
            
            try {
                ToolResponse<String> javaResult = executeTool.execute(hypothesisId);
                
                if (javaResult.isSuccess()) {
                    result.setSuccess(true);
                    result.setOutput(javaResult.getResult());
                    LoggerUtil.logExec(Level.INFO, "✓ 执行成功: " + hypothesisId);
                } else {
                    result.setSuccess(false);
                    result.setOutput(javaResult.getFailMessage());
                    LoggerUtil.logExec(Level.WARNING, "✗ 执行失败: " + hypothesisId + " - " + javaResult.getFailMessage());
                }
            } finally {
                // 恢复原始工作目录
                System.setProperty("user.dir", originalDir);
            }
            
        } catch (Exception e) {
            result.setSuccess(false);
            result.setOutput("执行异常: " + e.getMessage());
            LoggerUtil.logExec(Level.SEVERE, "✗ 执行异常: " + hypothesisId + " - " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 修复测试用例
     */
    private String fixTestCase(String testCase, String hypothesis, String errorMessage) {
        try {
            String prompt = PromptGen.generateFixVerificationTestCasePrompt(testCase, hypothesis, errorMessage);
            String response = filterThinkingChain(llm.messageCompletion(prompt));
            
            if (response.contains("CANNOT_FIX")) {
                LoggerUtil.logExec(Level.WARNING, "LLM无法修复测试用例");
                return null;
            }
            
            ArrayList<String> codeBlocks = CodeExtractor.extractCode(response);
            String fixedCode = codeBlocks.isEmpty() ? null : codeBlocks.get(codeBlocks.size() - 1);
            
            if (fixedCode != null && !fixedCode.trim().isEmpty()) {
                LoggerUtil.logExec(Level.INFO, "✓ 测试用例修复成功");
                return fixedCode;
            } else {
                LoggerUtil.logExec(Level.WARNING, "✗ 修复后的代码为空");
                return null;
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "✗ 修复测试用例时发生异常: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 保存假设
     */
    private void saveHypotheses(String response) {
        if (workingDir == null) return;
        
        try {
            // 创建假设目录
            Path verifyContextPath = Paths.get(workingDir);
            Path hypothesesDir = verifyContextPath.resolve("hypotheses");
            Files.createDirectories(hypothesesDir);

            saveToFile(hypothesesDir.resolve("hypotheses_response.txt").toString(), response);
            
            // 保存每个假设
            for (int i = 0; i < hypotheses.size(); i++) {
                String hypothesis = hypotheses.get(i);
                String id = extractJsonFieldValue(hypothesis, "id");
                if (id == null) id = "H" + (i + 1);
                
                saveToFile(hypothesesDir.resolve(id + ".json").toString(), hypothesis);
            }

            // 保存假设汇总
            StringBuilder summary = new StringBuilder();
            summary.append("# 假设汇总\n\n");
            for (String hypothesis : hypotheses) {
                String id = extractJsonFieldValue(hypothesis, "id");
                String description = extractJsonFieldValue(hypothesis, "description");
                String category = extractJsonFieldValue(hypothesis, "category");
                
                summary.append("## ").append(id != null ? id : "未知ID").append("\n\n");
                summary.append("- 描述: ").append(description != null ? description : "无描述").append("\n");
                summary.append("- 类别: ").append(category != null ? category : "未分类").append("\n\n");
            }
            saveToFile(hypothesesDir.resolve("_summary.md").toString(), summary.toString());
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存假设失败: " + e.getMessage());
        }
    }
    
    /**
     * 保存验证结果
     */
    private void saveVerificationResults() {
        if (workingDir == null) return;
        
        try {
            // 创建验证结果目录
            Path verifyContextPath = Paths.get(workingDir);
            Path resultsDir = verifyContextPath.resolve("verification_results");
            Files.createDirectories(resultsDir);
            
            // 保存每个验证结果
            for (Map.Entry<String, TestResult> entry : verificationResults.entrySet()) {
                String id = entry.getKey();
                TestResult result = entry.getValue();
                
                StringBuilder content = new StringBuilder();
                content.append("# 验证结果: ").append(id).append("\n\n");
                content.append("成功: ").append(result.isSuccess()).append("\n\n");
                content.append("输出:\n```\n").append(result.getOutput()).append("\n```\n");
                
                saveToFile(resultsDir.resolve(id + "_result.md").toString(), content.toString());
            }
            
            // 保存验证结果汇总
            StringBuilder summary = new StringBuilder();
            summary.append("# 验证结果汇总\n\n");
            for (Map.Entry<String, TestResult> entry : verificationResults.entrySet()) {
                summary.append("## ").append(entry.getKey()).append("\n\n");
                summary.append("- 成功: ").append(entry.getValue().isSuccess()).append("\n");
                summary.append("- 输出摘要: ").append(
                        entry.getValue().getOutput().length() > 100 
                        ? entry.getValue().getOutput().substring(0, 100) + "..." 
                        : entry.getValue().getOutput()
                ).append("\n\n");
            }
            saveToFile(resultsDir.resolve("_summary.md").toString(), summary.toString());
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存验证结果失败: " + e.getMessage());
        }
    }
    
    /**
     * 过滤LLM响应中的思维链标签
     */
    private static String filterThinkingChain(String response) {
        if (response == null) return null;
        String filtered = response.replaceAll("<thinking>[\\s\\S]*?</thinking>", "");
        return filtered.trim();
    }
    
    /**
     * 从JSON字符串中提取字段值
     */
    private String extractJsonFieldValue(String json, String fieldName) {
        json = filterThinkingChain(json);
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(json);
            JsonNode fieldNode = rootNode.path(fieldName);

            if (!fieldNode.isMissingNode()) {
                return fieldNode.isValueNode() ? fieldNode.asText() : fieldNode.toString();
            }
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "JSON字段提取失败 (" + fieldName + "): " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 从JSON中提取对象数组
     */
    private List<String> extractJsonObjectArrayFromField(String json, String fieldName) {
        json = filterThinkingChain(json);
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(json);
            JsonNode arrayNode = rootNode.path(fieldName);

            if (arrayNode.isArray()) {
                for (JsonNode node : arrayNode) {
                    result.add(node.toString().trim());
                }
                LoggerUtil.logExec(Level.INFO, "Successfully extracted " + result.size() + " objects from array field '" + fieldName + "'.");
            } else {
                LoggerUtil.logExec(Level.WARNING, "Field '" + fieldName + "' is not an array or was not found in the JSON.");
            }
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "Failed to parse JSON or extract object array field '" + fieldName + "': " + e.getMessage());
        }
        return result;
    }
    
    /**
     * 从代码中提取类名
     */
    private String extractClassNameFromCode(String code) {
        Pattern pattern = Pattern.compile("public\\s+class\\s+(\\w+)");
        Matcher matcher = pattern.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * 诊断方法 - 检查环境配置
     */
    public void diagnoseEnvironment() {
        LoggerUtil.logExec(Level.INFO, "=== HypothesisAgent 环境诊断 ===");
        LoggerUtil.logExec(Level.INFO, "当前工作目录: " + System.getProperty("user.dir"));
        LoggerUtil.logExec(Level.INFO, "项目根目录: " + projectRoot);
        LoggerUtil.logExec(Level.INFO, "Java版本: " + System.getProperty("java.version"));
        LoggerUtil.logExec(Level.INFO, "操作系统: " + System.getProperty("os.name"));
        LoggerUtil.logExec(Level.INFO, "路径分隔符: " + File.pathSeparator);
        
        // 检查target目录
        Path targetDir = Paths.get(projectRoot, "target");
        Path classesDir = targetDir.resolve("classes");
        Path testClassesDir = targetDir.resolve("test-classes");
        
        LoggerUtil.logExec(Level.INFO, "target目录存在: " + Files.exists(targetDir));
        LoggerUtil.logExec(Level.INFO, "classes目录存在: " + Files.exists(classesDir));
        LoggerUtil.logExec(Level.INFO, "test-classes目录存在: " + Files.exists(testClassesDir));
        
        // 检查javac和java命令
        try {
            Process javacProcess = new ProcessBuilder("javac", "-version").start();
            javacProcess.waitFor(5, TimeUnit.SECONDS);
            LoggerUtil.logExec(Level.INFO, "javac命令可用: " + (javacProcess.exitValue() == 0));
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "javac命令检查失败: " + e.getMessage());
        }
        
        try {
            Process javaProcess = new ProcessBuilder("java", "-version").start();
            javaProcess.waitFor(5, TimeUnit.SECONDS);
            LoggerUtil.logExec(Level.INFO, "java命令可用: " + (javaProcess.exitValue() == 0));
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "java命令检查失败: " + e.getMessage());
        }
        
        LoggerUtil.logExec(Level.INFO, "=== 诊断完成 ===");
    }
} 
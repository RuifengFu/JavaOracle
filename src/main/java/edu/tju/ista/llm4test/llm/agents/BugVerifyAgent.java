package edu.tju.ista.llm4test.llm.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.*;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class BugVerifyAgent extends Agent {
    // 基础提示模板
    private final String basePrompt = """
                <TestCase>
                ${testcase}
                </TestCase>
                
                <Test Output>
                ${testOutput}
                </Test Output>
                
                <API docs>
                ${apiDocs}
                </API docs>
                
                <Task>
                Analyze the reason for the test case failure and provide a root cause analysis.
                </Task>
                
                <Requirements>
                1. Summarize your analysis into a single function call as the output.
                2. Ensure your analysis is accurate, especially for JDK functions.
                </Requirements>
                """;
    
    // 可用的工具
    private final JavaDocSearchTool javadocTool;
    private final SourceCodeSearchTool sourceTool;
    private final WebContentExtractor webTool;
    private final BingSearch searchTool;
    private final JavaExecuteTool executeTool;
    private final JtregExecuteTool jtregTool;
    
    // LLM实例
    private final OpenAI llm;
    private final OpenAI llm_json;
    
    // 分析状态
    private TestCase testCase;
    private String testCode;
    private String testOutput;
    private String initialAnalysis;
    private Map<String, Object> collectedInfo = new HashMap<>();
    private List<String> hypotheses = new ArrayList<>();
    private Map<String, TestResult> verificationResults = new HashMap<>();
    private String conclusion;
    
    // 报告输出路径
    private String bugReportPath = "BugReport";
    private String verifyContextFolder = null;
    private String testCaseName = null;
    
    // 信息源标记
    private int infoCounter = 0;
    private Map<String, String> infoSourceMap = new HashMap<>();
    
    /**
     * 创建BugVerifyAgent
     * @param javadocPath JavaDoc路径
     * @param sourcePath 源码路径
     */
    public BugVerifyAgent(String javadocPath, String sourcePath) {
        this.llm = OpenAI.R1;
        this.llm_json = OpenAI.V3_json;
        this.javadocTool = new JavaDocSearchTool(javadocPath);
        this.sourceTool = new SourceCodeSearchTool(sourcePath);
        this.webTool = new WebContentExtractor(true);
        this.searchTool = new BingSearch();
        this.executeTool = new JavaExecuteTool();
        this.jtregTool = new JtregExecuteTool();
    }
    
    /**
     * 创建BugVerifyAgent并指定BugReport路径
     * @param javadocPath JavaDoc路径
     * @param sourcePath 源码路径
     * @param bugReportPath BugReport路径
     */
    public BugVerifyAgent(String javadocPath, String sourcePath, String bugReportPath) {
        this(javadocPath, sourcePath);
        if (bugReportPath != null && !bugReportPath.isEmpty()) {
            this.bugReportPath = bugReportPath;
        }
    }


    public void setTestData(TestCase testCase) {
        this.testCase = testCase;
        this.testCode = testCase.getSourceCode();
        this.testOutput = testCase.getResult().getOutput();
        this.initialAnalysis = testCase.verifyMessage;
    }

    /**
     * 设置测试用例和输出
     */
    public void setTestData(String testCase, String testOutput, String initialAnalysis) {
        this.testCode = testCase;
        this.testOutput = testOutput;
        this.initialAnalysis = initialAnalysis;
        
        // 创建验证上下文文件夹
        createVerifyContextFolder();
    }
    
    /**
     * 创建验证上下文文件夹
     */
    private void createVerifyContextFolder() {
        try {
            // 提取测试类名作为文件夹名称
            testCaseName = extractClassNameFromCode(testCode);
            if (testCaseName == null) {
                testCaseName = "UnknownTest";
            }
            
            // 创建以时间戳为后缀的验证上下文文件夹
            String timestamp = String.valueOf(System.currentTimeMillis());
            verifyContextFolder = "VerifyContext_" + timestamp;
            
            // 创建完整路径
            Path testCasePath = Paths.get(bugReportPath, testCaseName);
            Path verifyContextPath = testCasePath.resolve(verifyContextFolder);
            Files.createDirectories(verifyContextPath);
            
            // 保存测试用例和输出
            saveToFile(testCasePath.resolve(testCaseName + ".java").toString(), testCode);
            saveToFile(verifyContextPath.resolve("output.txt").toString(), testOutput);
            if (initialAnalysis != null) {
                saveToFile(verifyContextPath.resolve("initial_analysis.txt").toString(), initialAnalysis);
            }
            
            LoggerUtil.logExec(Level.INFO, "创建验证上下文文件夹: " + verifyContextPath);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "创建验证上下文文件夹失败: " + e.getMessage());
        }
    }
    
    /**
     * 保存内容到文件
     */
    private void saveToFile(String filePath, String content) {
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
            LoggerUtil.logExec(Level.INFO, "已保存文件: " + filePath);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存文件失败 " + filePath + ": " + e.getMessage());
        }
    }
    
    /**
     * 执行完整的Bug验证流程
     */
    public String analyze() {
        LoggerUtil.logExec(Level.INFO, "开始Bug验证流程");
        
        // 1. 初始分析
        String initialInsight = performInitialAnalysis();
        LoggerUtil.logExec(Level.INFO, "初始分析完成：" + initialInsight);
        
        // 保存初始分析结果
        if (verifyContextFolder != null && testCaseName != null) {
            Path verifyContextPath = Paths.get(bugReportPath, testCaseName, verifyContextFolder);
            saveToFile(verifyContextPath.resolve("initial_insight.json").toString(), initialInsight);
        }
        
        // 2. 收集信息
        collectRelevantInformation(initialInsight);
        LoggerUtil.logExec(Level.INFO, "信息收集完成，共 " + collectedInfo.size() + " 项");
        
        // 保存收集到的信息
        saveCollectedInfo();
        
        // 3. 形成假设
        formHypotheses();
        LoggerUtil.logExec(Level.INFO, "形成 " + hypotheses.size() + " 个假设");

        
        // 4. 验证假设
        verifyHypotheses();
        LoggerUtil.logExec(Level.INFO, "验证完成，结果数: " + verificationResults.size());
        
        // 保存验证结果
        saveVerificationResults();
        
        // 5. 形成结论和报告
        String report = generateReport();
        LoggerUtil.logExec(Level.INFO, "Bug验证报告已生成");
        
        // 保存最终报告
        if (testCaseName != null) {
            saveToFile(Paths.get(bugReportPath, testCaseName, "BugReport.md").toString(), report);
        }
        
        return report;
    }
    
    /**
     * 过滤LLM响应中的思维链标签
     * @param response LLM响应内容
     * @return 过滤后的内容
     */
    private static String filterThinkingChain(String response) {
        if (response == null) return null;
        LoggerUtil.logExec(Level.INFO, "过滤思维链标签\n" + response);
        // 移除<think>...</think>标签及其内容
        String filtered = response.replaceAll("<thinking>[\\s\\S]*?</thinking>", "");
        LoggerUtil.logExec(Level.INFO, "过滤后的内容\n" + filtered.trim());
        // 返回清理后的响应
        return filtered.trim();
    }

    /*
        * 过滤JSON标记
        * @param response LLM响应内容
        * 去掉里面的 ```json 和 ``` 标签
     */
    private static String filiterJsonMark(String response) {
        if (response == null) return null;
        var filtered = response.replaceFirst("^```(json)?", "").replaceFirst("```\\s*$$", "");
        return filtered;
    }
    
    /**
     * 初始分析阶段 - 分析测试用例和输出，确定问题性质
     */
    private String performInitialAnalysis() {
        String prompt = """
                你是一位Java Bug分析专家。请分析以下测试用例和其失败输出，确定问题的性质。
                
                <TestCase>
                %s
                </TestCase>
                
                <Test Output>
                %s
                </Test Output>
                
                <Initial Analysis>
                %s
                </Initial Analysis>
                
                请提供关于这个Bug的初步分析:
                1. Bug的主要症状是什么?
                2. 可能涉及的Java类或API有哪些?
                3. 错误可能发生在代码的哪个部分?
                4. 需要查询哪些额外信息来深入了解这个问题?
                
                输出格式:
                {
                  "symptoms": "简要描述Bug的症状",
                  "relevantClasses": ["类名1", "类名2"],
                  "errorLocation": "错误可能发生的代码部分",
                  "queries": ["需要查询的信息1", "需要查询的信息2"]
                }
                """.formatted(testCode, testOutput, initialAnalysis != null ? initialAnalysis : "无初步分析");
        
        String response = llm.messageCompletion(prompt);
        return filterThinkingChain(response);
    }
    
    /**
     * 信息收集阶段 - 根据初始分析收集相关信息
     */
    private void collectRelevantInformation(String initialInsight) {
        // 解析初始分析结果中的相关类和查询
        List<String> relevantClasses = extractJsonArrayFromField(initialInsight, "relevantClasses");
        List<String> queries = extractJsonArrayFromField(initialInsight, "queries");
        
        // 收集JavaDoc信息
        for (String className : relevantClasses) {
            ToolResponse<String> docResponse = javadocTool.execute(className);
            if (docResponse.isSuccess()) {
                String infoId = "INFO_" + (++infoCounter);
                String sourcePath = "JavaDoc: " + className;
                infoSourceMap.put(infoId, sourcePath);
                
                // 添加信息源标记
                String markedContent = "[" + infoId + " 来源: " + sourcePath + "]\n" + docResponse.getResult();
                collectedInfo.put("javadoc_" + className, markedContent);
            }
        }
        
        // 收集源码信息
        for (String className : relevantClasses) {
            ToolResponse<String> sourceResponse = sourceTool.execute(className);
            if (sourceResponse.isSuccess()) {
                String infoId = "INFO_" + (++infoCounter);
                String sourcePath = "源码: " + className;
                infoSourceMap.put(infoId, sourcePath);
                
                // 添加信息源标记
                String markedContent = "[" + infoId + " 来源: " + sourcePath + "]\n" + sourceResponse.getResult();
                collectedInfo.put("source_" + className, markedContent);
            }
        }
        
        // 根据查询收集Web信息
        for (String query : queries) {
            // 首先尝试直接在Web搜索
            ToolResponse<List<SearchResult>> searchResponse = searchTool.execute(query + " java");
            if (searchResponse.isSuccess() && !searchResponse.getResult().isEmpty()) {
                // 获取前三个结果的内容
                int count = 0;
                for (SearchResult result : searchResponse.getResult()) {
                    if (count >= 3) break;
                    try {
                        ToolResponse<String> contentResponse = webTool.execute(result.getUrl());
                        if (contentResponse.isSuccess()) {
                            String infoId = "INFO_" + (++infoCounter);
                            String sourceUrl = result.getUrl();
                            infoSourceMap.put(infoId, sourceUrl);
                            
                            // 添加信息源标记
                            String markedContent = "[" + infoId + " 来源: " + sourceUrl + "]\n" + contentResponse.getResult();
                            collectedInfo.put("web_" + query + "_" + count, markedContent);
                            count++;
                        }
                    } catch (Exception e) {
                        LoggerUtil.logExec(Level.WARNING, "获取网页内容失败: " + e.getMessage());
                    }
                }
            }
        }
    }
    
    /**
     * 保存收集到的信息
     */
    private void saveCollectedInfo() {
        if (verifyContextFolder == null || testCaseName == null) return;
        
        try {
            // 创建info子目录
            Path verifyContextPath = Paths.get(bugReportPath, testCaseName, verifyContextFolder);
            Path infoDir = verifyContextPath.resolve("collected_info");
            Files.createDirectories(infoDir);
            
            // 保存每项收集到的信息
            for (Map.Entry<String, Object> entry : collectedInfo.entrySet()) {
                if (entry.getValue() instanceof String) {
                    String fileName = entry.getKey().replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt";
                    saveToFile(infoDir.resolve(fileName).toString(), (String) entry.getValue());
                }
            }
            
            // 保存汇总信息
            StringBuilder summary = new StringBuilder();
            summary.append("# 收集的信息汇总\n\n");
            for (String key : collectedInfo.keySet()) {
                summary.append("- ").append(key).append("\n");
            }
            
            // 添加信息源映射
            summary.append("\n## 信息源映射\n\n");
            for (Map.Entry<String, String> entry : infoSourceMap.entrySet()) {
                summary.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            
            saveToFile(infoDir.resolve("_summary.md").toString(), summary.toString());
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存收集的信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 假设形成阶段 - 基于收集到的信息形成假设
     */
    private void formHypotheses() {
        // 构建提示，包含所有收集到的信息
        StringBuilder infoBuilder = new StringBuilder();
//        for (Map.Entry<String, Object> entry : collectedInfo.entrySet()) {
//            if (entry.getValue() instanceof String) {
//                String content = (String) entry.getValue();
//                // 限制每项内容的长度，避免提示过长
//                if (content.length() > 30000) {
//                    content = content.substring(0, 30000) + "...(内容已截断)";
//                }
//                if (infoBuilder.length() + content.length() > 100000) {
//                    break;
//                }
//                infoBuilder.append("<").append(entry.getKey()).append(">\n");
//                infoBuilder.append(content).append("\n");
//                infoBuilder.append("</").append(entry.getKey()).append(">\n\n");
//            }
//        }
        
        String prompt = """
                你是一位Java Bug分析专家。基于测试用例、测试输出和收集到的信息，形成关于问题原因的假设。
                
                <TestCase>
                %s
                </TestCase>
                
                <Test Output>
                %s
                </Test Output>
                
                <Collected Information>
                %s
                </Collected Information>
                
                请形成3-5个可能的假设，解释这个问题的原因。请务必考虑以下所有可能性：
                1. JDK实现中的真正bug
                2. 测试用例违反了API规范或使用文档
                3. 测试用例中的逻辑错误
                4. 测试用例期望的行为超出了API保证的范围
                5. 环境或配置问题
                
                每个假设应该具体明确，并且可以通过修改代码来验证。
                
                在分析过程中，请明确标注你引用的信息来源，使用[INFO_X]格式引用，其中X是信息编号。
                例如："根据[INFO_1]中的JavaDoc文档，HashMap在并发环境下不是线程安全的..."
                
                输出格式:
                {
                  "hypotheses": [
                    {
                      "id": "H1",
                      "description": "假设描述",
                      "category": "JDK_BUG|TEST_ERROR|SPEC_VIOLATION|UNDEFINED_BEHAVIOR|ENVIRONMENT_ISSUE",
                      "rationale": "为什么这是合理的假设",
                      "verificationCode": "可用于验证这个假设的Java测试代码"
                    },
                    ...
                  ]
                }
                """.formatted(testCode, testOutput, infoBuilder.toString());
        
        String response = llm.messageCompletion(prompt);
        hypotheses = extractJsonObjectArrayFromField(response, "hypotheses");
        
        // 立即保存假设
        saveHypotheses();
    }
    
    /**
     * 保存假设
     */
    private void saveHypotheses() {
        if (verifyContextFolder == null || testCaseName == null) return;
        
        try {
            // 创建假设目录
            Path verifyContextPath = Paths.get(bugReportPath, testCaseName, verifyContextFolder);
            Path hypothesesDir = verifyContextPath.resolve("hypotheses");
            Files.createDirectories(hypothesesDir);
            
            // 保存每个假设
            for (int i = 0; i < hypotheses.size(); i++) {
                String hypothesis = hypotheses.get(i);
                String id = extractJsonFieldValue(hypothesis, "id");
                if (id == null) id = "H" + (i + 1);
                
                saveToFile(hypothesesDir.resolve(id + ".json").toString(), hypothesis);
                
                // 如果有验证代码，也单独保存
                String verificationCode = extractJsonFieldValue(hypothesis, "verificationCode");
                if (verificationCode != null && !verificationCode.isEmpty()) {
                    saveToFile(hypothesesDir.resolve(id + "_verification.java").toString(), verificationCode);
                }
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
     * 假设验证阶段 - 通过执行测试来验证假设
     */
    private void verifyHypotheses() {
        for (String hypothesisJson : hypotheses) {
            String verificationCode = extractJsonFieldValue(hypothesisJson, "verificationCode");
            String hypothesisId = extractJsonFieldValue(hypothesisJson, "id");
            
            if (verificationCode != null && !verificationCode.isEmpty()) {
                // 执行验证代码
                ToolResponse<TestResult> result = jtregTool.execute(verificationCode);
                if (result.isSuccess()) {
                    verificationResults.put(hypothesisId, result.getResult());
                } else {
                    // 如果jtreg执行失败，尝试使用普通Java执行
                    String className = extractClassNameFromCode(verificationCode);
                    if (className != null) {
                        ToolResponse<String> javaResult = executeTool.execute(className);
                        // 创建一个简单的TestResult对象记录执行结果
                        TestResult testResult = new TestResult();
                        testResult.setSuccess(javaResult.isSuccess());
                        testResult.setOutput(javaResult.isSuccess() ? javaResult.getResult() : javaResult.getMessage());
                        verificationResults.put(hypothesisId, testResult);
                    }
                }
            }
        }
        
        // 立即保存验证结果
        saveVerificationResults();
    }
    
    /**
     * 保存验证结果
     */
    private void saveVerificationResults() {
        if (verifyContextFolder == null || testCaseName == null) return;
        
        try {
            // 创建验证结果目录
            Path verifyContextPath = Paths.get(bugReportPath, testCaseName, verifyContextFolder);
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
     * 结论形成阶段 - 生成最终报告
     */
    private String generateReport() {
        // 构建验证结果
        StringBuilder resultsBuilder = new StringBuilder();
        for (Map.Entry<String, TestResult> entry : verificationResults.entrySet()) {
            resultsBuilder.append("<").append(entry.getKey()).append("_Result>\n");
            TestResult result = entry.getValue();
            resultsBuilder.append("成功: ").append(result.isSuccess()).append("\n");
            resultsBuilder.append("输出:\n").append(result.getOutput()).append("\n");
            resultsBuilder.append("</").append(entry.getKey()).append("_Result>\n\n");
        }
        
        // 构建假设信息
        StringBuilder hypothesesBuilder = new StringBuilder();
        for (String hypothesis : hypotheses) {
            hypothesesBuilder.append(hypothesis).append("\n\n");
        }
        
        // 检查是否有测试用例问题相关的假设被验证
        boolean hasTestCaseIssueHypothesis = false;
        String testIssueHypothesisId = "";
        
        for (String hypothesisJson : hypotheses) {
            String category = extractJsonFieldValue(hypothesisJson, "category");
            String hypothesisId = extractJsonFieldValue(hypothesisJson, "id");
            
            if (category != null && (
                    category.equals("TEST_ERROR") || 
                    category.equals("SPEC_VIOLATION") || 
                    category.equals("UNDEFINED_BEHAVIOR"))) {
                
                // 检查验证结果是否支持这个假设
                TestResult result = verificationResults.get(hypothesisId);
                if (result != null && result.isSuccess()) {
                    hasTestCaseIssueHypothesis = true;
                    testIssueHypothesisId = hypothesisId;
                    break;
                }
            }
        }
        
        // 构建信息源映射
        StringBuilder infoSourceBuilder = new StringBuilder();
        infoSourceBuilder.append("# 信息源映射\n\n");
        for (Map.Entry<String, String> entry : infoSourceMap.entrySet()) {
            infoSourceBuilder.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        
        // 根据是否为测试用例问题调整提示模板
        String reportTemplate;
        if (hasTestCaseIssueHypothesis) {
            reportTemplate = """
                    你是一位Java测试专家。分析表明这可能是测试用例问题而非JDK bug。请生成一份分析报告。
                    
                    <TestCase>
                    %s
                    </TestCase>
                    
                    <Test Output>
                    %s
                    </Test Output>
                    
                    <Hypotheses>
                    %s
                    </Hypotheses>
                    
                    <Verification Results>
                    %s
                    </Verification Results>
                    
                    <Information Sources>
                    %s
                    </Information Sources>
                    
                    请生成一份详细的测试用例问题分析报告，包括:
                    1. 问题概述（明确指出这是测试用例问题而非JDK bug）
                    2. 测试用例中的具体问题
                    3. 相关API正确用法说明
                    4. 修复测试用例的建议
                    5. 相关文档参考
                    
                    在分析过程中，请明确标注你引用的信息来源，使用[INFO_X]格式引用，其中X是信息编号。
                    
                    请使用Markdown格式输出报告。
                    """;
        } else {
            // 使用原有的Bug报告模板
            reportTemplate = """
                    你是一位Java Bug分析专家。请基于测试用例、初始分析和验证结果，生成一份完整的Bug报告。
                    
                    <TestCase>
                    %s
                    </TestCase>
                    
                    <Test Output>
                    %s
                    </Test Output>
                    
                    <Hypotheses>
                    %s
                    </Hypotheses>
                    
                    <Verification Results>
                    %s
                    </Verification Results>
                    
                    <Information Sources>
                    %s
                    </Information Sources>
                    
                    请生成一份完整的Bug报告，包括:
                    1. Bug概述，明确说明这是测试用例问题还是JDK BUG
                    2. 复现步骤
                    3. 根本原因分析
                    4. 建议修复方案
                    5. 相关API或类的使用说明
                    
                    在分析过程中，请明确标注你引用的信息来源，使用[INFO_X]格式引用，其中X是信息编号。
                    
                    请使用Markdown格式输出报告。
                    """;
        }
        
        String prompt = reportTemplate.formatted(testCode, testOutput,
                hypothesesBuilder.toString(), resultsBuilder.toString(), infoSourceBuilder.toString());
        
        conclusion = llm.messageCompletion(prompt);
        return conclusion;
    }
    
    /**
     * 从JSON字符串中提取字段值
     */
    private String extractJsonFieldValue(String json, String fieldName) {
        // 先过滤掉思维链标签
        json = filterThinkingChain(json);
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(json);
            JsonNode fieldNode = rootNode.path(fieldName);

            if (!fieldNode.isMissingNode()) {
                // 如果是文本节点，直接返回文本值；否则返回节点的字符串表示
                return fieldNode.isValueNode() ? fieldNode.asText() : fieldNode.toString();
            }
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "JSON字段提取失败 (" + fieldName + "): " + e.getMessage() + "\nJSON: " + json);
        }
        return null;
    }
    
    /**
     * 从JSON中提取字符串数组
     */
    private List<String> extractJsonArrayFromField(String json, String fieldName) {
        // 先过滤掉思维链标签
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
                for (JsonNode elementNode : arrayNode) {
                    // 提取数组中每个元素的值作为字符串
                    result.add(elementNode.asText());
                }
            } else {
                LoggerUtil.logExec(Level.WARNING, "JSON字段 '" + fieldName + "' 不是一个数组或未找到。\nJSON: " + json);
            }
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "JSON数组提取失败 (" + fieldName + "): " + e.getMessage() + "\nJSON: " + json);
        }
        return result;
    }
    
    /**
     * 使用LLM提取并格式化字符串中的JSON内容
     * @param input 包含JSON的原始字符串
     * @return 格式化后的JSON字符串，如果失败则返回null或原始输入
     */
    private String string2json(String input) {
        if (input == null || input.trim().isEmpty()) {
            return input;
        }
        // 先尝试直接解析，如果已经是合法JSON，则直接返回
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.readTree(input.trim());
            LoggerUtil.logExec(Level.FINE, "输入已经是合法JSON，直接返回");
            return input.trim(); // Already valid JSON
        } catch (IOException e) {
            // Not valid JSON, proceed with LLM formatting
            LoggerUtil.logExec(Level.FINE, "输入不是合法JSON，尝试使用LLM提取和格式化");
        }

        String prompt = """
                从以下文本中提取唯一的、完整的 JSON 对象或数组。
                严格只返回提取到的 JSON 内容，不要包含任何解释、代码标记（例如 ```json ```）或其他文本。
                确保输出是一个语法完全正确的 JSON 结构。

                Example Output:
                {
                    "hypotheses": [
                        {
                            "id": "H1",
                            "description": "JDK在MessageDigest.getInstance中使用传入的算法名称的大小写作为getAlgorithm()的返回值，而非标准化为大写形式。",
                            "category": "JDK_BUG",
                            "rationale": "测试失败显示实际算法名称为小写的'sha'，而预期为大写的'SHA'。若JDK未正确处理算法名称的大小写规范化，导致返回实例的算法名称与传入参数一致而非标准名称，则属于实现错误。",
                            "verificationCode": "MessageDigest md = MessageDigest.getInstance(\"sha\");\nSystem.out.println(md.getAlgorithm()); // 观察输出是否为'SHA'"
                        },
                        {
                            "id": "H2",
                            "description": "测试用例错误地假设getAlgorithm()返回的算法名称必须为大写，而API规范允许提供者返回任意大小写形式。",
                            "category": "TEST_ERROR",
                            "rationale": "Java API文档未明确要求getAlgorithm()必须返回大写名称，测试用例对大小写的严格校验可能不符合规范。若提供者返回小写名称是合法的，则测试用例存在逻辑错误。",
                            "verificationCode": "检查javadoc中MessageDigest.getAlgorithm()的规范，确认返回值是否保证标准化大小写。"
                        },
                        {
                            "id": "H3",
                            "description": "SUN提供者在特定JDK版本中将'SHA'算法注册为小写名称，导致getAlgorithm()返回'sha'。",
                            "category": "ENVIRONMENT_ISSUE",
                            "rationale": "若测试使用的安全提供者内部注册的算法名称实际为小写（如\"sha\"），则测试预期的大写形式'SHA'不成立，需检查提供者的注册配置。",
                            "verificationCode": "Provider p = Security.getProvider(\"SUN\");\nSystem.out.println(p.getService(\"MessageDigest\", \"SHA\")); // 查看注册的算法名称"
                        }
                    ]
                }

                请直接输出提取的 JSON:
                """.formatted(input);

        try {
            // 使用 llm_json 模型来确保返回的是 JSON 格式
            String jsonOutput = llm_json.messageCompletion(prompt);

            // 再次验证LLM返回的是否是合法JSON
            try {
                 ObjectMapper objectMapper = new ObjectMapper();
                 objectMapper.readTree(jsonOutput);
                 LoggerUtil.logExec(Level.INFO, "LLM成功返回合法JSON");
                 return jsonOutput;
            } catch (IOException validationError) {
                 LoggerUtil.logExec(Level.WARNING, "LLM返回的不是合法JSON: " + validationError.getMessage() + "\n返回内容:\n" + jsonOutput);
                 // 返回原始输入或null作为后备
                 return null;
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "调用LLM进行JSON格式化失败: " + e.getMessage());
            return null; // Indicate failure
        }
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
     * 执行Bug验证的主方法
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java BugVerifyAgent <javadoc_path> <source_path> <test_file> [bug_report_path]");
            return;
        }

        String javadocPath = args[0];
        String sourcePath = args[1];
        String bugReportPath = args.length > 3 ? args[3] : "BugReport";

        // 创建Agent
        BugVerifyAgent agent = new BugVerifyAgent(javadocPath, sourcePath, bugReportPath);

        // 如果有测试文件，读取文件内容
        if (args.length > 2) {
            try {
                String testCase = new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get(args[2])));

                // 运行测试并获取输出
                JtregExecuteTool jtregTool = new JtregExecuteTool();
                ToolResponse<TestResult> result = jtregTool.execute(args[2]);

                String testOutput = result.isSuccess()
                        ? result.getResult().getOutput()
                        : "执行失败: " + result.getMessage();

                // 设置测试数据
                agent.setTestData(testCase, testOutput, null);

                // 分析Bug
                String report = agent.analyze();
                System.out.println(report);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("示例测试:");
            // 示例测试用例
            String sampleTest = """
                    /**
                     * @test
                     */
                    import java.util.HashMap;
                    import java.util.Map;
                    
                    public class HashMapConcurrencyTest {
                        public static void main(String[] args) throws Exception {
                            final Map<String, String> map = new HashMap<>();
                            
                            Thread t1 = new Thread(() -> {
                                for (int i = 0; i < 1000; i++) {
                                    map.put("Key" + i, "Value" + i);
                                }
                            });
                            
                            Thread t2 = new Thread(() -> {
                                for (int i = 0; i < 1000; i++) {
                                    map.put("ThreadB" + i, "Value" + i);
                                }
                            });
                            
                            t1.start();
                            t2.start();
                            t1.join();
                            t2.join();
                            
                            System.out.println("Map size should be 2000: " + map.size());
                            assert map.size() == 2000 : "Map size is not 2000, it's " + map.size();
                        }
                    }
                    """;

            String sampleOutput = """
                    Map size should be 2000: 1986
                    Exception in thread "main" java.lang.AssertionError: Map size is not 2000, it's 1986
                        at HashMapConcurrencyTest.main(HashMapConcurrencyTest.java:28)
                    """;

            // 设置测试数据
            agent.setTestData(sampleTest, sampleOutput, "HashMap在并发环境下可能丢失条目");

            // 分析Bug
            String report = agent.analyze();
            System.out.println(report);
            agent.close();
        }
    }

    /**
     * 从日志文件中提取已验证的bug并生成报告
     * @param logPath 日志文件路径
     * @param javadocPath JavaDoc路径
     * @param sourcePath 源码路径
     * @param bugReportPath Bug报告输出路径
     */
    public static void verifyBugsFromLog(String logPath, String javadocPath, String sourcePath, String bugReportPath) {
        LoggerUtil.logExec(Level.INFO, "开始从日志文件验证bug: " + logPath);
        
        // 创建BugVerifyAgent
        BugVerifyAgent agent = new BugVerifyAgent(javadocPath, sourcePath, bugReportPath);
        
        try {
            // 读取日志文件
            File logFile = new File(logPath);
            if (!logFile.exists()) {
                LoggerUtil.logExec(Level.WARNING, "日志文件不存在: " + logPath);
                return;
            }
            
            List<String> lines = Files.readAllLines(logFile.toPath());
            Map<String, String> verifiedBugs = new HashMap<>();
            
            // 解析日志文件，提取已验证的bug
            for (int i = 0; i < lines.size() - 1; i++) {
                String line = lines.get(i);
                if (line.contains("VERIFIED_BUG")) {
                    // 提取文件路径
                    int pathStart = line.indexOf(": ") + 2;
                    int pathEnd = line.lastIndexOf(" VERIFIED_BUG");
                    
                    if (pathEnd > pathStart) {
                        String filePath = line.substring(pathStart, pathEnd);
                        filePath = filePath.replace("jdk17u-dev/test", "test"); // 实际的测试用例放在test目录下
                        
                        // 检查下一行是否包含bug详细信息
                        if (i + 2 < lines.size()) {
                            String nextLine = lines.get(i + 2);
                            if (nextLine.contains("{") && nextLine.contains("}")) {
                                String verifyMessage = nextLine.substring(nextLine.indexOf("{"));
                                verifiedBugs.put(filePath, verifyMessage);
                                LoggerUtil.logExec(Level.INFO, "找到已验证的bug: " + filePath);
                            }
                        }
                        i += 2;
                    }
                }
            }
            
            LoggerUtil.logExec(Level.INFO, "从日志中找到 " + verifiedBugs.size() + " 个已验证的bug");
            
            // 为每个bug生成报告
            for (Map.Entry<String, String> entry : verifiedBugs.entrySet()) {
                String filePath = entry.getKey();
                String verifyMessage = entry.getValue();
                
                // 从JDK路径转换到测试路径
                File originFile = new File(filePath);
                File testFile = new File(filePath.replace("jdk17u-dev/test", "test"));
                
                if (!testFile.exists() && !originFile.exists()) {
                    LoggerUtil.logExec(Level.WARNING, "测试文件不存在: " + filePath);
                    continue;
                }
                
                // 使用存在的文件
                File fileToUse = testFile.exists() ? testFile : originFile;
                
                String testCaseName = fileToUse.getName().replace(".java", "");
                LoggerUtil.logExec(Level.INFO, "正在分析bug: " + testCaseName);
                
                try {
                    // 读取测试用例内容
                    String testContent = Files.readString(fileToUse.toPath());
                    
                    // 运行测试获取输出
                    JtregExecuteTool jtregTool = new JtregExecuteTool();
                    ToolResponse<TestResult> response = jtregTool.execute(fileToUse.getPath());
                    
                    String testOutput = "";
                    if (response.isSuccess()) {
                        testOutput = response.getResult().getOutput();
                    } else {
                        // 如果执行失败，尝试从result文件获取输出
                        File resultFile = new File(fileToUse.getPath() + ".result");
                        if (resultFile.exists()) {
                            testOutput = Files.readString(resultFile.toPath());
                        } else {
                            // 使用verifyMessage作为备选
                            testOutput = "无法获取测试输出，使用验证消息作为替代: " + verifyMessage;
                        }
                    }
                    
                    // 设置测试数据并分析
                    agent.setTestData(testContent, testOutput, verifyMessage);
                    String report = agent.analyze();
                    
                    LoggerUtil.logExec(Level.INFO, "Bug报告已生成: " + testCaseName);
                } catch (Exception e) {
                    LoggerUtil.logExec(Level.WARNING, "为测试用例生成报告失败: " + testCaseName);
                    e.printStackTrace();
                }
            }
            
            LoggerUtil.logExec(Level.INFO, "Bug验证和报告生成完成，报告保存在: " + bugReportPath);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "Bug验证过程失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            agent.close();
        }
    }
    
    /**
     * 无参数重载，使用默认路径
     */
    public static void verifyBugsFromLog() {
        verifyBugsFromLog("result.log", "JavaDoc/docs/api/java.base", "jdk17u-dev/src", "BugReport");
    }
    
    public void close(){
        webTool.close();
    }

    /**
     * 从JSON中提取对象数组
     */
    public static List<String> extractJsonObjectArrayFromField(String json, String fieldName) {
        // 先过滤掉思维链标签
        json = filterThinkingChain(json);
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }

        LoggerUtil.logExec(Level.INFO,"Attempting to extract JSON object array for field: " + fieldName + "\nInput JSON preview: " + (json.length() > 100 ? json.substring(0, 100) + "..." : json));
        List<String> result = new ArrayList<>();
        try {
            // ObjectMapper 用于解析最终的 JSON
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(json); // 直接解析输入的json字符串
            JsonNode arrayNode = rootNode.path(fieldName);

            if (arrayNode.isArray()) {
                for (JsonNode node : arrayNode) {
                    // 将每个对象节点转换为其字符串表示形式
                    result.add(node.toString().trim());
                }
                LoggerUtil.logExec(Level.INFO, "Successfully extracted " + result.size() + " objects from array field '" + fieldName + "'.");
            } else {
                 LoggerUtil.logExec(Level.WARNING, "Field '" + fieldName + "' is not an array or was not found in the JSON.");
            }
        } catch (IOException e) { // Changed Exception to IOException for specificity
            LoggerUtil.logExec(Level.WARNING, "Failed to parse JSON or extract object array field '" + fieldName + "': " + e.getMessage());
            // 可以在这里添加调用 string2json 的逻辑作为后备，但这会改变方法预期
            // 例如:
            // String cleanedJson = new BugVerifyAgent(null, null).string2json(json); // 需要处理构造函数
            // if (cleanedJson != null) { /* retry parsing cleanedJson */ }
        }
        LoggerUtil.logExec(Level.FINE,"Resulting object strings count: " + result.size()); // Changed level to FINE for less verbose default logging
        return result;
    }
}

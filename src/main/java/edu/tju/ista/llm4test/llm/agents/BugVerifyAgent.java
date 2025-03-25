package edu.tju.ista.llm4test.llm.agents;

import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.*;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final DuckDuckGoSearcher searchTool;
    private final JavaExecuteTool executeTool;
    private final JtregExecuteTool jtregTool;
    
    // LLM实例
    private final OpenAI llm;
    
    // 分析状态
    private String testCase;
    private String testOutput;
    private String initialAnalysis;
    private Map<String, Object> collectedInfo = new HashMap<>();
    private List<String> hypotheses = new ArrayList<>();
    private Map<String, TestResult> verificationResults = new HashMap<>();
    private String conclusion;
    
    /**
     * 创建BugVerifyAgent
     * @param javadocPath JavaDoc路径
     * @param sourcePath 源码路径
     */
    public BugVerifyAgent(String javadocPath, String sourcePath) {
        this.llm = OpenAI.R1;
        this.javadocTool = new JavaDocSearchTool(javadocPath);
        this.sourceTool = new SourceCodeSearchTool(sourcePath);
        this.webTool = new WebContentExtractor(true);
        this.searchTool = new DuckDuckGoSearcher();
        this.executeTool = new JavaExecuteTool();
        this.jtregTool = new JtregExecuteTool();
    }
    
    /**
     * 设置测试用例和输出
     */
    public void setTestData(String testCase, String testOutput, String initialAnalysis) {
        this.testCase = testCase;
        this.testOutput = testOutput;
        this.initialAnalysis = initialAnalysis;
    }
    
    /**
     * 执行完整的Bug验证流程
     */
    public String analyze() {
        LoggerUtil.logExec(Level.INFO, "开始Bug验证流程");
        
        // 1. 初始分析
        String initialInsight = performInitialAnalysis();
        LoggerUtil.logExec(Level.INFO, "初始分析完成：" + initialInsight);
        
        // 2. 收集信息
        collectRelevantInformation(initialInsight);
        LoggerUtil.logExec(Level.INFO, "信息收集完成，共 " + collectedInfo.size() + " 项");
        
        // 3. 形成假设
        formHypotheses();
        LoggerUtil.logExec(Level.INFO, "形成 " + hypotheses.size() + " 个假设");
        
        // 4. 验证假设
        verifyHypotheses();
        LoggerUtil.logExec(Level.INFO, "验证完成，结果数: " + verificationResults.size());
        
        // 5. 形成结论和报告
        String report = generateReport();
        LoggerUtil.logExec(Level.INFO, "Bug验证报告已生成");
        
        return report;
    }
    
    /**
     * 过滤LLM响应中的思维链标签
     * @param response LLM响应内容
     * @return 过滤后的内容
     */
    private String filterThinkingChain(String response) {
        if (response == null) return null;
        
        // 移除<think>...</think>标签及其内容
        String filtered = response.replaceAll("<think>[\\s\\S]*?</think>", "");
        
        // 移除可能的残留标签（如果标签不配对）
        filtered = filtered.replaceAll("<think>[\\s\\S]*", "");
        filtered = filtered.replaceAll("[\\s\\S]*</think>", "");
        
        // 返回清理后的响应
        return filtered.trim();
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
                """.formatted(testCase, testOutput, initialAnalysis != null ? initialAnalysis : "无初步分析");
        
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
                collectedInfo.put("javadoc_" + className, docResponse.getResult());
            }
        }
        
        // 收集源码信息
        for (String className : relevantClasses) {
            ToolResponse<String> sourceResponse = sourceTool.execute(className);
            if (sourceResponse.isSuccess()) {
                collectedInfo.put("source_" + className, sourceResponse.getResult());
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
                            collectedInfo.put("web_" + query + "_" + count, contentResponse.getResult());
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
     * 假设形成阶段 - 基于收集到的信息形成假设
     */
    private void formHypotheses() {
        // 构建提示，包含所有收集到的信息
        StringBuilder infoBuilder = new StringBuilder();
        for (Map.Entry<String, Object> entry : collectedInfo.entrySet()) {
            if (entry.getValue() instanceof String) {
                String content = (String) entry.getValue();
                // 限制每项内容的长度，避免提示过长
                if (content.length() > 2000) {
                    content = content.substring(0, 2000) + "...(内容已截断)";
                }
                infoBuilder.append("<").append(entry.getKey()).append(">\n");
                infoBuilder.append(content).append("\n");
                infoBuilder.append("</").append(entry.getKey()).append(">\n\n");
            }
        }
        
        String prompt = """
                你是一位Java Bug分析专家。基于测试用例、测试输出和收集到的信息，形成关于Bug原因的假设。
                
                <TestCase>
                %s
                </TestCase>
                
                <Test Output>
                %s
                </Test Output>
                
                <Collected Information>
                %s
                </Collected Information>
                
                请形成3-5个可能的假设，解释这个Bug的原因。每个假设应该具体明确，并且可以通过修改代码来验证。
                
                输出格式:
                {
                  "hypotheses": [
                    {
                      "id": "H1",
                      "description": "假设描述",
                      "rationale": "为什么这是合理的假设",
                      "verificationCode": "可用于验证这个假设的Java测试代码"
                    },
                    ...
                  ]
                }
                """.formatted(testCase, testOutput, infoBuilder.toString());
        
        String response = llm.messageCompletion(prompt);
        hypotheses = extractJsonObjectArrayFromField(response, "hypotheses");
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
        
        String prompt = """
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
                
                请生成一份完整的Bug报告，包括:
                1. Bug概述
                2. 复现步骤
                3. 根本原因分析
                4. 建议修复方案
                5. 相关API或类的使用说明
                
                请使用Markdown格式输出报告。
                """.formatted(testCase, testOutput, hypothesesBuilder.toString(), resultsBuilder.toString());
        
        conclusion = llm.messageCompletion(prompt);
        return conclusion;
    }
    
    /**
     * 从JSON字符串中提取字段值
     */
    private String extractJsonFieldValue(String json, String fieldName) {
        // 先过滤掉思维链标签
        json = filterThinkingChain(json);
        
        try {
            Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"");
            Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return matcher.group(1);
            }
            
            // 尝试匹配非字符串类型值（如数字、布尔值）
            pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*([^,\\}\\]]+)");
            matcher = pattern.matcher(json);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "JSON字段提取失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 从JSON中提取数组
     */
    private List<String> extractJsonArrayFromField(String json, String fieldName) {
        // 先过滤掉思维链标签
        json = filterThinkingChain(json);
        
        List<String> result = new ArrayList<>();
        try {
            Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                String arrayContent = matcher.group(1);
                Pattern itemPattern = Pattern.compile("\"(.*?)\"");
                Matcher itemMatcher = itemPattern.matcher(arrayContent);
                while (itemMatcher.find()) {
                    result.add(itemMatcher.group(1));
                }
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "JSON数组提取失败: " + e.getMessage());
        }
        return result;
    }
    
    /**
     * 从JSON中提取对象数组
     */
    private List<String> extractJsonObjectArrayFromField(String json, String fieldName) {
        // 先过滤掉思维链标签
        json = filterThinkingChain(json);
        
        List<String> result = new ArrayList<>();
        try {
            Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                String arrayContent = matcher.group(1);
                // 分割对象
                int depth = 0;
                StringBuilder currentObject = new StringBuilder();
                for (char c : arrayContent.toCharArray()) {
                    if (c == '{') depth++;
                    if (c == '}') depth--;
                    
                    currentObject.append(c);
                    
                    if (depth == 0 && currentObject.length() > 0) {
                        if (currentObject.toString().trim().length() > 2) { // 不是空对象
                            result.add(currentObject.toString().trim());
                        }
                        currentObject = new StringBuilder();
                    }
                }
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "JSON对象数组提取失败: " + e.getMessage());
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
     * 执行Bug验证的主方法
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java BugVerifyAgent <javadoc_path> <source_path> <test_file>");
            return;
        }
        
        String javadocPath = args[0];
        String sourcePath = args[1];
        
        // 创建Agent
        BugVerifyAgent agent = new BugVerifyAgent(javadocPath, sourcePath);
        
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
        }
    }
}

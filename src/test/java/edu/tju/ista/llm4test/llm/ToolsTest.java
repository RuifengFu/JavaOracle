package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.execute.TestResult;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import edu.tju.ista.llm4test.llm.tools.*;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ToolsTest {
    private JtregExecuteTool jtregTool;
    private JavaDocSearchTool javadocTool;
    private SourceCodeSearchTool sourceTool;
    
    @Before
    public void setUp() {
        // 初始化工具实例
        jtregTool = new JtregExecuteTool();
        javadocTool = new JavaDocSearchTool("JavaDoc/docs/api/java.base");
        sourceTool = new SourceCodeSearchTool("jdk17u-dev/src");
    }
    
    // ==== JtregExecuteTool 测试 ====
    
    @Test
    public void testJtregExecuteWithSimpleTest() {
        // 创建一个简单的测试源代码
        String testSource = 
                "/**\n" +
                " * @test\n" +
                " */\n" +
                "public class SimpleTest {\n" +
                "    public static void main(String[] args) {\n" +
                "        System.out.println(\"Hello from SimpleTest\");\n" +
                "        // 简单的断言\n" +
                "        if (2 + 2 == 4) {\n" +
                "            System.out.println(\"Test passed\");\n" +
                "        } else {\n" +
                "            throw new RuntimeException(\"Test failed\");\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        
        // 执行测试
        ToolResponse<TestResult> response = jtregTool.execute(testSource);
        
        // 打印结果供检查
        System.out.println("=== JtregExecuteTool 简单测试结果 ===");
        System.out.println("响应成功: " + response.isSuccess());
        
        if (response.isSuccess()) {
            TestResult result = response.getResult();
            System.out.println("测试通过: " + result.isSuccess());
            System.out.println("测试输出:\n" + result.getOutput());
        } else {
            System.out.println("错误信息: " + response.getMessage());
        }
    }
    
    @Test
    public void testJtregExecuteWithFailingTest() {
        // 创建一个会失败的测试源代码
        String testSource = 
                "/**\n" +
                " * @test\n" +
                " */\n" +
                "public class FailingTest {\n" +
                "    public static void main(String[] args) {\n" +
                "        System.out.println(\"This test will fail\");\n" +
                "        throw new RuntimeException(\"Intentional test failure\");\n" +
                "    }\n" +
                "}\n";
        
        // 执行测试
        ToolResponse<TestResult> response = jtregTool.execute(testSource);
        
        // 打印结果供检查
        System.out.println("\n=== JtregExecuteTool 失败测试结果 ===");
        System.out.println("响应成功: " + response.isSuccess());
        
        if (response.isSuccess()) {
            TestResult result = response.getResult();
            System.out.println("测试通过: " + result.isSuccess() + " (预期为false)");
            System.out.println("测试输出摘要:\n" + truncateOutput(result.getOutput(), 200));
        } else {
            System.out.println("错误信息: " + response.getMessage());
        }
    }
    
    // ==== JavaDocSearchTool 测试 ====
    
    @Test
    public void testJavaDocSearchForString() {
        // 搜索String类的文档
        String query = "String类的substring方法";
        
        // 执行搜索
        ToolResponse<String> response = javadocTool.execute(query);
        
        // 打印结果供检查
        System.out.println("\n=== JavaDocSearchTool String测试结果 ===");
        System.out.println("响应成功: " + response.isSuccess());
        
        if (response.isSuccess()) {
            System.out.println("搜索结果摘要:\n" + truncateOutput(response.getResult(), 300));
        } else {
            System.out.println("错误信息: " + response.getMessage());
        }
    }
    
    @Test
    public void testJavaDocSearchForCollections() {
        // 搜索集合相关文档
        String query = "java.util.ArrayList类";
        
        // 执行搜索
        ToolResponse<String> response = javadocTool.execute(query);
        
        // 打印结果供检查
        System.out.println("\n=== JavaDocSearchTool ArrayList测试结果 ===");
        System.out.println("响应成功: " + response.isSuccess());
        
        if (response.isSuccess()) {
            System.out.println("搜索结果摘要:\n" + truncateOutput(response.getResult(), 300));
        } else {
            System.out.println("错误信息: " + response.getMessage());
        }
    }
    
    // ==== SourceCodeSearchTool 测试 ====
    
    @Test
    public void testSourceCodeSearchForArrayList() {
        // 搜索ArrayList实现
        String query = "java.util.ArrayList的实现";
        
        // 执行搜索
        ToolResponse<String> response = sourceTool.execute(query);
        
        // 打印结果供检查
        System.out.println("\n=== SourceCodeSearchTool ArrayList测试结果 ===");
        System.out.println("响应成功: " + response.isSuccess());
        
        if (response.isSuccess()) {
            System.out.println("搜索结果摘要:\n" + truncateOutput(response.getResult(), 300));
        } else {
            System.out.println("错误信息: " + response.getMessage());
        }
    }
    
    @Test
    public void testSourceCodeSearchForHashMapMethod() {
        // 搜索HashMap的put方法实现
        String query = "java.util.HashMap的put方法实现";
        
        // 执行搜索
        ToolResponse<String> response = sourceTool.execute(query);
        
        // 打印结果供检查
        System.out.println("\n=== SourceCodeSearchTool HashMap.put测试结果 ===");
        System.out.println("响应成功: " + response.isSuccess());
        
        if (response.isSuccess()) {
            System.out.println("搜索结果摘要:\n" + response.getResult());
        } else {
            System.out.println("错误信息: " + response.getMessage());
        }
    }
    
    // ==== 辅助方法 ====
    
    /**
     * 截断长输出文本
     */
    private String truncateOutput(String output, int maxLength) {
        if (output == null) return "null";
        if (output.length() <= maxLength) return output;
        return output.substring(0, maxLength) + "...(更多内容省略)";
    }


        // ... existing code ...
    
    // ==== 日志分析测试 ====
    
    @Test
    public void testExtractVerifiedBugsFromLog() {
        // 定义测试用的log文件路径（可以使用一个测试文件或实际的result.log）
        File resultLog = new File("result.log");
        
        if (!resultLog.exists()) {
            System.out.println("找不到result.log文件，跳过测试");
            return;
        }
        
        try {
            // 解析log文件
            List<String> lines = Files.readAllLines(resultLog.toPath());
            Map<String, String> verifiedBugs = new HashMap<>();
            
            System.out.println("\n=== 日志分析结果 ===");
            System.out.println("日志文件总行数: " + lines.size());
            
            for (int i = 0; i < lines.size() - 1; i++) {
                String line = lines.get(i);
                // 查找包含 VERIFIED_BUG 的行
                if (line.contains("VERIFIED_BUG")) {
                    // 提取文件路径 - 格式: INFO: jdk17u-dev/test/jdk/java/util/xxx/Test.java VERIFIED_BUG
                    System.out.println(line);
                    int pathStart = line.lastIndexOf("INFO: ") + 6;
                    int pathEnd = line.lastIndexOf(" VERIFIED_BUG");
                    if (pathStart >= 6 && pathEnd > pathStart) {
                        String filePath = line.substring(pathStart, pathEnd);
                        filePath = filePath.replace("jdk17u-dev/", ""); // 去除jdk17u-dev前缀
                        
                        // 检查下一行是否包含bug详细信息
                        String nextLine = lines.get(i + 2);
                        if (nextLine.contains("{") && nextLine.contains("}")) {
                            // 提取JSON信息 (去除日志前缀)
                            int jsonStart = nextLine.indexOf("{");
                            String verifyMessage = nextLine.substring(jsonStart);
                            
                            verifiedBugs.put(filePath, verifyMessage);
                        }
                    }
                }
            }
            
            // 输出解析结果
            System.out.println("发现的VERIFIED_BUG数量: " + verifiedBugs.size());
            
            // 打印前5个bug的信息
            int count = 0;
            for (Map.Entry<String, String> entry : verifiedBugs.entrySet()) {
                if (count++ >= 5) break;
                
                System.out.println("\n--- Bug #" + count + " ---");
                System.out.println("文件路径: " + entry.getKey());
                
                // 解析JSON以提取关键信息
                String json = entry.getValue();
                System.out.println("原始JSON: " + truncateOutput(json, 1000));
                try {
                    // 简单提取关键字段
                    String bugType = extractJsonField(json, "bug_type");
                    String location = extractJsonField(json, "bug_location");
                    String rootCause = extractJsonField(json, "root_cause");
                    
                    System.out.println("Bug类型: " + bugType);
                    System.out.println("Bug位置: " + location);
                    System.out.println("根本原因: " + rootCause);
                } catch (Exception e) {
                    System.out.println("JSON解析失败: " + e.getMessage());
                    System.out.println("原始JSON: " + truncateOutput(json, 100));
                }
            }
            
            if (verifiedBugs.size() > 5) {
                System.out.println("\n... 还有 " + (verifiedBugs.size() - 5) + " 个bug未显示 ...");
            }
            
        } catch (Exception e) {
            System.out.println("分析日志文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 从JSON字符串中提取字段值
     */
    private String extractJsonField(String json, String fieldName) {
        Pattern pattern = Pattern.compile(fieldName + "=(.*?)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "未找到";
    }
    
    // ... existing code ...
}
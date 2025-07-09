package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.llm.tools.SimplifiedJavaDocSearchTool;
import edu.tju.ista.llm4test.llm.tools.SimplifiedSourceCodeSearchTool;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;

public class SimplifiedToolsTest {
    
    private SimplifiedSourceCodeSearchTool sourceCodeTool;
    private SimplifiedJavaDocSearchTool javaDocTool;
    
    @BeforeEach
    public void setUp() {
        // 使用测试路径
        sourceCodeTool = new SimplifiedSourceCodeSearchTool("jdk17u-dev/src");
        javaDocTool = new SimplifiedJavaDocSearchTool("JavaDoc/docs/api/java.base");
    }
    
    @Test
    public void testSourceCodeSearch() {
        System.out.println("=== 测试源码搜索工具 ===");
        
        // 测试1: 按类名搜索
        System.out.println("\n1. 按类名搜索 HashMap:");
        ToolResponse<String> response1 = sourceCodeTool.execute(Map.of(
            "search_type", "by_class",
            "class_name", "java.util.HashMap"
        ));
        System.out.println("结果: " + response1.isSuccess());
        if (!response1.isSuccess()) {
            System.out.println("错误: " + response1.getFailMessage());
        }
        
        // 测试2: 关键词搜索
        System.out.println("\n2. 关键词搜索 ConcurrentModificationException:");
        ToolResponse<String> response2 = sourceCodeTool.execute(Map.of(
            "search_type", "by_keyword",
            "keyword", "ConcurrentModificationException"
        ));
        System.out.println("结果: " + response2.isSuccess());
        if (response2.isSuccess()) {
            System.out.println("找到匹配文件数量: " + countMatches(response2.getResult(), "###"));
        } else {
            System.out.println("错误: " + response2.getFailMessage());
        }
        
        // 测试3: 方法搜索
        System.out.println("\n3. 在HashMap中搜索put方法:");
        ToolResponse<String> response3 = sourceCodeTool.execute(Map.of(
            "search_type", "by_method",
            "class_name", "java.util.HashMap",
            "method_name", "put"
        ));
        System.out.println("结果: " + response3.isSuccess());
        if (!response3.isSuccess()) {
            System.out.println("错误: " + response3.getFailMessage());
        }
        
        // 测试4: 相关类搜索
        System.out.println("\n4. 查找HashMap相关类:");
        ToolResponse<String> response4 = sourceCodeTool.execute(Map.of(
            "search_type", "related_classes",
            "class_name", "java.util.HashMap"
        ));
        System.out.println("结果: " + response4.isSuccess());
        if (response4.isSuccess()) {
            System.out.println("找到相关类数量: " + countMatches(response4.getResult(), "- **"));
        } else {
            System.out.println("错误: " + response4.getFailMessage());
        }
    }
    
    @Test
    public void testJavaDocSearch() {
        System.out.println("\n=== 测试JavaDoc搜索工具 ===");
        
        // 测试1: 按类名搜索
        System.out.println("\n1. 按类名搜索 HashMap:");
        ToolResponse<String> response1 = javaDocTool.execute(Map.of(
            "search_type", "by_class",
            "class_name", "java.util.HashMap"
        ));
        System.out.println("结果: " + response1.isSuccess());
        if (!response1.isSuccess()) {
            System.out.println("错误: " + response1.getFailMessage());
        }
        
        // 测试2: 按路径搜索
        System.out.println("\n2. 按路径搜索 java/util/HashMap.html:");
        ToolResponse<String> response2 = javaDocTool.execute(Map.of(
            "search_type", "by_path",
            "doc_path", "java/util/HashMap.html"
        ));
        System.out.println("结果: " + response2.isSuccess());
        if (!response2.isSuccess()) {
            System.out.println("错误: " + response2.getFailMessage());
        }
        
        // 测试3: 列出包中的类
        System.out.println("\n3. 列出java.util包中的类:");
        ToolResponse<String> response3 = javaDocTool.execute(Map.of(
            "search_type", "by_package",
            "package_name", "java.util"
        ));
        System.out.println("结果: " + response3.isSuccess());
        if (response3.isSuccess()) {
            System.out.println("找到类数量: " + countMatches(response3.getResult(), "###"));
        } else {
            System.out.println("错误: " + response3.getFailMessage());
        }
        
        // 测试4: 列出文件结构
        System.out.println("\n4. 列出根目录文件结构:");
        ToolResponse<String> response4 = javaDocTool.execute(Map.of(
            "search_type", "list_files"
        ));
        System.out.println("结果: " + response4.isSuccess());
        if (response4.isSuccess()) {
            System.out.println("找到目录/文件数量: " + countMatches(response4.getResult(), "- **"));
        } else {
            System.out.println("错误: " + response4.getFailMessage());
        }
    }
    
    @Test
    public void testPathResolution() {
        System.out.println("\n=== 测试路径解析改进 ===");
        
        // 测试源码工具的JDK模块路径解析
        System.out.println("\n1. 测试JDK模块路径解析 (源码):");
        ToolResponse<String> sourceResponse = sourceCodeTool.execute(Map.of(
            "search_type", "by_class",
            "class_name", "java.util.concurrent.ConcurrentHashMap"
        ));
        System.out.println("ConcurrentHashMap搜索结果: " + sourceResponse.isSuccess());
        
        // 测试JavaDoc工具的模块路径解析
        System.out.println("\n2. 测试JDK模块路径解析 (JavaDoc):");
        ToolResponse<String> docResponse = javaDocTool.execute(Map.of(
            "search_type", "by_class",
            "class_name", "java.util.concurrent.ConcurrentHashMap"
        ));
        System.out.println("ConcurrentHashMap文档搜索结果: " + docResponse.isSuccess());
        
        // 测试简化路径访问
        System.out.println("\n3. 测试简化路径访问:");
        ToolResponse<String> pathResponse = javaDocTool.execute(Map.of(
            "search_type", "by_path",
            "doc_path", "HashMap.html"
        ));
        System.out.println("简化路径访问结果: " + pathResponse.isSuccess());
    }
    
    private int countMatches(String text, String pattern) {
        if (text == null || pattern == null) return 0;
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }
} 
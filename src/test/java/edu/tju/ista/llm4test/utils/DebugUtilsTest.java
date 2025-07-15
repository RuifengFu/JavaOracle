package edu.tju.ista.llm4test.utils;

import org.jetbrains.annotations.Debug;

import java.io.File;

/**
 * DebugUtils测试类
 */
public class DebugUtilsTest {
    
    public static void main(String[] args) {
        // 创建调试工具实例
        DebugUtils debugUtils = DebugUtils.getInstance();
        
        System.out.println("调试会话目录: " + debugUtils.getSessionDir());
        System.out.println("调试目录是否存在: " + debugUtils.exists());
        
        // 测试保存文件到不同部分
        debugUtils.saveToFile("A", "test1.txt", "这是部分A的测试内容1");
        debugUtils.saveToFile("A", "test2.txt", "这是部分A的测试内容2");
        debugUtils.saveToFile("B", "data.json", "{\"key\": \"value\", \"number\": 123}");
        debugUtils.saveToFile("C", "log.txt", "调试日志信息\n第二行内容");
        
        // 测试带时间戳的文件保存
        debugUtils.saveToFileWithTimestamp("A", "timestamped.txt", "带时间戳的文件内容");
        debugUtils.saveToFileWithTimestamp("B", "report.md", "# 调试报告\n\n这是一个测试报告。");
        
        // 显示各部分的文件
        System.out.println("\n部分A的文件:");
        File[] filesA = debugUtils.getPartFiles("A");
        for (File file : filesA) {
            System.out.println("  " + file.getName());
        }
        
        System.out.println("\n部分B的文件:");
        File[] filesB = debugUtils.getPartFiles("B");
        for (File file : filesB) {
            System.out.println("  " + file.getName());
        }
        
        System.out.println("\n部分C的文件:");
        File[] filesC = debugUtils.getPartFiles("C");
        for (File file : filesC) {
            System.out.println("  " + file.getName());
        }
        
        // 测试清空部分
        System.out.println("\n清空部分C...");
        debugUtils.clearPart("C");
        
        System.out.println("清空后部分C的文件:");
        File[] filesCAfterClear = debugUtils.getPartFiles("C");
        for (File file : filesCAfterClear) {
            System.out.println("  " + file.getName());
        }
        
        System.out.println("\n测试完成!");
    }
}
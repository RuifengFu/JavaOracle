package edu.tju.ista.llm4test.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件工具类
 * 提供文件操作相关的通用方法
 */
public class FileUtils {

    /**
     * 递归遍历目录，获取所有文件
     * 
     * @param dir 要遍历的目录
     * @return 所有文件的列表
     */
    public static List<File> traverseDirectory(File dir) {
        List<File> files = new ArrayList<>();
        
        if (dir == null || !dir.exists()) {
            return files;
        }
        
        if (dir.isDirectory()) {
            File[] listFiles = dir.listFiles();
            if (listFiles != null) {
                for (File file : listFiles) {
                    if (file.isDirectory()) {
                        files.addAll(traverseDirectory(file));
                    } else if (file.isFile()) {
                        files.add(file);
                    }
                }
            }
        } else if (dir.isFile()) {
            files.add(dir);
        }
        
        return files;
    }
    
    /**
     * 获取指定目录下所有Java文件
     * 
     * @param dir 目录
     * @return Java文件列表
     */
    public static List<File> getJavaFiles(File dir) {
        return traverseDirectory(dir).stream()
                .filter(file -> file.getName().endsWith(".java"))
                .toList();
    }
    
    /**
     * 检查文件是否存在且可读
     * 
     * @param file 文件
     * @return 是否存在且可读
     */
    public static boolean isFileReadable(File file) {
        return file != null && file.exists() && file.isFile() && file.canRead();
    }
    
    /**
     * 安全地获取文件名（不包含扩展名）
     * 
     * @param file 文件
     * @return 文件名（不包含扩展名）
     */
    public static String getFileNameWithoutExtension(File file) {
        if (file == null) {
            return "";
        }
        
        String fileName = file.getName();
        int lastDotIndex = fileName.lastIndexOf('.');
        
        return lastDotIndex > 0 ? fileName.substring(0, lastDotIndex) : fileName;
    }
    
    /**
     * 获取文件扩展名
     * 
     * @param file 文件
     * @return 扩展名（不包含点）
     */
    public static String getFileExtension(File file) {
        if (file == null) {
            return "";
        }
        
        String fileName = file.getName();
        int lastDotIndex = fileName.lastIndexOf('.');
        
        return lastDotIndex > 0 && lastDotIndex < fileName.length() - 1 
                ? fileName.substring(lastDotIndex + 1) 
                : "";
    }
} 
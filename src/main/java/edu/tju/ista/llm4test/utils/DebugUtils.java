package edu.tju.ista.llm4test.utils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

/**
 * 调试工具类
 * 提供创建调试目录和保存调试信息的功能
 */
public class DebugUtils {
    
    private static final String DEBUG_DIR_NAME = "debug";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    
    private static volatile DebugUtils instance;
    private static final Object lock = new Object();
    
    private final String baseDir;
    private final String sessionDir;
    
    /**
     * 获取DebugUtils的单例实例
     * 
     * @return DebugUtils实例
     */
    public static DebugUtils getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new DebugUtils();
                }
            }
        }
        return instance;
    }
    
    /**
     * 获取DebugUtils的单例实例，使用指定的基础目录
     * 
     * @param baseDir 基础目录
     * @return DebugUtils实例
     */
    public static DebugUtils getInstance(String baseDir) {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new DebugUtils(baseDir);
                }
            }
        }
        return instance;
    }
    
    /**
     * 构造函数，创建基于当前时间的调试会话目录
     */
    private DebugUtils() {
        this(System.getProperty("user.dir"));
    }
    
    /**
     * 构造函数，在指定目录下创建调试会话目录
     * 
     * @param baseDir 基础目录
     */
    private DebugUtils(String baseDir) {
        this.baseDir = baseDir;
        String timestamp = LocalDateTime.now().format(TIME_FORMAT);
        this.sessionDir = PathUtils.combinePaths(baseDir, DEBUG_DIR_NAME + "/" + timestamp);
        createDebugDirectory();
    }
    
    /**
     * 创建调试目录结构
     */
    private synchronized void createDebugDirectory() {
        File debugDir = new File(sessionDir);
        if (!debugDir.exists()) {
            if (debugDir.mkdirs()) {
                LoggerUtil.logExec(Level.INFO, "创建调试目录: " + sessionDir);
            } else {
                LoggerUtil.logExec(Level.WARNING, "创建调试目录失败: " + sessionDir);
            }
        }
    }
    
    /**
     * 在指定部分创建子目录
     * 
     * @param part 部分名称（如A、B、C等）
     * @return 创建的部分目录路径
     */
    public synchronized String createPart(String part) {
        String partDir = PathUtils.combinePaths(sessionDir, part);
        File dir = new File(partDir);
        if (!dir.exists()) {
            if (dir.mkdirs()) {
                LoggerUtil.logExec(Level.INFO, "创建部分目录: " + partDir);
            } else {
                LoggerUtil.logExec(Level.WARNING, "创建部分目录失败: " + partDir);
            }
        }
        return partDir;
    }
    
    /**
     * 保存字符串到指定部分的文件中
     * 
     * @param part 部分名称
     * @param fileName 文件名
     * @param content 文件内容
     */
    public synchronized void saveToFile(String part, String fileName, String content) {
        String partDir = createPart(part);
        String filePath = PathUtils.combinePaths(partDir, fileName);
        
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
            LoggerUtil.logExec(Level.INFO, "保存调试文件: " + filePath);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.WARNING, "保存调试文件失败 " + filePath + ": " + e.getMessage());
        }
    }
    
    /**
     * 保存字符串到指定部分的文件中（自动添加时间戳到文件名）
     * 
     * @param part 部分名称
     * @param fileName 文件名
     * @param content 文件内容
     */
    public void saveToFileWithTimestamp(String part, String fileName, String content) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH-mm-ss"));
        String threadId = String.valueOf(Thread.currentThread().threadId());
        String extension = FileUtils.getFileExtension(new File(fileName));
        String nameWithoutExt = FileUtils.getFileNameWithoutExtension(new File(fileName));
        nameWithoutExt += "_" + timestamp + "_" + threadId;
        String timestampedFileName = extension.isEmpty() 
            ? nameWithoutExt
            : nameWithoutExt + "." + extension;
            
        saveToFile(part, timestampedFileName, content);
    }
    
    /**
     * 获取当前调试会话目录
     * 
     * @return 调试会话目录路径
     */
    public String getSessionDir() {
        return sessionDir;
    }
    
    /**
     * 获取指定部分的目录路径
     * 
     * @param part 部分名称
     * @return 部分目录路径
     */
    public String getPartDir(String part) {
        return PathUtils.combinePaths(sessionDir, part);
    }
    
    /**
     * 检查调试目录是否存在
     * 
     * @return 是否存在
     */
    public boolean exists() {
        return new File(sessionDir).exists();
    }
    
    /**
     * 清空指定部分的所有文件
     * 
     * @param part 部分名称
     */
    public synchronized void clearPart(String part) {
        String partDir = getPartDir(part);
        File dir = new File(partDir);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        if (file.delete()) {
                            LoggerUtil.logExec(Level.INFO, "删除调试文件: " + file.getPath());
                        } else {
                            LoggerUtil.logExec(Level.WARNING, "删除调试文件失败: " + file.getPath());
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 获取指定部分中的所有文件
     * 
     * @param part 部分名称
     * @return 文件列表
     */
    public synchronized File[] getPartFiles(String part) {
        String partDir = getPartDir(part);
        File dir = new File(partDir);
        if (dir.exists() && dir.isDirectory()) {
            return dir.listFiles(File::isFile);
        }
        return new File[0];
    }
}
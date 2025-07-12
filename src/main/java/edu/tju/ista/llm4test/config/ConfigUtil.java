package edu.tju.ista.llm4test.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * 配置工具类
 * 负责加载和读取配置文件
 */
public class ConfigUtil {
    private static final Properties properties = new Properties();

    static {
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            properties.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load configuration file.");
        }
    }

    /**
     * 获取配置值
     * 
     * @param key 配置键
     * @return 配置值，如果不存在返回null
     */
    public static String get(String key) {
        return properties.getProperty(key);
    }
    
    /**
     * 获取配置值，如果不存在则返回默认值
     * 
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public static String getOrDefault(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
    

    /**
     * 获取整数配置值
     * 
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 整数配置值或默认值
     */
    public static int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("警告: 配置项 '" + key + "' 的值 '" + value + "' 不是有效的整数，使用默认值: " + defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * 获取长整数配置值
     * 
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 长整数配置值或默认值
     */
    public static long getLong(String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("警告: 配置项 '" + key + "' 的值 '" + value + "' 不是有效的长整数，使用默认值: " + defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * 获取布尔配置值
     * 
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 布尔配置值或默认值
     */
    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }
    
    /**
     * 获取浮点数配置值
     * 
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 浮点数配置值或默认值
     */
    public static double getDouble(String key, double defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("警告: 配置项 '" + key + "' 的值 '" + value + "' 不是有效的浮点数，使用默认值: " + defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * 检查配置项是否存在
     * 
     * @param key 配置键
     * @return 是否存在
     */
    public static boolean containsKey(String key) {
        return properties.containsKey(key);
    }
    
    /**
     * 获取所有配置属性
     * 
     * @return Properties对象
     */
    public static Properties getAllProperties() {
        return new Properties(properties);
    }
}
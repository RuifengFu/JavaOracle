package edu.tju.ista.llm4test.config;

import java.io.File;

/**
 * 应用程序配置管理类
 * 从config.properties文件读取所有配置项
 */
public class GlobalConfig {
    
    // 默认值常量（当配置文件中没有对应配置时使用）
    private static final String DEFAULT_TEST_DIR = "test";
    private static final String DEFAULT_BASE_DOC_PATH = "JavaDoc/docs/api/java.base";
    private static final String DEFAULT_JDK_TEST_PATH = "jdk17u-dev/test";
    private static final String DEFAULT_JDK_SOURCE_PATH = "jdk17u-dev/src";
    private static final String DEFAULT_BUG_REPORT_DIR = "BugReport";
    private static final String DEFAULT_LOG_FILE = "result.log";
    private static final String DEFAULT_SUITE_BASE_PATH = "jdk17u-dev/test/jdk/";
    private static final String[] DEFAULT_DEPENDENCY_JARS = {
        "Dependency/testng-7.10.2.jar",
        "Dependency/junit-jupiter-api-5.11.4.jar",
        "Dependency/junit-jupiter-engine-5.11.4.jar",
        "Dependency/junit-4.13.1.jar"
    };
    private static final long DEFAULT_MAX_FILE_SIZE = 10000;
    private static final int DEFAULT_THREAD_MULTIPLIER_GENERATE = 3;
    private static final int DEFAULT_THREAD_MULTIPLIER_EXECUTE = 2;
    private static final int DEFAULT_EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final String DEFAULT_TEMPLATE_MODE = "SpecTest";
    
    // JDK模块相关默认配置
    private static final String DEFAULT_JDK_ROOT_PATH = "jdk17u-dev";
    private static final String DEFAULT_JDK_BASE_SOURCE_PATH = "jdk17u-dev/src/java.base/share/classes";
    private static final String DEFAULT_JDK_XML_SOURCE_PATH = "jdk17u-dev/src/java.xml/share/classes";
    private static final String DEFAULT_JDK_DESKTOP_SOURCE_PATH = "jdk17u-dev/src/java.desktop/share/classes";
    private static final String DEFAULT_SOURCE_PREFIX = "java.base/share/classes";
    private static final String[] DEFAULT_JDK_PATHS = {
        "/home/Java/HotSpot/jdk-17.0.14+7",
        "/home/Java/HotSpot/jdk-21.0.6+7"
    };
    
    // 缓存相关默认配置
    private static final String DEFAULT_VALID_TEST_CASES_DIR = "ValidTestCases";
    private static final boolean DEFAULT_USE_CACHE_MODE = false;
    
    /**
     * 辅助方法：优先从配置文件获取，其次从环境变量获取
     * @param configKey 配置文件中的键
     * @param envKey 环境变量的键
     * @param defaultValue 默认值
     * @return 配置值
     */
    private static String getConfigThenEnv(String configKey, String envKey, String defaultValue) {
        String value = ConfigUtil.get(configKey);
        if (value != null && !value.trim().isEmpty()) {
            return value;
        }
        value = System.getenv(envKey);
        if (value != null && !value.trim().isEmpty()) {
            return value;
        }
        return defaultValue;
    }
    
    /**
     * 获取测试目录
     */
    public static String getTestDir() {
        return ConfigUtil.getOrDefault("testDir", DEFAULT_TEST_DIR);
    }
    
    /**
     * 获取基础文档路径
     */
    public static String getBaseDocPath() {
        return ConfigUtil.getOrDefault("baseDocPath", DEFAULT_BASE_DOC_PATH);
    }
    
    /**
     * 获取JDK测试路径
     */
    public static String getJdkTestPath() {
        return ConfigUtil.getOrDefault("jdkTestPath", DEFAULT_JDK_TEST_PATH);
    }
    
    /**
     * 获取JDK源码路径
     */
    public static String getJdkSourcePath() {
        return ConfigUtil.getOrDefault("jdkSourcePath", DEFAULT_JDK_SOURCE_PATH);
    }
    
    /**
     * 获取Bug报告目录
     */
    public static String getBugReportDir() {
        return ConfigUtil.getOrDefault("bugReportDir", DEFAULT_BUG_REPORT_DIR);
    }
    
    /**
     * 获取日志文件路径
     */
    public static String getLogFile() {
        return ConfigUtil.getOrDefault("logFile", DEFAULT_LOG_FILE);
    }
    
    /**
     * 获取测试套件基础路径
     */
    public static String getSuiteBasePath() {
        return ConfigUtil.getOrDefault("suiteBasePath", DEFAULT_SUITE_BASE_PATH);
    }
    
    /**
     * 获取依赖jar包数组
     */
    public static String[] getDependencyJars() {
        String jarsString = ConfigUtil.get("jars");
        if (jarsString != null && !jarsString.trim().isEmpty()) {
            return jarsString.split(",");
        }
        return DEFAULT_DEPENDENCY_JARS;
    }
    
    /**
     * 获取jar包路径字符串
     */
    public static String getJarPath() {
        return String.join(File.pathSeparator, getDependencyJars());
    }
    
    /**
     * 获取JDK路径列表
     */
    public static java.util.List<String> getJdkPaths() {
        String jdkPathsString = ConfigUtil.get("jdkPaths");
        if (jdkPathsString != null && !jdkPathsString.trim().isEmpty()) {
            return java.util.Arrays.asList(jdkPathsString.split(","));
        }
        return java.util.Arrays.asList(DEFAULT_JDK_PATHS);
    }
    
    /**
     * 获取最大文件大小限制
     */
    public static long getMaxFileSize() {
        return ConfigUtil.getLong("maxFileSize", DEFAULT_MAX_FILE_SIZE);
    }
    
    /**
     * 获取生成模式的线程倍数
     */
    public static int getThreadMultiplierGenerate() {
        return ConfigUtil.getInt("threadMultiplierGenerate", DEFAULT_THREAD_MULTIPLIER_GENERATE);
    }
    
    /**
     * 获取执行模式的线程倍数
     */
    public static int getThreadMultiplierExecute() {
        return ConfigUtil.getInt("threadMultiplierExecute", DEFAULT_THREAD_MULTIPLIER_EXECUTE);
    }
    
    /**
     * 获取执行器关闭超时时间（秒）
     */
    public static int getExecutorShutdownTimeoutSeconds() {
        return ConfigUtil.getInt("executorShutdownTimeoutSeconds", DEFAULT_EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS);
    }
    
    /**
     * 获取模板模式
     */
    public static String getTemplateMode() {
        return ConfigUtil.getOrDefault("templateMode", DEFAULT_TEMPLATE_MODE);
    }
    
    /**
     * 是否使用系统代理
     */
    public static boolean isUseSystemProxies() {
        return ConfigUtil.getBoolean("useSystemProxies", true);
    }
    
    /**
     * 确保目录存在
     */
    public static File ensureDirectoryExists(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
    
    /**
     * 获取JDK根路径
     */
    public static String getJdkRootPath() {
        return ConfigUtil.getOrDefault("jdkRootPath", DEFAULT_JDK_ROOT_PATH);
    }
    
    /**
     * 获取JDK base模块源码路径
     */
    public static String getJdkBaseSourcePath() {
        return ConfigUtil.getOrDefault("jdkBaseSourcePath", DEFAULT_JDK_BASE_SOURCE_PATH);
    }
    
    /**
     * 获取JDK xml模块源码路径
     */
    public static String getJdkXmlSourcePath() {
        return ConfigUtil.getOrDefault("jdkXmlSourcePath", DEFAULT_JDK_XML_SOURCE_PATH);
    }
    
    /**
     * 获取JDK desktop模块源码路径
     */
    public static String getJdkDesktopSourcePath() {
        return ConfigUtil.getOrDefault("jdkDesktopSourcePath", DEFAULT_JDK_DESKTOP_SOURCE_PATH);
    }
    
    /**
     * 获取默认源码前缀路径
     */
    public static String getDefaultSourcePrefix() {
        return ConfigUtil.getOrDefault("defaultSourcePrefix", DEFAULT_SOURCE_PREFIX);
    }
    
    /**
     * 获取有效测试用例缓存目录
     */
    public static String getValidTestCasesDir() {
        return ConfigUtil.getOrDefault("validTestCasesDir", DEFAULT_VALID_TEST_CASES_DIR);
    }
    
    /**
     * 根据rootPath获取对应的有效测试用例缓存文件路径
     * @param rootPath 测试套件根路径
     * @return 缓存文件路径
     */
    public static String getValidTestCasesPath(String rootPath) {
        String cacheDir = getValidTestCasesDir();
        ensureDirectoryExists(cacheDir);
        
        // 将rootPath转换为文件名安全的格式
        String fileName = rootPath.replace("/", "_").replace("\\", "_").replace(":", "_");
        if (fileName.isEmpty()) {
            fileName = "default";
        }
        return cacheDir + "/" + fileName + ".txt";
    }
    
    /**
     * 获取有效测试用例缓存文件路径（向后兼容的方法）
     * @deprecated 使用 getValidTestCasesPath(String rootPath) 代替
     */
    @Deprecated
    public static String getValidTestCasesPath() {
        return getValidTestCasesPath("");
    }
    
    /**
     * 是否使用缓存模式进行测试用例过滤
     */
    public static boolean isUseCacheMode() {
        return ConfigUtil.getBoolean("useCacheMode", DEFAULT_USE_CACHE_MODE);
    }
    
    // --- API Configuration ---
    
    public static String getOpenaiApiKey() {
        return getConfigThenEnv("openai.api.key", "OPENAI_API_KEY", "");
    }
    
    public static String getOpenaiBaseUrl() {
        return getConfigThenEnv("openai.base.url", "OPENAI_BASE_URL", "https://api.deepseek.com/beta/v1/chat/completions");
    }
    
    public static String getOpenaiModel() {
        return getConfigThenEnv("openai.model", "OPENAI_MODEL", "deepseek-chat");
    }
    
    public static String getOpenaiR1Model() {
        return ConfigUtil.getOrDefault("openai.r1.model", "deepseek-reasoner");
    }
    
    public static String getOpenaiV3Model() {
        return ConfigUtil.getOrDefault("openai.v3.model", "deepseek-chat");
    }
    
    public static String getDoubaoApiKey() {
        return getConfigThenEnv("doubao.api.key", "ARK_API_KEY", "");
    }
    
    public static String getDoubaoBaseUrl() {
        return getConfigThenEnv("doubao.base.url", "ARK_BASE_URL", "");
    }
    
    public static String getDoubaoModel() {
        return getConfigThenEnv("doubao.model", "ARK_MODEL", "ep-20250615170506-mtn4k");
    }
    
    public static String getBochaApiKey() {
        return getConfigThenEnv("bocha.api.key", "BOCHA_API_KEY", "");
    }
    
    public static String getWebSearchApiBaseUrl() {
        return ConfigUtil.getOrDefault("websearch.api.base.url", "https://api.bochaai.com/v1/web-search");
    }
    
    public static String getWebSearchRerankApiUrl() {
        return ConfigUtil.getOrDefault("websearch.rerank.api.url", "https://api.bochaai.com/v1/rerank");
    }
} 
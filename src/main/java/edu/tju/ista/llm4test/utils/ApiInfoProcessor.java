package edu.tju.ista.llm4test.utils;


import edu.tju.ista.llm4test.javaparser.APISignatureExtractor;
import edu.tju.ista.llm4test.config.GlobalConfig;
import org.jsoup.nodes.Document;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Paths;
import java.util.List;
import java.util.Arrays;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import java.util.logging.Level;

public class ApiInfoProcessor {
    
    /**
     * 路径类型枚举
     */
    private enum PathType {
        SOURCE(".java"),
        DOC(".html");
        
        private final String extension;
        
        PathType(String extension) {
            this.extension = extension;
        }
        
        public String getExtension() {
            return extension;
        }
    }
    
    private final String baseDocPath;
    private final String docRootPath;

    private final String jdkSourcePath;
    private final String defaultSourcePrefix;
    private final APISignatureExtractor extractor;
    
    // 缓存：类的完整标识 -> 文件路径
    private final Map<String, String> sourcePathCache = new ConcurrentHashMap<>();
    private final Map<String, String> docPathCache = new ConcurrentHashMap<>();

    public ApiInfoProcessor(String baseDocPath) {
        this.baseDocPath = baseDocPath;
        this.docRootPath = Path.of(this.baseDocPath).getParent().toString();
        this.jdkSourcePath = null;
        this.defaultSourcePrefix = null;
        this.extractor = new APISignatureExtractor();
    }
    

    /**
     * 支持JDK源码查找的构造函数
     */
    public ApiInfoProcessor(String baseDocPath, String jdkSourcePath, String defaultSourcePrefix) {
        this.baseDocPath = baseDocPath;
        this.docRootPath = Path.of(this.baseDocPath).getParent().toString();
        this.jdkSourcePath = jdkSourcePath;
        this.defaultSourcePrefix = defaultSourcePrefix;
        this.extractor = new APISignatureExtractor();
    }
    
    /**
     * 从配置文件创建ApiDocProcessor实例
     */
    public static ApiInfoProcessor fromConfig() {
        String baseDocPath = GlobalConfig.getBaseDocPath();
        String jdkSourcePath = GlobalConfig.getJdkSourcePath();
        String defaultSourcePrefix = GlobalConfig.getDefaultSourcePrefix();
        
        return new ApiInfoProcessor(baseDocPath, jdkSourcePath, defaultSourcePrefix);
    }

    public Map<String, String> processApiDocs(File file) throws IOException, RuntimeException {
        Map<String, String> result = new HashMap<>();
        
        try {
            var signatures = extractor.extractSignatures(file.getPath());
            var processedClasses = new java.util.HashSet<String>();
            
            signatures.forEach(signature -> {
                // 获取方法文档
                try {
                    String methodDoc = getApiDocumentation(signature);
                    result.put(signature.getSignature(), methodDoc);
                } catch (ApiInfoProcessingException e) {
                    LoggerUtil.logExec(Level.WARNING, "获取方法文档失败: " + e.getMessage());
                }
                
                // 获取类文档（避免重复）
                String className = signature.getClassName();
                if (processedClasses.add(className)) { // add() 返回 true 表示之前不存在
                    try {
                        String classDoc = getClassDocumentation(signature);
                        result.put(className, classDoc);
                    } catch (ApiInfoProcessingException e) {
                        LoggerUtil.logExec(Level.WARNING, "获取类文档失败: " + e.getMessage());
                    }
                }
            });
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "提取API签名失败: " + e.getMessage());
            throw new RuntimeException("处理API文档失败", e);
        }
        
        return result;
    }

    /**
     * 获取测试用例中调用的外部API的源码和文档
     * @param file 测试用例Java文件
     * @return 包含外部API方法源码和文档的Map
     * @throws Exception 处理异常
     */
    public Map<String, String> getApiDocWithSource(File file) throws Exception {
        Map<String, String> result = new HashMap<>();

        // 1. 使用APISignatureExtractor提取测试用例中调用的外部API签名
        var signatures = extractor.extractSignatures(file.getPath());
        
        // 2. 为每个外部API方法获取源码和文档
        for (var signature : signatures) {
            String methodKey = signature.getClassName() + "." + signature.getMethodName();
            StringBuilder methodInfo = new StringBuilder();
            

            methodInfo.append("方法签名: ").append(signature.getSignature()).append("\n");

            
            // 获取API源码
            try {
                String sourceCode = getMethodSourceCode(signature);
                if (sourceCode != null && !sourceCode.isEmpty()) {
                    methodInfo.append("=== 方法源码 ===\n");
                    methodInfo.append(sourceCode).append("\n\n");
                }
            } catch (ApiInfoProcessingException e) {
                LoggerUtil.logExec(Level.WARNING, "获取源码失败: " + e.getMessage());
            }
            
            // 获取API文档
            try {
                String apiDoc = getApiDocumentation(signature);
                if (apiDoc != null && !apiDoc.isEmpty()) {
                    methodInfo.append("=== API文档 ===\n");
                    methodInfo.append(apiDoc).append("\n\n");
                }
            } catch (ApiInfoProcessingException e) {
                LoggerUtil.logExec(Level.WARNING, "获取 API 文档失败: " + e.getMessage());
            }
            
            result.put(methodKey, methodInfo.toString());
        }
        
        // 3. 获取涉及的类的文档信息
        var processedClasses = new java.util.HashSet<String>();
        for (var signature : signatures) {
            String className = signature.getClassName();
            if (!processedClasses.contains(className)) {
                processedClasses.add(className);
                try {
                    String classDoc = getClassDocumentation(signature);
                    if (classDoc != null && !classDoc.isEmpty()) {
                        result.put(className + "_CLASS_DOC", classDoc);
                    }
                } catch (ApiInfoProcessingException e) {
                    LoggerUtil.logExec(Level.WARNING, "获取类文档失败: " + e.getMessage());
                }
            }
        }

        return result;
    }
    
    /**
     * 统一的类路径查找方法
     * @param packageName 包名
     * @param className 类名
     * @param type 路径类型（源码或文档）
     * @return 找到的文件路径，如果未找到返回null
     */
    private String findClassPath(String packageName, String className, PathType type) {
        String cacheKey = packageName + "." + className;
        Map<String, String> cache = (type == PathType.SOURCE) ? sourcePathCache : docPathCache;
        
        // 1. 先检查缓存
        if (cache.containsKey(cacheKey)) {
            String cachedValue = cache.get(cacheKey);
            // 特判NOTFOUND值，如果是NOTFOUND则返回null
            return "NOTFOUND".equals(cachedValue) ? null : cachedValue;
        }
        
        String foundPath = null;
        String fileName = className + type.getExtension();
        String packagePath = packageName.replace('.', File.separatorChar);
        
        try {
            // 2. 尝试默认路径
            if (type == PathType.SOURCE && jdkSourcePath != null && defaultSourcePrefix != null) {
                String defaultPath = Paths.get(jdkSourcePath, defaultSourcePrefix, packagePath, fileName).toString();
                if (new File(defaultPath).exists()) {
                    foundPath = defaultPath;
                }
            } else if (type == PathType.DOC && baseDocPath != null) {
                // 对于文档，默认路径是 baseDocPath/packagePath/className.html
                String defaultPath = Paths.get(baseDocPath, packagePath, fileName).toString();
                if (new File(defaultPath).exists()) {
                    foundPath = defaultPath;
                }
            }
            
            // 3. 如果默认路径找不到，使用find命令搜索
            if (foundPath == null) {
                foundPath = findPathUsingFind(packageName, fileName, type);
            }
            
            // 4. 缓存结果（如果是null则存储NOTFOUND，避免在缓存中存储null值）
            System.out.println(cacheKey + " " + foundPath);
            cache.put(cacheKey, foundPath != null ? foundPath : "NOTFOUND");
            
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("查找类路径失败: " + cacheKey + " (" + type + ") - " + e.getMessage());
            cache.put(cacheKey, "NOTFOUND");
        }
        
        return foundPath;
    }
    
    /**
     * 使用find命令查找文件路径
     * @param packageName 包名
     * @param fileName 文件名（包含扩展名）
     * @param type 路径类型
     * @return 找到的文件路径，如果未找到返回null
     */
    private String findPathUsingFind(String packageName, String fileName, PathType type) {
        try {
            String packagePath = packageName.replace('.', File.separatorChar);
            String searchPattern = "*" + File.separator + packagePath + File.separator + fileName;
            String searchRoot = (type == PathType.SOURCE) ? jdkSourcePath : docRootPath;
            
            if (searchRoot == null) {
                return null;
            }
            
            // 构建find命令
            List<String> command = Arrays.asList(
                "find", 
                searchRoot, 
                "-path", searchPattern,
                "-type", "f"
            );
            
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = processBuilder.start();
            
            // 等待命令完成（设置超时）
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            
            // 读取输出
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            }
            
        } catch (Exception e) {
            System.err.println("find命令执行失败: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 获取指定方法的源码
     * @param signature 方法签名
     * @return 方法源码，如果找不到返回null
     */
    private String getMethodSourceCode(APISignatureExtractor.MethodSignature signature) throws ApiInfoProcessingException {
        if (jdkSourcePath == null) {
            throw new ApiInfoProcessingException("JDK 源码路径未配置");
        }
        try {
            String sourceFilePath = findClassPath(signature.getPackageName(), signature.getClassName(), PathType.SOURCE);
            if (sourceFilePath == null) {
                throw new ApiInfoProcessingException("源码文件不存在: " + signature.getPackageName() + "." + signature.getClassName());
            }
            File sourceFile = new File(sourceFilePath);
            if (!sourceFile.exists()) {
                throw new ApiInfoProcessingException("源码文件不存在: " + sourceFilePath);
            }
            return parseMethodFromFile(sourceFile, signature);
        } catch (ApiInfoProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiInfoProcessingException("获取源码失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取API文档
     * @param signature 方法签名
     * @return API文档，如果找不到返回null
     */
    private String getApiDocumentation(APISignatureExtractor.MethodSignature signature) throws ApiInfoProcessingException {
        try {
            String docFilePath = findClassPath(signature.getPackageName(), signature.getClassName(), PathType.DOC);
            if (docFilePath == null) {
                throw new ApiInfoProcessingException("API 文档文件不存在: " + signature.getPackageName() + "." + signature.getClassName());
            }
            File docFile = new File(docFilePath);
            if (!docFile.exists()) {
                throw new ApiInfoProcessingException("API 文档文件不存在: " + docFilePath);
            }
            Document doc = HtmlParser.getDocumentFromFile(docFile);
            if (doc == null) {
                throw new ApiInfoProcessingException("解析 API 文档失败: " + docFilePath);
            }
            var methodDetails = HtmlParser.getMethodDetails(doc);
            String methodDoc = methodDetails.get(signature.getMethodName());
            if (methodDoc == null) {
                throw new ApiInfoProcessingException("未找到方法文档: " + signature.getMethodName());
            }
            return methodDoc;
        } catch (ApiInfoProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiInfoProcessingException("获取 API 文档失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取类文档
     * @param signature 方法签名（用于获取类信息）
     * @return 类文档，如果找不到返回null
     */
    private String getClassDocumentation(APISignatureExtractor.MethodSignature signature) throws ApiInfoProcessingException {
        try {
            String docFilePath = findClassPath(signature.getPackageName(), signature.getClassName(), PathType.DOC);
            if (docFilePath == null) {
                throw new ApiInfoProcessingException("类文档文件不存在: " + signature.getPackageName() + "." + signature.getClassName());
            }
            File docFile = new File(docFilePath);
            if (!docFile.exists()) {
                throw new ApiInfoProcessingException("类文档文件不存在: " + docFilePath);
            }
            Document doc = HtmlParser.getDocumentFromFile(docFile);
            if (doc == null) {
                throw new ApiInfoProcessingException("解析类文档失败: " + docFilePath);
            }
            String classDoc = HtmlParser.getClassDescriptionText(doc);
            return classDoc;
        } catch (ApiInfoProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiInfoProcessingException("获取类文档失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 从文件中解析方法源码
     * @param sourceFile 源码文件
     * @param signature 方法签名
     * @return 方法源码
     */
    private String parseMethodFromFile(File sourceFile, APISignatureExtractor.MethodSignature signature) throws ApiInfoProcessingException {
        try {
            JavaProjectBuilder builder = new JavaProjectBuilder();
            builder.addSource(sourceFile);
            for (JavaClass javaClass : builder.getClasses()) {
                if (javaClass.getName().equals(signature.getClassName())) {
                    for (JavaMethod method : javaClass.getMethods()) {
                        if (method.getName().equals(signature.getMethodName())) {
                            StringBuilder methodSource = new StringBuilder();
                            methodSource.append("// 源码文件: ").append(sourceFile.getAbsolutePath()).append("\n\n");
                            if (method.getComment() != null) {
                                methodSource.append("// 方法注释:\n");
                                methodSource.append(method.getComment()).append("\n\n");
                            }
                            methodSource.append("// 方法实现:\n");
                            methodSource.append(method.getSourceCode());
                            return methodSource.toString();
                        }
                    }
                }
            }
            throw new ApiInfoProcessingException("在源码文件中未找到方法: " + signature.getMethodName() + " (文件: " + sourceFile.getAbsolutePath() + ")");
        } catch (ApiInfoProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiInfoProcessingException("解析源码文件失败: " + e.getMessage() + " (文件: " + sourceFile.getAbsolutePath() + ")", e);
        }
    }

    /**
     * 自定义异常：API 信息处理过程中的异常
     */
    public static class ApiInfoProcessingException extends Exception {
        public ApiInfoProcessingException(String message) {
            super(message);
        }
        public ApiInfoProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
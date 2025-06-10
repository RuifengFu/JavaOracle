package edu.tju.ista.llm4test.utils;


import edu.tju.ista.llm4test.javaparser.APISignatureExtractor;
import edu.tju.ista.llm4test.config.GlobalConfig;
import org.jsoup.nodes.Document;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Paths;
import java.util.List;
import java.util.Arrays;

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
    private final String jdkSourcePath;
    private final String defaultSourcePrefix;
    private final APISignatureExtractor extractor;
    
    // 缓存：类的完整标识 -> 文件路径
    private final Map<String, String> sourcePathCache = new ConcurrentHashMap<>();
    private final Map<String, String> docPathCache = new ConcurrentHashMap<>();

    public ApiInfoProcessor(String baseDocPath) {
        this.baseDocPath = baseDocPath;
        this.jdkSourcePath = null;
        this.defaultSourcePrefix = null;
        this.extractor = new APISignatureExtractor();
    }
    

    /**
     * 支持JDK源码查找的构造函数
     */
    public ApiInfoProcessor(String baseDocPath, String jdkSourcePath, String defaultSourcePrefix) {
        this.baseDocPath = baseDocPath;
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
        Map<String, String> map = new HashMap<>();
        extractor.extractSignatures(file.getPath()).forEach(signature -> {
            try {
                Document doc = HtmlParser.getDocument(baseDocPath,
                        signature.getPackageName(),
                        signature.getClassName());
                map.put(signature.getSignature(), HtmlParser.getMethodDetails(doc).get(signature.getMethodName()));
                map.put(signature.getClassName(), HtmlParser.getClassDescriptionText(doc));
            } catch (IOException e) {
                // Handle exception
            }
        });
        return map;
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
            String sourceCode = getMethodSourceCode(signature);
            if (sourceCode != null && !sourceCode.isEmpty()) {
                methodInfo.append("=== 方法源码 ===\n");
                methodInfo.append(sourceCode).append("\n\n");
            }
            
            // 获取API文档
            String apiDoc = getApiDocumentation(signature);
            if (apiDoc != null && !apiDoc.isEmpty()) {
                methodInfo.append("=== API文档 ===\n");
                methodInfo.append(apiDoc).append("\n\n");
            }
            
            result.put(methodKey, methodInfo.toString());
        }
        
        // 3. 获取涉及的类的文档信息
        var processedClasses = new java.util.HashSet<String>();
        for (var signature : signatures) {
            String className = signature.getClassName();
            if (!processedClasses.contains(className)) {
                processedClasses.add(className);
                String classDoc = getClassDocumentation(signature);
                if (classDoc != null && !classDoc.isEmpty()) {
                    result.put(className + "_CLASS_DOC", classDoc);
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
            return cache.get(cacheKey);
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
            
            // 4. 缓存结果（包括null结果，避免重复搜索）
            cache.put(cacheKey, foundPath);
            
        } catch (Exception e) {
            System.err.println("查找类路径失败: " + cacheKey + " (" + type + ") - " + e.getMessage());
            cache.put(cacheKey, null);
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
            String searchRoot = (type == PathType.SOURCE) ? jdkSourcePath : baseDocPath;
            
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
    private String getMethodSourceCode(APISignatureExtractor.MethodSignature signature) {
        if (jdkSourcePath == null) {
            return "JDK源码路径未配置";
        }
        
        try {
            // 使用统一的路径查找方法
            String sourceFilePath = findClassPath(signature.getPackageName(), signature.getClassName(), PathType.SOURCE);
            
            if (sourceFilePath == null) {
                return "源码文件不存在: " + signature.getPackageName() + "." + signature.getClassName();
            }
            
            File sourceFile = new File(sourceFilePath);
            if (!sourceFile.exists()) {
                return "源码文件不存在: " + sourceFilePath;
            }
            
            return parseMethodFromFile(sourceFile, signature);
            
        } catch (Exception e) {
            return "获取源码失败: " + e.getMessage();
        }
    }
    
    /**
     * 获取API文档
     * @param signature 方法签名
     * @return API文档，如果找不到返回null
     */
    private String getApiDocumentation(APISignatureExtractor.MethodSignature signature) {
        try {
            // 使用统一的路径查找方法找到HTML文档
            String docFilePath = findClassPath(signature.getPackageName(), signature.getClassName(), PathType.DOC);
            
            if (docFilePath == null) {
                return "API文档文件不存在: " + signature.getPackageName() + "." + signature.getClassName();
            }
            
            File docFile = new File(docFilePath);
            if (!docFile.exists()) {
                return "API文档文件不存在: " + docFilePath;
            }
            
            // 使用HtmlParser解析HTML文件
            Document doc = HtmlParser.getDocumentFromFile(docFile);
            if (doc == null) {
                return "解析API文档失败: " + docFilePath;
            }
            
            var methodDetails = HtmlParser.getMethodDetails(doc);
            String methodDoc = methodDetails.get(signature.getMethodName());
            
            return methodDoc != null ? methodDoc : "未找到方法文档: " + signature.getMethodName();
            
        } catch (Exception e) {
            return "获取API文档失败: " + e.getMessage();
        }
    }
    
    /**
     * 获取类文档
     * @param signature 方法签名（用于获取类信息）
     * @return 类文档，如果找不到返回null
     */
    private String getClassDocumentation(APISignatureExtractor.MethodSignature signature) {
        try {
            // 使用统一的路径查找方法找到HTML文档
            String docFilePath = findClassPath(signature.getPackageName(), signature.getClassName(), PathType.DOC);
            
            if (docFilePath == null) {
                return "类文档文件不存在: " + signature.getPackageName() + "." + signature.getClassName();
            }
            
            File docFile = new File(docFilePath);
            if (!docFile.exists()) {
                return "类文档文件不存在: " + docFilePath;
            }
            
            // 使用HtmlParser解析HTML文件
            Document doc = HtmlParser.getDocumentFromFile(docFile);
            if (doc == null) {
                return "解析类文档失败: " + docFilePath;
            }
            
            return HtmlParser.getClassDescriptionText(doc);
            
        } catch (Exception e) {
            return "获取类文档失败: " + e.getMessage();
        }
    }
    
    /**
     * 从文件中解析方法源码
     * @param sourceFile 源码文件
     * @param signature 方法签名
     * @return 方法源码
     */
    private String parseMethodFromFile(File sourceFile, APISignatureExtractor.MethodSignature signature) {
        try {
            // 使用JavaParser解析源码文件
            JavaProjectBuilder builder = new JavaProjectBuilder();
            builder.addSource(sourceFile);
            
            // 查找对应的类和方法
            for (JavaClass javaClass : builder.getClasses()) {
                if (javaClass.getName().equals(signature.getClassName())) {
                    for (JavaMethod method : javaClass.getMethods()) {
                        // 简单的方法名匹配（可以根据需要改进为更精确的签名匹配）
                        if (method.getName().equals(signature.getMethodName())) {
                            StringBuilder methodSource = new StringBuilder();
                            
                            // 添加文件路径信息
                            methodSource.append("// 源码文件: ").append(sourceFile.getAbsolutePath()).append("\n\n");
                            
                            // 添加方法注释
                            if (method.getComment() != null) {
                                methodSource.append("// 方法注释:\n");
                                methodSource.append(method.getComment()).append("\n\n");
                            }
                            
                            // 添加方法源码
                            methodSource.append("// 方法实现:\n");
                            methodSource.append(method.getSourceCode());
                            
                            return methodSource.toString();
                        }
                    }
                }
            }
            
            return "在源码文件中未找到方法: " + signature.getMethodName() + " (文件: " + sourceFile.getAbsolutePath() + ")";
            
        } catch (Exception e) {
            return "解析源码文件失败: " + e.getMessage() + " (文件: " + sourceFile.getAbsolutePath() + ")";
        }
    }
}
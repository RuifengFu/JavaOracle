package edu.tju.ista.llm4test.utils;


import edu.tju.ista.llm4test.javaparser.APISignatureExtractor;
import edu.tju.ista.llm4test.config.ApplicationConfig;
import org.jsoup.nodes.Document;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Paths;
import java.util.List;
import java.util.Arrays;

public class ApiInfoProcessor {
    private final String baseDocPath;
    private final String jdkSourcePath;
    private final String defaultSourcePrefix;
    private final APISignatureExtractor extractor;

    public ApiInfoProcessor(String baseDocPath) {
        this.baseDocPath = baseDocPath;
        this.jdkSourcePath = null;
        this.defaultSourcePrefix = null;
        this.extractor = new APISignatureExtractor();
    }
    
    public ApiInfoProcessor(String baseDocPath, String jdkSourcePath) {
        this.baseDocPath = baseDocPath;
        this.jdkSourcePath = jdkSourcePath;
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
        String baseDocPath = ApplicationConfig.getBaseDocPath();
        String jdkSourcePath = ApplicationConfig.getJdkSourcePath();
        String defaultSourcePrefix = ApplicationConfig.getDefaultSourcePrefix();
        
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
            
            // 添加方法签名信息
            methodInfo.append("=== 方法信息 ===\n");
            methodInfo.append("完整限定名: ").append(signature.getQualifiedName()).append("\n");
            methodInfo.append("包名: ").append(signature.getPackageName()).append("\n");
            methodInfo.append("类名: ").append(signature.getClassName()).append("\n");
            methodInfo.append("方法名: ").append(signature.getMethodName()).append("\n");
            methodInfo.append("返回类型: ").append(signature.getReturnType()).append("\n");
            methodInfo.append("完整签名: ").append(signature.getSignature()).append("\n");
            methodInfo.append("调用行号: ").append(signature.getLineNumber()).append("\n\n");
            
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
            
            result.put(methodKey + "_L" + signature.getLineNumber(), methodInfo.toString());
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
     * 获取指定方法的源码
     * @param signature 方法签名
     * @return 方法源码，如果找不到返回null
     */
    private String getMethodSourceCode(APISignatureExtractor.MethodSignature signature) {
        if (jdkSourcePath == null) {
            return "JDK源码路径未配置";
        }
        
        try {
            // 构建源码文件路径
            String packagePath = signature.getPackageName().replace('.', '/');
            String className = signature.getClassName() + ".java";
            
            File sourceFile = null;
            String sourceFilePath = null;
            
            // 1. 优先在默认路径查找：jdkSourcePath + defaultSourcePrefix
            if (defaultSourcePrefix != null) {
                sourceFilePath = Paths.get(jdkSourcePath, defaultSourcePrefix, packagePath, className).toString();
                sourceFile = new File(sourceFilePath);
                
                if (sourceFile.exists()) {
                    return parseMethodFromFile(sourceFile, signature);
                }
            }
            
            // 2. 如果默认路径找不到，使用find命令动态查找
            String foundPath = findSourceFileUsingFind(signature.getPackageName(), className);
            if (foundPath != null) {
                sourceFile = new File(foundPath);
                if (sourceFile.exists()) {
                    return parseMethodFromFile(sourceFile, signature);
                }
            }
            
            return "源码文件不存在。尝试的路径: " + sourceFilePath + 
                   (foundPath != null ? ", find结果: " + foundPath : ", find未找到");
            
        } catch (Exception e) {
            return "获取源码失败: " + e.getMessage();
        }
    }
    
    /**
     * 使用find命令查找源码文件
     * @param packageName 包名
     * @param className 类名（包含.java扩展名）
     * @return 找到的文件路径，如果未找到返回null
     */
    private String findSourceFileUsingFind(String packageName, String className) {
        try {
            // 构建包路径作为搜索模式
            String packagePath = packageName.replace('.', '/');
            String searchPattern = "*/" + packagePath + "/" + className;
            
            // 构建find命令
            List<String> command = Arrays.asList(
                "find", 
                jdkSourcePath, 
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
            // find命令失败，静默处理
            System.err.println("find命令执行失败: " + e.getMessage());
        }
        
        return null;
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
    
    /**
     * 获取API文档
     * @param signature 方法签名
     * @return API文档，如果找不到返回null
     */
    private String getApiDocumentation(APISignatureExtractor.MethodSignature signature) {
        try {
            Document doc = HtmlParser.getDocument(baseDocPath,
                    signature.getPackageName(),
                    signature.getClassName());
            var methodDetails = HtmlParser.getMethodDetails(doc);
            return methodDetails.get(signature.getMethodName());
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
            Document doc = HtmlParser.getDocument(baseDocPath,
                    signature.getPackageName(),
                    signature.getClassName());
            return HtmlParser.getClassDescriptionText(doc);
        } catch (Exception e) {
            return "获取类文档失败: " + e.getMessage();
        }
    }
}
package edu.tju.ista.llm4test.utils;


import edu.tju.ista.llm4test.javaparser.APISignatureExtractor;
import edu.tju.ista.llm4test.config.GlobalConfig;
import org.jsoup.nodes.Document;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaConstructor;
import com.thoughtworks.qdox.model.JavaMethod;
import com.thoughtworks.qdox.model.JavaParameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * API信息处理器：从JDK源码定位并提取API的源码与文档。
 * <p>
 * 文档策略：优先从JDK源码的Javadoc注释解析（无需下载HTML文档）；
 * 仅当源码中无注释时回退到本地HTML JavaDoc（若存在）。
 * <p>
 * 匹配策略：方法名 + 参数类型（擦除泛型）多级匹配，支持内部类
 * （Path2D.Float等）、varargs、父类/接口层级回退。
 */
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

    // 缓存：源码文件路径 -> QDox builder（避免每个签名重复解析同一文件）
    private final Map<String, JavaProjectBuilder> builderCache = new ConcurrentHashMap<>();

    /**
     * 已定位到的类成员（方法或构造函数）
     */
    private static class LocatedMember {
        final File file;
        final JavaClass javaClass;
        final JavaMethod method;       // 二选一为null
        final JavaConstructor constructor;

        LocatedMember(File file, JavaClass javaClass, JavaMethod method, JavaConstructor constructor) {
            this.file = file;
            this.javaClass = javaClass;
            this.method = method;
            this.constructor = constructor;
        }

        boolean isConstructor() {
            return constructor != null;
        }
    }

    public ApiInfoProcessor(String baseDocPath) {
        this.baseDocPath = blankToNull(baseDocPath);
        this.docRootPath = docRootOf(baseDocPath);
        this.jdkSourcePath = null;
        this.defaultSourcePrefix = null;
        this.extractor = new APISignatureExtractor();
    }

    /**
     * 支持JDK源码查找的构造函数
     */
    public ApiInfoProcessor(String baseDocPath, String jdkSourcePath, String defaultSourcePrefix) {
        this.baseDocPath = blankToNull(baseDocPath);
        this.docRootPath = docRootOf(baseDocPath);
        this.jdkSourcePath = jdkSourcePath;
        this.defaultSourcePrefix = defaultSourcePrefix;
        this.extractor = new APISignatureExtractor();
    }

    /**
     * 推导文档搜索根目录（baseDocPath 的父目录）。
     * baseDocPath 是可选配置（可留空），且可能是单段相对路径——两种情况下
     * {@code Path.getParent()} 均为 null，此时返回 null，由调用方跳过 find 回退。
     */
    private static String docRootOf(String baseDocPath) {
        if (baseDocPath == null || baseDocPath.isBlank()) {
            return null;
        }
        Path parent = Path.of(baseDocPath).getParent();
        return parent != null ? parent.toString() : null;
    }

    /** 空白配置值视为“未配置”（baseDocPath 为可选项）。 */
    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
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
                    LoggerUtil.logExec(Level.FINE, "获取方法文档失败: " + e.getMessage());
                }

                // 获取类文档（避免重复）
                String className = signature.getClassName();
                if (processedClasses.add(className)) { // add() 返回 true 表示之前不存在
                    try {
                        String classDoc = getClassDocumentation(signature);
                        result.put(className, classDoc);
                    } catch (ApiInfoProcessingException e) {
                        LoggerUtil.logExec(Level.FINE, "获取类文档失败: " + e.getMessage());
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
                LoggerUtil.logExec(Level.FINE, "获取源码失败: " + e.getMessage());
            }

            // 获取API文档
            try {
                String apiDoc = getApiDocumentation(signature);
                if (apiDoc != null && !apiDoc.isEmpty()) {
                    methodInfo.append("=== API 文档 ===\n");
                    methodInfo.append(apiDoc).append("\n\n");
                }
            } catch (ApiInfoProcessingException e) {
                LoggerUtil.logExec(Level.FINE, "获取 API 文档失败: " + e.getMessage());
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
                    LoggerUtil.logExec(Level.FINE, "获取类文档失败: " + e.getMessage());
                }
            }
        }

        return result;
    }

    // ==================== 类路径查找 ====================

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

        // 获取外部类名用于文件查找
        String outerClassName = className;
        int dotIndex = outerClassName.indexOf('.');
        if (dotIndex != -1) {
            outerClassName = outerClassName.substring(0, dotIndex);
        }
        String fileName = outerClassName + type.getExtension();
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

            // 3. 如果默认路径找不到：源码走共享索引，文档走find命令
            if (foundPath == null) {
                if (type == PathType.SOURCE) {
                    foundPath = findPathUsingIndex(packagePath.replace(File.separatorChar, '/') + "/" + fileName);
                } else {
                    foundPath = findPathUsingFind(packageName, fileName, type);
                }
            }

            // 4. 缓存结果（如果是null则存储NOTFOUND，避免在缓存中存储null值）
            cache.put(cacheKey, foundPath != null ? foundPath : "NOTFOUND");

        } catch (Exception e) {
            LoggerUtil.logExec(Level.FINE, "查找类路径失败: " + cacheKey + " (" + type + ") - " + e.getMessage());
            cache.put(cacheKey, "NOTFOUND");
        }

        return foundPath;
    }

    /**
     * 通过共享源码索引查找文件路径
     */
    private String findPathUsingIndex(String relKey) {
        if (jdkSourcePath == null) {
            return null;
        }
        SourceTreeIndex index = SourceTreeIndex.getInstance(jdkSourcePath);
        if (index == null) {
            return null;
        }
        java.nio.file.Path found = index.find(relKey);
        return found != null ? found.toString() : null;
    }

    /**
     * 使用find命令查找文件路径（仅作为文档的备用查找）
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
                process.destroy();
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
            LoggerUtil.logExec(Level.FINE, "find命令执行失败: " + e.getMessage());
        }

        return null;
    }

    // ==================== 类定位与成员匹配 ====================

    /**
     * 获取（或创建）源码文件对应的QDox builder，带缓存
     */
    private JavaProjectBuilder builderFor(String sourceFilePath) {
        JavaProjectBuilder cached = builderCache.get(sourceFilePath);
        if (cached != null) {
            return cached;
        }
        if (builderCache.size() > 150) {
            // 粗粒度淘汰，防止内存膨胀（JDK单文件最大几MB）
            builderCache.clear();
        }
        JavaProjectBuilder builder = new JavaProjectBuilder();
        try {
            builder.addSource(new File(sourceFilePath));
        } catch (Exception e) {
            return null;
        }
        builderCache.put(sourceFilePath, builder);
        return builder;
    }

    /**
     * 定位类：找源码文件 -> QDox解析 -> 精确类查找
     */
    private JavaClass resolveJavaClass(String packageName, String className, StringBuilder filePathOut) {
        String sourceFilePath = findClassPath(packageName, className, PathType.SOURCE);
        if (sourceFilePath == null) {
            return null;
        }
        JavaClass jc = QdoxUtil.lookupClass(builderFor(sourceFilePath), packageName, className);
        if (jc != null && filePathOut != null) {
            filePathOut.append(sourceFilePath);
        }
        return jc;
    }

    /**
     * 解析签名字符串中的方法名与参数列表
     * @return [methodName, param1, param2, ...]，解析失败返回null
     */
    private static List<String> splitSignature(String signature) {
        int p = signature.indexOf('(');
        if (p <= 0) {
            return null;
        }
        int e = signature.lastIndexOf(')');
        if (e < p) {
            return null;
        }
        String namePart = signature.substring(0, p);
        int lastDot = namePart.lastIndexOf('.');
        if (lastDot < 0 || lastDot == namePart.length() - 1) {
            return null;
        }
        List<String> out = new ArrayList<>();
        out.add(namePart.substring(lastDot + 1));
        String params = signature.substring(p + 1, e).trim();
        if (params.isEmpty()) {
            return out;
        }
        // 顶层逗号分割（考虑<>嵌套）
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : params.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    /**
     * 擦除泛型的参数类型规范化：去空格、去<...>、varargs转数组、统一内部类分隔符
     */
    private static String eraseType(String type) {
        String s = type.replaceAll("\\s", "");
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : s.toCharArray()) {
            if (c == '<') { depth++; continue; }
            if (c == '>') { depth--; continue; }
            if (depth == 0) sb.append(c);
        }
        String r = sb.toString().replace('$', '.');
        if (r.endsWith("...")) {
            r = r.substring(0, r.length() - 3) + "[]";
        }
        return r;
    }

    /**
     * 简单名形式：java.lang.String[] -> String[]
     */
    private static String simpleType(String erasedType) {
        int idx = -1;
        for (int i = erasedType.length() - 1; i >= 0; i--) {
            char c = erasedType.charAt(i);
            if (c == '.') { idx = i; break; }
            if (Character.isUpperCase(c)) {
                // 从这个大写字母向前找到段边界
                while (i > 0 && erasedType.charAt(i - 1) != '.') i--;
                idx = i - 1;
                break;
            }
        }
        return idx >= 0 ? erasedType.substring(idx + 1) : erasedType;
    }

    /**
     * QDox参数的擦除FQN形式（与eraseType输出对齐）。
     * QDox将数组编码在FQN中（char[]）；varargs类型为组件类型，需补[]。
     */
    private static String qdoxParamType(JavaParameter param) {
        String fqn = param.getType().getFullyQualifiedName().replace('$', '.');
        if (param.isVarArgs() && !fqn.endsWith("[]")) {
            fqn = fqn + "[]";
        }
        return fqn;
    }

    /**
     * 多级参数匹配
     * @return 0=全限定名精确匹配, 1=简单名匹配, 2=仅参数个数匹配, -1=不匹配
     */
    private static int matchLevel(List<String> candidateParams, List<String> targetParamsErased) {
        if (candidateParams.size() != targetParamsErased.size()) {
            return -1;
        }
        boolean simpleOk = true;
        for (int i = 0; i < candidateParams.size(); i++) {
            String cand = candidateParams.get(i);
            String target = targetParamsErased.get(i);
            if (!cand.equals(target)) {
                if (!simpleType(cand).equals(simpleType(target))) {
                    // 类型不匹配：若参数个数一致，降为arity级（由调用方消歧）
                    return 2;
                }
                simpleOk = false; // 简单名相同但FQN不同，降为简单名级
            }
        }
        return simpleOk ? 0 : 1;
    }

    /**
     * 在类中查找方法：名称 + 多级参数匹配 + 重载消歧
     */
    private JavaMethod findMethodInClass(JavaClass javaClass, String methodName, List<String> targetParamsErased) {
        JavaMethod best = null;
        int bestLevel = Integer.MAX_VALUE;
        int bestLevelCount = 0;
        for (JavaMethod m : javaClass.getMethods()) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            List<String> params = new ArrayList<>();
            List<JavaParameter> qdoxParams = m.getParameters();
            for (int i = 0; i < qdoxParams.size(); i++) {
                params.add(qdoxParamType(qdoxParams.get(i)));
            }
            int level = matchLevel(params, targetParamsErased);
            if (level < 0) {
                continue;
            }
            if (level < bestLevel) {
                best = m;
                bestLevel = level;
                bestLevelCount = 1;
            } else if (level == bestLevel) {
                bestLevelCount++;
            }
        }
        // 同级出现多个候选：精确级(0/1)取第一个即可（真实重载不会同级同签名）；
        // arity级有歧义时不返回
        if (bestLevel == 2 && bestLevelCount > 1) {
            return null;
        }
        return best;
    }

    /**
     * 在类中查找构造函数：参数多级匹配（构造函数名固定为类名，不参与匹配）
     */
    private JavaConstructor findConstructorInClass(JavaClass javaClass, List<String> targetParamsErased) {
        JavaConstructor best = null;
        int bestLevel = Integer.MAX_VALUE;
        int bestLevelCount = 0;
        for (JavaConstructor c : javaClass.getConstructors()) {
            List<String> params = new ArrayList<>();
            List<JavaParameter> qdoxParams = c.getParameters();
            for (int i = 0; i < qdoxParams.size(); i++) {
                params.add(qdoxParamType(qdoxParams.get(i)));
            }
            int level = matchLevel(params, targetParamsErased);
            if (level < 0) {
                continue;
            }
            if (level < bestLevel) {
                best = c;
                bestLevel = level;
                bestLevelCount = 1;
            } else if (level == bestLevel) {
                bestLevelCount++;
            }
        }
        if (bestLevel == 2 && bestLevelCount > 1) {
            return null;
        }
        return best;
    }

    /**
     * 将FQN拆分为 [包名, 类名链]。
     * 例如 java.util.Map.Entry -> ["java.util", "Map.Entry"]
     */
    private static String[] splitFqn(String fqn) {
        String[] segs = fqn.split("\\.");
        int clsStart = -1;
        for (int i = 0; i < segs.length; i++) {
            if (!segs[i].isEmpty() && Character.isUpperCase(segs[i].charAt(0))) {
                clsStart = i;
                break;
            }
        }
        if (clsStart <= 0) {
            return null;
        }
        String pkg = String.join(".", Arrays.copyOfRange(segs, 0, clsStart));
        StringBuilder cls = new StringBuilder(segs[clsStart]);
        for (int i = clsStart + 1; i < segs.length; i++) cls.append('.').append(segs[i]);
        return new String[]{pkg, cls.toString()};
    }

    /**
     * 规范化QDox给出的父类/接口FQN（简单名补包名）
     */
    private static String normalizeFqn(String fqn, String contextPackage) {
        if (fqn == null || fqn.isBlank() || "java.lang.Object".equals(fqn)) {
            return null;
        }
        fqn = fqn.replace('$', '.');
        if (!fqn.contains(".")) {
            return contextPackage + "." + fqn;
        }
        return fqn;
    }

    /**
     * 带层级回退的成员定位：先在指定类中找，找不到则沿父类/接口链向上（最多8步）。
     * 用于处理方法声明在父类/接口、但测试用例通过子类调用的情形。
     */
    private LocatedMember locateMember(String packageName, String className, String methodName,
                                       List<String> targetParamsErased, boolean wantConstructor) {
        Set<String> visited = new HashSet<>();
        Deque<String[]> queue = new ArrayDeque<>();
        queue.add(new String[]{packageName, className});

        while (!queue.isEmpty() && visited.size() < 8) {
            String[] cur = queue.poll();
            String key = cur[0] + "." + cur[1];
            if (!visited.add(key)) {
                continue;
            }
            StringBuilder filePath = new StringBuilder();
            JavaClass jc = resolveJavaClass(cur[0], cur[1], filePath);
            if (jc == null) {
                continue;
            }
            File file = filePath.length() > 0 ? new File(filePath.toString()) : null;

            if (!wantConstructor) {
                JavaMethod m = findMethodInClass(jc, methodName, targetParamsErased);
                if (m != null) {
                    return new LocatedMember(file, jc, m, null);
                }
            } else {
                JavaConstructor c = findConstructorInClass(jc, targetParamsErased);
                if (c != null) {
                    return new LocatedMember(file, jc, null, c);
                }
            }

            // 入队父类与接口（在其自身源码文件中继续查找）
            try {
                String sup = normalizeFqn(jc.getSuperJavaClass() == null ? null
                        : jc.getSuperJavaClass().getFullyQualifiedName(), cur[0]);
                if (sup != null) {
                    String[] parts = splitFqn(sup);
                    if (parts != null) queue.add(parts);
                }
                for (JavaClass itf : jc.getInterfaces()) {
                    String ifn = normalizeFqn(itf.getFullyQualifiedName(), cur[0]);
                    if (ifn != null) {
                        String[] parts = splitFqn(ifn);
                        if (parts != null) queue.add(parts);
                    }
                }
            } catch (Exception ignored) {
                // 层级信息仅作辅助
            }
        }
        return null;
    }

    // ==================== 源码获取 ====================

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
            List<String> parsed = splitSignature(signature.getSignature());
            if (parsed == null) {
                throw new ApiInfoProcessingException("签名格式无法解析: " + signature.getSignature());
            }
            String methodName = parsed.get(0);
            List<String> targetParams = new ArrayList<>();
            for (int i = 1; i < parsed.size(); i++) targetParams.add(eraseType(parsed.get(i)));

            boolean isCtor = methodName.equals(signature.getClassName())
                    || signature.getReturnType().equals(signature.getClassName());
            // 构造函数在内部类时: 方法名是内部类简单名
            if (isCtor && signature.getClassName().contains(".")) {
                String innerSimple = signature.getClassName().substring(signature.getClassName().lastIndexOf('.') + 1);
                methodName = innerSimple;
            }

            LocatedMember located = locateMember(signature.getPackageName(), signature.getClassName(),
                    methodName, targetParams, isCtor);
            if (located == null) {
                throw new ApiInfoProcessingException("在源码中未找到方法或构造函数: " + signature.getSignature());
            }
            return buildMemberSource(located);
        } catch (ApiInfoProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiInfoProcessingException("获取源码失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造方法/构造函数源码文本（保持原有输出格式）
     */
    private String buildMemberSource(LocatedMember located) {
        StringBuilder sb = new StringBuilder();
        sb.append("// 源码文件: ").append(located.file != null ? located.file.getAbsolutePath() : "unknown").append("\n\n");
        if (located.isConstructor()) {
            if (located.constructor.getComment() != null) {
                sb.append("// 构造函数注释:\n").append(located.constructor.getComment()).append("\n\n");
            }
            sb.append("// 构造函数实现:\n").append(located.constructor.getSourceCode());
        } else {
            if (located.method.getComment() != null) {
                sb.append("// 方法注释:\n").append(located.method.getComment()).append("\n\n");
            }
            sb.append("// 方法实现:\n").append(located.method.getSourceCode());
        }
        return sb.toString();
    }

    // ==================== 文档获取（源码注释优先，HTML回退） ====================

    /**
     * 获取API文档：优先从源码Javadoc注释解析；无注释时回退HTML JavaDoc（若存在）。
     * @param signature 方法签名
     * @return API文档，如果找不到返回null
     */
    private String getApiDocumentation(APISignatureExtractor.MethodSignature signature) throws ApiInfoProcessingException {
        // 判断是否为构造函数：方法名与类名相同，或者返回类型与类名相同
        boolean isConstructor = signature.getMethodName().equals(signature.getClassName()) ||
                               signature.getReturnType().equals(signature.getClassName());

        // 1) 源码注释文档（无需下载JavaDoc）
        if (jdkSourcePath != null) {
            try {
                List<String> parsed = splitSignature(signature.getSignature());
                if (parsed != null) {
                    String methodName = parsed.get(0);
                    List<String> targetParams = new ArrayList<>();
                    for (int i = 1; i < parsed.size(); i++) targetParams.add(eraseType(parsed.get(i)));

                    String lookupClassName = signature.getClassName();
                    if (isConstructor && lookupClassName.contains(".")) {
                        methodName = lookupClassName.substring(lookupClassName.lastIndexOf('.') + 1);
                    }

                    LocatedMember located = locateMember(signature.getPackageName(), lookupClassName,
                            methodName, targetParams, isConstructor);
                    if (located != null) {
                        String doc = located.isConstructor()
                                ? SourceDocExtractor.constructorDoc(located.javaClass, located.constructor)
                                : SourceDocExtractor.methodDoc(located.javaClass, located.method);
                        if (doc != null && !doc.isBlank()) {
                            return doc;
                        }
                    }
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.FINE, "源码注释文档提取失败，尝试HTML回退: " + e.getMessage());
            }
        }

        // 2) HTML JavaDoc回退
        if (isConstructor) {
            return getConstructorDocumentationHtml(signature);
        } else {
            return getMethodDocumentationHtml(signature);
        }
    }

    /**
     * 获取类文档：优先源码类级Javadoc注释；回退HTML。
     */
    private String getClassDocumentation(APISignatureExtractor.MethodSignature signature) throws ApiInfoProcessingException {
        // 1) 源码注释
        if (jdkSourcePath != null) {
            try {
                JavaClass jc = resolveJavaClass(signature.getPackageName(), signature.getClassName(), null);
                if (jc != null) {
                    String doc = SourceDocExtractor.classDoc(jc);
                    if (doc != null && !doc.isBlank()) {
                        return doc;
                    }
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.FINE, "源码类文档提取失败，尝试HTML回退: " + e.getMessage());
            }
        }

        // 2) HTML回退
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
     * 获取普通方法文档（HTML实现，作为回退）
     * @param signature 方法签名
     * @return 方法文档
     */
    private String getMethodDocumentationHtml(APISignatureExtractor.MethodSignature signature) throws ApiInfoProcessingException {
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
     * 获取构造函数文档（HTML实现，作为回退）
     * @param signature 构造函数签名
     * @return 构造函数文档
     */
    private String getConstructorDocumentationHtml(APISignatureExtractor.MethodSignature signature) throws ApiInfoProcessingException {
        try {
            String docFilePath = findClassPath(signature.getPackageName(), signature.getClassName(), PathType.DOC);
            if (docFilePath == null) {
                throw new ApiInfoProcessingException("构造函数文档文件不存在: " + signature.getPackageName() + "." + signature.getClassName());
            }
            File docFile = new File(docFilePath);
            if (!docFile.exists()) {
                throw new ApiInfoProcessingException("构造函数文档文件不存在: " + docFilePath);
            }
            Document doc = HtmlParser.getDocumentFromFile(docFile);
            if (doc == null) {
                throw new ApiInfoProcessingException("解析构造函数文档失败: " + docFilePath);
            }

            var constructorDetails = HtmlParser.getConstructorDetails(doc);

            // 尝试多种方式匹配构造函数文档
            String constructorDoc = findConstructorDoc(constructorDetails, signature);

            if (constructorDoc == null) {
                throw new ApiInfoProcessingException("未找到构造函数文档: " + signature.getSignature());
            }
            return constructorDoc;
        } catch (ApiInfoProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiInfoProcessingException("获取构造函数文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从构造函数详细信息中查找匹配的文档
     * @param constructorDetails 构造函数详细信息映射
     * @param signature 构造函数签名
     * @return 匹配的构造函数文档，如果找不到返回null
     */
    private String findConstructorDoc(Map<String, String> constructorDetails, APISignatureExtractor.MethodSignature signature) {
        String className = signature.getClassName();
        String fullSignature = signature.getSignature();

        // 方案1: 直接用类名匹配（无参构造函数）
        if (constructorDetails.containsKey(className)) {
            return constructorDetails.get(className);
        }

        // 方案2: 提取参数部分，构造短签名进行匹配
        int parenIndex = fullSignature.indexOf('(');
        if (parenIndex > 0) {
            String paramsPart = fullSignature.substring(parenIndex);
            String shortSignature = className + paramsPart;
            if (constructorDetails.containsKey(shortSignature)) {
                return constructorDetails.get(shortSignature);
            }
        }

        // 方案3: 模糊匹配 - 查找以类名开头且包含参数的构造函数
        for (String key : constructorDetails.keySet()) {
            if (key.startsWith(className + "(")) {
                // 进一步检查参数是否匹配
                if (compareConstructorSignatures(key, fullSignature)) {
                    return constructorDetails.get(key);
                }
            }
        }

        // 方案4: 如果只有一个构造函数，直接返回
        if (constructorDetails.size() == 1) {
            return constructorDetails.values().iterator().next();
        }

        return null;
    }

    /**
     * 比较构造函数签名是否匹配
     * @param docSignature 文档中的签名
     * @param extractedSignature 提取的签名
     * @return 是否匹配
     */
    private boolean compareConstructorSignatures(String docSignature, String extractedSignature) {
        // 简单的参数数量比较
        int docParamCount = countParameters(docSignature);
        int extractedParamCount = countParameters(extractedSignature);
        return docParamCount == extractedParamCount;
    }

    /**
     * 计算签名中的参数数量
     * @param signature 方法或构造函数签名
     * @return 参数数量
     */
    private int countParameters(String signature) {
        int start = signature.indexOf('(');
        int end = signature.lastIndexOf(')');
        if (start == -1 || end == -1 || start >= end) {
            return 0;
        }
        String params = signature.substring(start + 1, end).trim();
        if (params.isEmpty()) {
            return 0;
        }
        return params.split(",").length;
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
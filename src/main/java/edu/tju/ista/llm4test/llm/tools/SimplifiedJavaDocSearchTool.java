package edu.tju.ista.llm4test.llm.tools;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaConstructor;
import com.thoughtworks.qdox.model.JavaMethod;
import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.utils.QdoxUtil;
import edu.tju.ista.llm4test.utils.SourceDocExtractor;
import edu.tju.ista.llm4test.utils.SourceTreeIndex;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

/**
 * 简化的JavaDoc搜索工具 - 只提供基础静态文档检索功能
 * Agent负责智能决策，工具只负责执行具体的文档查找操作。
 * <p>
 * 文档来源：优先从JDK源码树（jdk17u-dev/src）的Javadoc注释解析，
 * 无需下载HTML JavaDoc；仅当源码中找不到（如ByteBuffer等构建时模板生成类）
 * 且本地存在HTML JavaDoc时才回退。
 */
public class SimplifiedJavaDocSearchTool implements Tool<String> {
    private final String javadocBasePath;
    private final String sourceRootPath;
    private final SourceTreeIndex sourceIndex;
    private Path apiRootPath;

    /**
     * 源码优先构造函数（推荐）
     * @param sourceRootPath JDK源码根目录（如 jdk17u-dev/src），可为null
     * @param javadocBasePath HTML JavaDoc根目录（可选回退），可为null
     */
    public SimplifiedJavaDocSearchTool(String sourceRootPath, String javadocBasePath) {
        this.sourceRootPath = sourceRootPath;
        this.javadocBasePath = javadocBasePath;
        this.sourceIndex = SourceTreeIndex.getInstance(sourceRootPath);
        this.apiRootPath = findApiRootDir();
    }

    /**
     * 兼容旧构造函数：仅HTML JavaDoc模式
     */
    public SimplifiedJavaDocSearchTool(String javadocBasePath) {
        this(null, javadocBasePath);
    }

    public SimplifiedJavaDocSearchTool() {
        this(GlobalConfig.getJdkSourcePath(), GlobalConfig.getBaseDocPath());
    }

    @Override
    public String getName() {
        return "simplified_javadoc_search";
    }

    @Override
    public String getDescription() {
        return "Static JavaDoc search tool - Search for class documents, package information, and API descriptions "
                + "parsed directly from the JDK source code Javadoc comments (no HTML download needed). "
                + "Supports search by class name, access by path, package browsing, and document structure exploration.";
    }

    @Override
    public List<String> getParameters() {
        return List.of("search_type", "doc_path", "class_name", "package_name");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
            "search_type", "Search type (required):\n" +
                          "• 'by_class' - Get API document of a class (class description, method list with doc summaries)\n" +
                          "• 'by_path' - Get document by source path\n" +
                          "• 'by_package' - List all classes (with brief docs) in a package\n" +
                          "• 'list_files' - Browse package directory structure",

            "doc_path", "Document path (for by_path):\n" +
                       "• Package-relative path, e.g. 'java/util/HashMap.java'\n" +
                       "• Also accepts legacy '.html' form, e.g. 'java/util/HashMap.html'\n" +
                       "• Simple file name, e.g. 'HashMap'\n" +
                       "• The tool resolves it to the JDK source file and extracts its Javadoc comments",

            "class_name", "Class name (for by_class):\n" +
                         "• Recommend full class name, e.g. 'java.util.HashMap'\n" +
                         "• Supports simple class name, e.g. 'HashMap'\n" +
                         "• Supports inner class, e.g. 'java.util.Map.Entry'\n" +
                         "• Returns class-level doc plus method/constructor list with summaries",

            "package_name", "Package name (for by_package and list_files):\n" +
                            "• Full package name, e.g. 'java.util', 'java.io'\n" +
                            "• For by_package, list all classes with brief descriptions in the package\n" +
                            "• For list_files, browse directory structure of the package\n" +
                            "• If empty, list_files shows root structure"
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
            "search_type", "string",
            "doc_path", "string",
            "class_name", "string",
            "package_name", "string"
        );
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        try {
            String searchType = (String) args.get("search_type");
            if (searchType == null) {
                return ToolResponse.failure("必须指定search_type参数");
            }

            switch (searchType) {
                case "by_path":
                    return getDocByPath((String) args.get("doc_path"));
                case "by_class":
                    return searchDocByClassName((String) args.get("class_name"));
                case "by_package":
                    return listPackageClasses((String) args.get("package_name"));
                case "list_files":
                    return listAvailableFiles((String) args.get("package_name"));
                default:
                    return ToolResponse.failure("不支持的搜索类型: " + searchType);
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.SEVERE, "JavaDoc搜索失败: " + e.getMessage());
            return ToolResponse.failure("搜索失败: " + e.getMessage());
        }
    }

    // ===================== 源码注释文档提取 =====================

    /**
     * 将类解析为源码文档（QDox + SourceDocExtractor）
     */
    private String renderSourceDoc(Path sourceFile, String classNameHint) {
        try {
            JavaProjectBuilder builder = new JavaProjectBuilder();
            builder.addSource(sourceFile.toFile());

            // 定位目标类：优先按提示的类名，否则取第一个顶层类
            JavaClass jc = null;
            if (classNameHint != null && !classNameHint.isBlank()) {
                String simple = classNameHint;
                String pkg = "";
                if (classNameHint.contains(".")) {
                    // FQN: 拆出包名与类链（支持内部类）
                    String[] parts = splitFqn(classNameHint);
                    if (parts != null) {
                        pkg = parts[0];
                        simple = parts[1];
                    }
                }
                jc = QdoxUtil.lookupClass(builder, pkg, simple);
            }
            if (jc == null) {
                for (JavaClass top : builder.getClasses()) {
                    jc = top;
                    break;
                }
            }
            if (jc == null) {
                return null;
            }
            return renderJavaClassDoc(jc, sourceFile);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "解析源码文档失败(" + sourceFile + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * FQN拆分为 [包名, 类名链]，如 java.util.Map.Entry -> ["java.util", "Map.Entry"]
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
     * 渲染类文档：类级文档 + 方法/构造函数摘要列表
     */
    private String renderJavaClassDoc(JavaClass jc, Path sourceFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 类文档: ").append(jc.getFullyQualifiedName()).append("\n\n");
        sb.append("**源码文件**: ").append(sourceFile).append("\n\n");
        sb.append(SourceDocExtractor.classDoc(jc)).append("\n");

        // 方法列表（签名 + 首句描述）
        List<JavaMethod> methods = jc.getMethods();
        if (!methods.isEmpty()) {
            sb.append("## 方法列表 (").append(methods.size()).append(")\n\n");
            for (JavaMethod m : methods) {
                sb.append("- `").append(jc.getFullyQualifiedName()).append('.')
                        .append(m.getName()).append(paramTypes(m)).append("`");
                String first = firstSentence(m.getComment());
                if (first != null) {
                    sb.append(" — ").append(first);
                }
                sb.append('\n');
                if (sb.length() > 12000) {
                    sb.append("...内容过长，已截断\n");
                    break;
                }
            }
            sb.append('\n');
        }

        // 构造函数列表
        List<JavaConstructor> ctors = jc.getConstructors();
        if (!ctors.isEmpty()) {
            sb.append("## 构造函数列表 (").append(ctors.size()).append(")\n\n");
            for (JavaConstructor c : ctors) {
                sb.append("- `").append(jc.getFullyQualifiedName())
                        .append(paramTypes(c)).append("`");
                String first = firstSentence(c.getComment());
                if (first != null) {
                    sb.append(" — ").append(first);
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        sb.append("> 提示: 需要某方法的完整文档或实现代码时，可使用 simplified_source_search 的 by_method 检索。\n");
        return sb.toString();
    }

    private static String paramTypes(JavaMethod m) {
        StringBuilder sb = new StringBuilder("(");
        var params = m.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i).getType().getValue());
            if (params.get(i).isVarArgs()) sb.append("...");
        }
        return sb.append(')').toString();
    }

    private static String paramTypes(JavaConstructor c) {
        StringBuilder sb = new StringBuilder("(");
        var params = c.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i).getType().getValue());
            if (params.get(i).isVarArgs()) sb.append("...");
        }
        return sb.append(')').toString();
    }

    /**
     * 注释首句
     */
    private static String firstSentence(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String cleaned = SourceDocExtractor.cleanInline(comment.trim());
        int cut = -1;
        for (int i = 0; i < cleaned.length(); i++) {
            if (cleaned.charAt(i) == '.') {
                // 句点后是空白/换行/结尾视为句末
                if (i + 1 >= cleaned.length() || Character.isWhitespace(cleaned.charAt(i + 1))) {
                    cut = i + 1;
                    break;
                }
            }
        }
        String s = cut > 0 ? cleaned.substring(0, cut) : cleaned;
        if (s.length() > 160) {
            s = s.substring(0, 160) + "...";
        }
        return s;
    }

    // ===================== 搜索操作 =====================

    /**
     * 按路径获取文档内容（.java/.html/简名均可，源码优先，HTML回退）
     */
    public ToolResponse<String> getDocByPath(String docPath) {
        if (docPath == null || docPath.trim().isEmpty()) {
            return ToolResponse.failure("文档路径不能为空");
        }
        docPath = docPath.trim();

        // 1) 源码注释文档
        if (sourceIndex != null) {
            String rel = docPath;
            // 去掉模块前缀（java.base/java/util/HashMap.html -> java/util/HashMap.html）
            String[] segs = rel.split("/");
            if (segs.length > 2 && segs[0].matches("(java|jdk)\\..+")) {
                rel = String.join("/", Arrays.copyOfRange(segs, 1, segs.length));
            }
            if (rel.endsWith(".html")) {
                rel = rel.substring(0, rel.length() - 5) + ".java";
            }
            rel = rel.replace('\\', '/');
            while (rel.startsWith("/") || rel.startsWith("./")) {
                rel = rel.substring(rel.startsWith("/") ? 1 : 2);
            }

            Path sourceFile = sourceIndex.find(rel);
            if (sourceFile == null && !rel.contains("/")) {
                // 简单类名：全索引检索
                List<Path> candidates = sourceIndex.findBySimpleName(rel);
                if (!candidates.isEmpty()) {
                    sourceFile = candidates.get(0);
                }
            }
            if (sourceFile != null) {
                String content = renderSourceDoc(sourceFile, rel.contains("/") ? null : rel.replace(".java", ""));
                if (content != null) {
                    return ToolResponse.success("## 文档: " + docPath + "\n\n" + content);
                }
            }
        }

        // 2) HTML回退
        if (apiRootPath != null) {
            try {
                File docFile = findExistingDocFile(docPath);
                if (docFile != null && docFile.exists()) {
                    String content = parseHtmlToMarkdown(docFile);
                    StringBuilder result = new StringBuilder();
                    result.append("## 文档: ").append(docPath).append(" (HTML回退)\n\n");
                    result.append(content);
                    return ToolResponse.success(result.toString());
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "HTML文档读取失败: " + e.getMessage());
            }
        }

        return ToolResponse.failure("文档不存在: " + docPath
                + "\n提示: 请使用 'list_files' 查看可用的包结构，或用 by_class 按类名检索");
    }

    /**
     * 按类名查找文档（源码注释优先，HTML回退）
     */
    public ToolResponse<String> searchDocByClassName(String className) {
        if (className == null || className.trim().isEmpty()) {
            return ToolResponse.failure("类名不能为空");
        }
        className = className.trim();

        // 1) 源码注释文档
        if (sourceIndex != null) {
            String[] parts = splitFqn(className);
            if (parts != null) {
                String rel = parts[0].replace('.', '/') + "/" + parts[1].replace('.', '$') + ".java";
                // 内部类文件与外部类同文件：取外部类路径
                String outerRel = parts[0].replace('.', '/') + "/"
                        + parts[1].split("\\.")[0] + ".java";
                Path sourceFile = sourceIndex.find(outerRel);
                if (sourceFile != null) {
                    String content = renderSourceDoc(sourceFile, parts[1]);
                    if (content != null) {
                        return ToolResponse.success("## 类文档: " + className + "\n\n" + content);
                    }
                }
            } else {
                // 简单类名：全索引检索（同文件名冲突时列出全部候选）
                List<Path> candidates = sourceIndex.findBySimpleName(className);
                if (!candidates.isEmpty()) {
                    if (candidates.size() == 1) {
                        String content = renderSourceDoc(candidates.get(0), className);
                        if (content != null) {
                            return ToolResponse.success("## 类文档: " + className + "\n\n" + content);
                        }
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("## 找到 ").append(candidates.size()).append(" 个同名类:\n\n");
                        for (Path p : candidates) {
                            sb.append("- ").append(p).append('\n');
                        }
                        sb.append("\n请使用完整类名重新检索。\n");
                        return ToolResponse.success(sb.toString());
                    }
                }
            }
        }

        // 2) HTML回退
        if (apiRootPath != null) {
            try {
                List<String> possiblePaths = generateClassDocPaths(className);
                for (String path : possiblePaths) {
                    File docFile = new File(apiRootPath.toString(), path);
                    if (docFile.exists()) {
                        String content = parseHtmlToMarkdown(docFile);
                        StringBuilder result = new StringBuilder();
                        result.append("## 类文档: ").append(className).append(" (HTML回退)\n\n");
                        result.append(content);
                        return ToolResponse.success(result.toString());
                    }
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "HTML类文档读取失败: " + e.getMessage());
            }
        }

        return ToolResponse.failure("未找到类 " + className + " 的文档");
    }

    /**
     * 列出包下的所有类文档（源码树 + 简要描述）
     */
    public ToolResponse<String> listPackageClasses(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return ToolResponse.failure("包名不能为空");
        }
        packageName = packageName.trim();
        String packagePath = packageName.replace('.', '/');

        // 1) 源码树
        if (sourceIndex != null) {
            List<Path> files = sourceIndex.listPackage(packagePath);
            if (!files.isEmpty()) {
                StringBuilder result = new StringBuilder();
                result.append("## 包 ").append(packageName).append(" 中的类 (源码树)\n\n");
                result.append("找到 ").append(files.size()).append(" 个类:\n\n");

                List<String> subPackages = sourceIndex.listSubPackages(packagePath);
                if (!subPackages.isEmpty()) {
                    result.append("### 子包\n");
                    for (String sub : subPackages) {
                        result.append("- ").append(packageName).append('.').append(sub).append('\n');
                    }
                    result.append('\n');
                }

                files.sort(Comparator.comparing(p -> p.getFileName().toString()));
                for (Path f : files) {
                    String cls = f.getFileName().toString().replace(".java", "");
                    result.append("### ").append(cls).append("\n");
                    result.append("**路径**: ").append(f).append("\n\n");
                    String brief = extractBriefInfoFromSource(f);
                    result.append(brief).append("\n\n");
                    if (result.length() > 12000) {
                        result.append("...结果过多，已截断");
                        break;
                    }
                }
                return ToolResponse.success(result.toString());
            }
        }

        // 2) HTML回退
        if (apiRootPath != null) {
            try {
                List<File> htmlFiles = findHtmlFilesInPackage(packagePath);
                if (!htmlFiles.isEmpty()) {
                    StringBuilder result = new StringBuilder();
                    result.append("## 包 ").append(packageName).append(" 中的类文档 (HTML回退)\n\n");
                    result.append("找到 ").append(htmlFiles.size()).append(" 个类:\n\n");
                    for (File htmlFile : htmlFiles) {
                        String cls = htmlFile.getName().replace(".html", "");
                        result.append("### ").append(cls).append("\n");
                        result.append("**路径**: ").append(getRelativePath(htmlFile)).append("\n\n");
                        result.append(extractBriefInfo(htmlFile)).append("\n\n");
                        if (result.length() > 10000) {
                            result.append("...结果过多，已截断");
                            break;
                        }
                    }
                    return ToolResponse.success(result.toString());
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "HTML包文档列出失败: " + e.getMessage());
            }
        }

        return ToolResponse.failure("包 " + packageName + " 中未找到文档");
    }

    /**
     * 列出可用的包结构（源码树优先）
     */
    public ToolResponse<String> listAvailableFiles(String basePath) {
        // 1) 源码树
        if (sourceIndex != null) {
            String packagePath = basePath == null || basePath.trim().isEmpty()
                    ? "" : basePath.trim().replace('.', '/');
            List<String> subs = sourceIndex.listSubPackages(packagePath);
            List<Path> files = sourceIndex.listPackage(packagePath);
            if (!subs.isEmpty() || !files.isEmpty()) {
                StringBuilder result = new StringBuilder();
                result.append("## 包结构: ").append(basePath != null && !basePath.isBlank() ? basePath : "根目录").append("\n\n");
                if (!subs.isEmpty()) {
                    result.append("### 子包\n\n");
                    subs.stream().limit(30).forEach(s ->
                            result.append("- **").append(s).append("**\n"));
                    result.append('\n');
                }
                if (!files.isEmpty()) {
                    result.append("### 类\n\n");
                    files.stream()
                         .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                         .limit(50)
                         .forEach(f -> result.append("- **").append(f.getFileName().toString().replace(".java", ""))
                                 .append("** (").append(f.getFileName()).append(")\n"));
                }
                return ToolResponse.success(result.toString());
            }
        }

        // 2) HTML回退
        if (apiRootPath != null) {
            try {
                Path searchPath = apiRootPath;
                if (basePath != null && !basePath.trim().isEmpty()) {
                    searchPath = apiRootPath.resolve(basePath.replace('.', '/'));
                }
                if (!Files.exists(searchPath) || !Files.isDirectory(searchPath)) {
                    return ToolResponse.failure("指定的路径不存在或不是目录: " + basePath);
                }

                StringBuilder result = new StringBuilder();
                result.append("## 目录结构 (HTML): ").append(basePath != null ? basePath : "根目录").append("\n\n");

                List<Path> directories = new ArrayList<>();
                List<Path> htmlFiles = new ArrayList<>();
                try (var stream = Files.list(searchPath)) {
                    stream.forEach(path -> {
                        if (Files.isDirectory(path)) {
                            directories.add(path);
                        } else if (path.toString().endsWith(".html")) {
                            htmlFiles.add(path);
                        }
                    });
                }
                if (!directories.isEmpty()) {
                    result.append("### 子目录\n\n");
                    directories.stream().sorted().limit(20)
                            .forEach(dir -> result.append("- **").append(dir.getFileName()).append("/**\n"));
                    result.append('\n');
                }
                if (!htmlFiles.isEmpty()) {
                    result.append("### 文档文件\n\n");
                    htmlFiles.stream().sorted().limit(30).forEach(file -> {
                        String fileName = file.getFileName().toString();
                        result.append("- **").append(fileName.replace(".html", "")).append("** (").append(fileName).append(")\n");
                    });
                }
                if (directories.isEmpty() && htmlFiles.isEmpty()) {
                    result.append("*该目录为空或不包含文档文件*");
                }
                return ToolResponse.success(result.toString());
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "HTML目录列出失败: " + e.getMessage());
            }
        }

        return ToolResponse.failure("无可用的文档源（源码树与HTML JavaDoc均未配置）");
    }

    // ===================== 私有辅助方法 =====================

    /**
     * 从源码文件提取简要信息（类声明 + 注释首句）
     */
    private String extractBriefInfoFromSource(Path sourceFile) {
        try {
            JavaProjectBuilder builder = new JavaProjectBuilder();
            builder.addSource(sourceFile.toFile());
            for (JavaClass jc : builder.getClasses()) {
                String head = jc.isInterface() ? "接口" : jc.isEnum() ? "枚举" : "类";
                String brief = firstSentence(jc.getComment());
                return brief != null ? "*" + head + "* " + brief : "*" + head + "*";
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.FINE, "源码简要信息提取失败: " + e.getMessage());
        }
        return "*无描述信息*";
    }

    /**
     * 查找HTML API文档根目录
     */
    private Path findApiRootDir() {
        if (javadocBasePath == null || javadocBasePath.isBlank()) {
            return null;
        }
        Path basePath = Paths.get(javadocBasePath);
        if (!Files.exists(basePath)) {
            return null;
        }

        // 检查 /docs/api 目录
        Path docsApiPath = basePath.resolve("docs").resolve("api");
        if (Files.exists(docsApiPath) && Files.isDirectory(docsApiPath)) {
            return docsApiPath;
        }

        // 检查 /api 目录
        Path apiPath = basePath.resolve("api");
        if (Files.exists(apiPath) && Files.isDirectory(apiPath)) {
            return apiPath;
        }

        // 使用基础目录
        if (Files.isDirectory(basePath)) {
            return basePath;
        }

        return null;
    }

    /**
     * 生成类文档的可能HTML路径（回退用）
     */
    private List<String> generateClassDocPaths(String className) {
        List<String> paths = new ArrayList<>();
        String simpleClassName = className.contains(".") ?
            className.substring(className.lastIndexOf('.') + 1) : className;

        if (className.contains(".")) {
            String packagePath = className.substring(0, className.lastIndexOf('.')).replace('.', '/');
            paths.add(packagePath + "/" + simpleClassName + ".html");

            if (className.startsWith("java.") || className.startsWith("javax.")) {
                String[] parts = className.split("\\.");
                if (parts.length >= 2) {
                    String moduleName = parts[0] + "." + parts[1];
                    paths.add(moduleName + "/" + packagePath + "/" + simpleClassName + ".html");
                }
            }
        }

        String[] commonPackages = {
            "java/lang", "java/util", "java/io", "java/nio",
            "java/net", "java/security", "java/text", "java/time",
            "java/util/concurrent", "java/util/stream", "java/util/function",
            "javax/swing", "javax/net", "javax/security"
        };
        for (String pkg : commonPackages) {
            paths.add(pkg + "/" + simpleClassName + ".html");
        }
        return paths;
    }

    /**
     * 查找包中的HTML文件（回退用）
     */
    private List<File> findHtmlFilesInPackage(String packagePath) {
        List<File> results = new ArrayList<>();
        File packageDir = new File(apiRootPath.toString(), packagePath);
        if (packageDir.exists() && packageDir.isDirectory()) {
            File[] htmlFiles = packageDir.listFiles((dir, name) ->
                name.endsWith(".html") && !name.equals("package-summary.html") && !name.equals("package-tree.html"));
            if (htmlFiles != null) {
                Arrays.sort(htmlFiles, Comparator.comparing(File::getName));
                results.addAll(Arrays.asList(htmlFiles));
            }
        }
        if (results.isEmpty()) {
            File[] moduleDirectories = apiRootPath.toFile().listFiles(File::isDirectory);
            if (moduleDirectories != null) {
                for (File moduleDir : moduleDirectories) {
                    File modulePackageDir = new File(moduleDir, packagePath);
                    if (modulePackageDir.exists() && modulePackageDir.isDirectory()) {
                        File[] htmlFiles = modulePackageDir.listFiles((dir, name) -> name.endsWith(".html"));
                        if (htmlFiles != null) {
                            results.addAll(Arrays.asList(htmlFiles));
                            if (results.size() >= 20) break;
                        }
                    }
                }
            }
        }
        return results;
    }

    /**
     * 尝试找到存在的HTML文档文件（考虑不同的路径变体）
     */
    private File findExistingDocFile(String docPath) {
        File directFile = new File(apiRootPath.toString(), docPath);
        if (directFile.exists()) {
            return directFile;
        }

        List<String> possiblePaths = new ArrayList<>();
        possiblePaths.add(docPath);
        if (!docPath.endsWith(".html") && !docPath.contains("/")) {
            possiblePaths.add(docPath + ".html");
        }
        if (docPath.startsWith("java/") || docPath.startsWith("javax/")) {
            String[] parts = docPath.split("/");
            if (parts.length >= 2) {
                String moduleName = parts[0] + "." + parts[1];
                possiblePaths.add(moduleName + "/" + docPath);
            }
        }
        for (String path : possiblePaths) {
            File file = new File(apiRootPath.toString(), path);
            if (file.exists()) {
                return file;
            }
        }

        File[] moduleDirectories = apiRootPath.toFile().listFiles(File::isDirectory);
        if (moduleDirectories != null) {
            for (File moduleDir : moduleDirectories) {
                if (moduleDir.getName().startsWith("java.") || moduleDir.getName().startsWith("javax.")) {
                    File moduleFile = new File(moduleDir, docPath);
                    if (moduleFile.exists()) {
                        return moduleFile;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 解析HTML为Markdown格式（回退用）
     */
    private String parseHtmlToMarkdown(File htmlFile) throws Exception {
        Document doc = Jsoup.parse(htmlFile, "UTF-8");
        doc.select("nav, .topNav, .bottomNav, .subNav, script, style, footer, .header").remove();

        StringBuilder markdown = new StringBuilder();
        Elements titles = doc.select("h1, h2, .title");
        if (!titles.isEmpty()) {
            markdown.append("# ").append(titles.first().text()).append("\n\n");
        }

        Elements classDesc = doc.select(".description .block, .classDescription, #class-description");
        for (int i = 0; i < Math.min(classDesc.size(), 2); i++) {
            String text = classDesc.get(i).text().trim();
            if (!text.isEmpty() && text.length() > 20) {
                markdown.append(text).append("\n\n");
            }
        }

        Elements methodSummary = doc.select(".summary table tr");
        if (methodSummary.size() > 1) {
            markdown.append("## 方法摘要\n\n");
            for (int i = 1; i < Math.min(methodSummary.size(), 11); i++) {
                String methodText = methodSummary.get(i).text().trim();
                if (!methodText.isEmpty() && methodText.length() > 10) {
                    String[] parts = methodText.split("\\s+", 3);
                    if (parts.length >= 2) {
                        markdown.append("- **").append(parts[1]).append("**");
                        if (parts.length > 2) {
                            String desc = parts[2];
                            if (desc.length() > 100) {
                                desc = desc.substring(0, 100) + "...";
                            }
                            markdown.append(": ").append(desc);
                        }
                        markdown.append("\n");
                    }
                }
            }
            markdown.append("\n");
        }

        if (markdown.length() < 200) {
            Elements mainContent = doc.select(".contentContainer, .details, main");
            if (!mainContent.isEmpty()) {
                String text = mainContent.first().text();
                if (text.length() > 1000) {
                    text = text.substring(0, 1000) + "...";
                }
                return text;
            }
        }
        return markdown.toString();
    }

    /**
     * 提取HTML文档的简要信息（回退用）
     */
    private String extractBriefInfo(File htmlFile) throws Exception {
        Document doc = Jsoup.parse(htmlFile, "UTF-8");
        Elements descriptions = doc.select(".description .block, .classDescription");
        if (!descriptions.isEmpty()) {
            String text = descriptions.first().text().trim();
            if (text.length() > 150) {
                text = text.substring(0, 150) + "...";
            }
            return text;
        }
        Elements typeInfo = doc.select(".header h2, .title");
        if (!typeInfo.isEmpty()) {
            return "*" + typeInfo.first().text() + "*";
        }
        return "*无描述信息*";
    }

    /**
     * 获取相对路径
     */
    private String getRelativePath(File file) {
        return apiRootPath.relativize(file.toPath()).toString();
    }
}
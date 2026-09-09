package edu.tju.ista.llm4test.utils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * JDK源码树索引：一次遍历建立 包相对路径 -> 文件 的映射，
 * 供源码定位/文档搜索共用（替代逐次find子进程与重复walk）。
 * <p>
 * key 形如 "java/lang/String.java"（相对 classes 根）。
 * 同名冲突时优先保留 share/classes（跨平台）版本。
 */
public final class SourceTreeIndex {

    private static final Map<String, SourceTreeIndex> CACHE = new ConcurrentHashMap<>();

    private final Map<String, Path> entries = new HashMap<>();
    private final Map<String, List<Path>> byFileName = new HashMap<>();
    private final Map<String, List<Path>> packageDirs = new HashMap<>();
    private volatile boolean built = false;
    private final Path root;
    /**
     * 是否 JDK 模块布局（{@code <module>/<platform>/classes/<包路径>}）。
     * 平铺布局（Maven 仓库的 src/main/java）下根目录本身就是包根。
     */
    private boolean jdkLayout = false;

    private SourceTreeIndex(Path root) {
        this.root = root;
    }

    /**
     * 获取（并缓存）指定源码根目录的索引
     */
    public static SourceTreeIndex getInstance(String sourceRoot) {
        if (sourceRoot == null || sourceRoot.isBlank()) {
            return null;
        }
        Path rootPath = Path.of(sourceRoot);
        if (!Files.isDirectory(rootPath)) {
            return null;
        }
        String key = rootPath.toAbsolutePath().toString();
        SourceTreeIndex idx = CACHE.get(key);
        if (idx == null) {
            idx = new SourceTreeIndex(rootPath.toAbsolutePath());
            SourceTreeIndex prev = CACHE.putIfAbsent(key, idx);
            if (prev != null) idx = prev;
        }
        idx.ensureBuilt();
        return idx;
    }

    private synchronized void ensureBuilt() {
        if (built) {
            return;
        }
        long start = System.currentTimeMillis();
        jdkLayout = detectJdkLayout(root);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return shouldSkip(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".java")) {
                        String rel = keyOf(file);
                        if (rel != null) {
                            String abs = file.toString();
                            String prev = entries.containsKey(rel) ? entries.get(rel).toString() : null;
                            if (prev == null || (!prev.contains("/share/") && abs.contains("/share/"))) {
                                entries.put(rel, file);
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            // 建立辅助索引
            for (Map.Entry<String, Path> e : entries.entrySet()) {
                String fileName = e.getValue().getFileName().toString();
                byFileName.computeIfAbsent(fileName, k -> new ArrayList<>()).add(e.getValue());
                String parent = parentKey(e.getKey());
                if (parent != null) {
                    packageDirs.computeIfAbsent(parent, k -> new ArrayList<>()).add(e.getValue());
                }
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "源码索引建立失败(" + root + "): " + e.getMessage());
        }
        built = true;
        LoggerUtil.logExec(Level.FINE,
                String.format("源码索引建立完成(%s): %d 个文件, 耗时 %d ms", root, entries.size(), System.currentTimeMillis() - start));
    }

    /**
     * 目录过滤。
     * <p>
     * 只跳过 {@code .git}；{@code test}/{@code doc}/{@code target}/{@code build}
     * 仅在 JDK 布局的**包根之外**（相对深度 ≤2，即模块/平台层）才跳过。
     * <p>
     * 历史实现按名字无条件跳过，两种布局都受害：JDK 侧
     * {@code jdk.compiler/share/classes/jdk/internal/shellsupport/doc} 与
     * {@code jdk.jfr/share/classes/jdk/jfr/internal/test} 是真实包，被整棵丢掉；
     * 平铺布局下任何名为 test/doc 的包同样会消失。
     */
    private boolean shouldSkip(Path dir) {
        Path fileName = dir.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        if (name.equals(".git")) {
            return true;
        }
        if (!jdkLayout) {
            // 平铺布局：根目录即包根，任何子目录都是包，不能按名字丢
            return false;
        }
        boolean nonApiDirName = name.equals("test") || name.equals("doc")
                || name.equals("target") || name.equals("build");
        return nonApiDirName && relativeDepth(dir) <= 2;
    }

    /** 相对索引根的层数（根本身为 0） */
    private int relativeDepth(Path dir) {
        try {
            return root.relativize(dir.toAbsolutePath().normalize()).getNameCount();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 探测 JDK 模块布局：根下存在 {@code <模块>/<平台>/classes} 这样的目录，
     * 且模块名带点（{@code java.base}、{@code jdk.compiler}、{@code jdk.jfr}）。
     * <p>
     * 「带点」是关键判据：Java 包名不允许含点，因此平铺布局的包根永远不会被
     * 误判成 JDK 布局。只要求「任意两层之下有 classes 目录」是不够的——平铺仓库里
     * {@code com/example/classes/} 完全合法，会把包名叫 classes 的情况又带回来。
     */
    private static boolean detectJdkLayout(Path root) {
        try (java.util.stream.Stream<Path> modules = Files.list(root)) {
            return modules
                    .filter(Files::isDirectory)
                    .filter(module -> isModuleName(module.getFileName()))
                    .anyMatch(module -> {
                        try (java.util.stream.Stream<Path> platforms = Files.list(module)) {
                            return platforms.filter(Files::isDirectory)
                                    .anyMatch(platform -> Files.isDirectory(platform.resolve("classes")));
                        } catch (IOException e) {
                            return false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }
    }

    /** JDK 模块目录名带点，普通包目录名不可能带点 */
    private static boolean isModuleName(Path name) {
        return name != null && name.toString().contains(".");
    }

    /**
     * 计算文件的索引键：
     * JDK布局取 {@code classes} 之后的包相对路径；平铺布局取相对根路径。
     * <p>
     * 不再用 {@code lastIndexOf("/classes/")}：包目录本身叫 {@code classes}
     * （如 {@code com/example/classes/Foo.java}）时会把包路径截错。改为只认
     * 相对深度 3 上的 {@code classes} 段，与 JDK 的实际布局一一对应。
     */
    private String keyOf(Path file) {
        try {
            Path rel = root.relativize(file.toAbsolutePath().normalize());
            if (jdkLayout && rel.getNameCount() > 3
                    && isModuleName(rel.getName(0))
                    && rel.getName(2).toString().equals("classes")) {
                return rel.subpath(3, rel.getNameCount()).toString().replace('\\', '/');
            }
            return rel.toString().replace('\\', '/');
        } catch (Exception e) {
            return null;
        }
    }

    private static String parentKey(String key) {
        int slash = key.lastIndexOf('/');
        return slash > 0 ? key.substring(0, slash) : null;
    }

    /**
     * 按包相对路径查找，如 "java/util/HashMap.java"
     */
    public Path find(String relPath) {
        if (relPath == null) {
            return null;
        }
        return entries.get(relPath.replace('\\', '/'));
    }

    /**
     * 按文件名（如 "HashMap.java" 或 "HashMap"）在全部包中查找
     */
    public List<Path> findBySimpleName(String name) {
        if (name == null || name.isBlank()) {
            return Collections.emptyList();
        }
        if (!name.endsWith(".java")) {
            name = name + ".java";
        }
        List<Path> list = byFileName.get(name);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 列出包目录下的源码文件，如 "java/util"
     */
    public List<Path> listPackage(String packagePath) {
        if (packagePath == null || packagePath.isBlank() || ".".equals(packagePath)) {
            return Collections.emptyList();
        }
        List<Path> list = packageDirs.get(packagePath.replace('.', '/'));
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 列出包目录下的子包名，如 "java/util" -> [concurrent, function, jar, ...]
     */
    public List<String> listSubPackages(String packagePath) {
        String base = packagePath == null || packagePath.isBlank() ? "" : packagePath.replace('.', '/') + "/";
        java.util.TreeSet<String> subs = new java.util.TreeSet<>();
        int baseLen = base.length();
        for (String key : packageDirs.keySet()) {
            if (base.isEmpty()) {
                int slash = key.indexOf('/');
                subs.add(slash > 0 ? key.substring(0, slash) : key);
            } else if (key.startsWith(base) && key.length() > baseLen) {
                String rest = key.substring(baseLen);
                if (!rest.contains("/")) {
                    subs.add(rest);
                } else {
                    subs.add(rest.substring(0, rest.indexOf('/')));
                }
            }
        }
        return new ArrayList<>(subs);
    }

    /**
     * 索引中源码文件总数
     */
    public int size() {
        return entries.size();
    }
}
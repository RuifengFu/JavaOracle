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
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("test") || name.equals("doc")
                            || name.equals("target") || name.equals("build")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
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
     * 计算文件的索引键：
     * JDK布局（src/&lt;module&gt;/&lt;platform&gt;/classes/&lt;包路径&gt;）取 classes 后的包相对路径；
     * 平铺布局（Maven等仓库的 src/main/java 根即包根）取相对根路径。
     */
    private String keyOf(Path file) {
        String s = file.toString();
        int idx = s.lastIndexOf("/classes/");
        if (idx >= 0) {
            return s.substring(idx + "/classes/".length());
        }
        // Windows风格兜底
        idx = s.lastIndexOf("\\classes\\");
        if (idx >= 0) {
            return s.substring(idx + "\\classes\\".length()).replace('\\', '/');
        }
        // 平铺布局：相对根目录
        try {
            String rel = root.relativize(file.toAbsolutePath().normalize()).toString();
            return rel.replace('\\', '/');
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
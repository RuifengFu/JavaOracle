package edu.tju.ista.llm4test.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SourceTreeIndex 布局兼容性测试：JDK布局（/classes/）与平铺布局（Maven src/main/java）。
 */
class SourceTreeIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void flatRootLayout() throws Exception {
        // 模拟 Maven 仓库：根即包根
        Path root = tempDir.resolve("main-java");
        Path calc = root.resolve("com/example/Calc.java");
        Path nested = root.resolve("com/example/util/Helper.java");
        Files.createDirectories(calc.getParent());
        Files.createDirectories(nested.getParent());
        Files.writeString(calc, "package com.example; public class Calc {}");
        Files.writeString(nested, "package com.example.util; public class Helper {}");

        SourceTreeIndex idx = SourceTreeIndex.getInstance(root.toString());
        assertNotNull(idx);
        assertEquals(calc.toAbsolutePath(), idx.find("com/example/Calc.java").toAbsolutePath());
        assertEquals(nested.toAbsolutePath(), idx.find("com/example/util/Helper.java").toAbsolutePath());

        // 按简单名反查
        List<Path> byName = idx.findBySimpleName("Calc");
        assertEquals(1, byName.size());

        // 包列表与子包
        assertEquals(1, idx.listPackage("com/example").size());
        List<String> subs = idx.listSubPackages("com/example");
        assertTrue(subs.contains("util"), "应含子包util: " + subs);
    }

    @Test
    void jdkClassesLayoutUnchanged() throws Exception {
        // 模拟 JDK 布局：键取 classes/ 之后
        Path root = tempDir.resolve("src");
        Path f = root.resolve("java.base/share/classes/java/lang/Foo.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "package java.lang; public class Foo {}");

        SourceTreeIndex idx = SourceTreeIndex.getInstance(root.toString());
        assertNotNull(idx);
        assertEquals(f.toAbsolutePath(), idx.find("java/lang/Foo.java").toAbsolutePath());
        assertNull(idx.find("java.base/share/classes/java/lang/Foo.java"),
                "JDK布局不应以根相对路径为键");
    }

    @Test
    void realPackagesNamedTestOrDocAreIndexed() throws Exception {
        // JDK 里真有这样的包：jdk.internal.shellsupport.doc、jdk.jfr.internal.test
        Path root = tempDir.resolve("src-with-doc-pkgs");
        Path docPkg = root.resolve("jdk.compiler/share/classes/jdk/internal/shellsupport/doc/JavadocHelper.java");
        Path testPkg = root.resolve("jdk.jfr/share/classes/jdk/jfr/internal/test/WhiteBox.java");
        // 模块层的非API目录，仍应跳过
        Path moduleTest = root.resolve("jdk.hotspot.agent/test/Sanity.java");
        for (Path f : List.of(docPkg, testPkg, moduleTest)) {
            Files.createDirectories(f.getParent());
            Files.writeString(f, "public class X {}");
        }

        SourceTreeIndex idx = SourceTreeIndex.getInstance(root.toString());

        assertNotNull(idx.find("jdk/internal/shellsupport/doc/JavadocHelper.java"),
                "包名恰好叫 doc 的真实包不该被整棵丢掉");
        assertNotNull(idx.find("jdk/jfr/internal/test/WhiteBox.java"),
                "包名恰好叫 test 的真实包不该被整棵丢掉");
        assertNull(idx.find("jdk.hotspot.agent/test/Sanity.java"),
                "模块层的 test 目录（包根之外）仍应跳过");
    }

    @Test
    void flatRootIndexesPackagesNamedTestAndClasses() throws Exception {
        // 平铺布局下根即包根：任何名字都是合法包名，不能按名字过滤
        Path root = tempDir.resolve("flat-java");
        Path testPkg = root.resolve("com/example/test/Fixtures.java");
        Path classesPkg = root.resolve("com/example/classes/Loader.java");
        for (Path f : List.of(testPkg, classesPkg)) {
            Files.createDirectories(f.getParent());
            Files.writeString(f, "public class X {}");
        }

        SourceTreeIndex idx = SourceTreeIndex.getInstance(root.toString());

        assertNotNull(idx.find("com/example/test/Fixtures.java"), "名为 test 的包应被索引");
        // 关键：历史实现用 lastIndexOf("/classes/") 截键，会把这个键错算成 Loader.java
        assertEquals(classesPkg.toAbsolutePath(),
                idx.find("com/example/classes/Loader.java").toAbsolutePath(),
                "包目录恰好叫 classes 时，键不能被截断");
        assertNull(idx.find("Loader.java"), "不应产生被截断的错误键");
    }

    @Test
    void shareClassesPreferredOverPlatform() throws Exception {
        Path root = tempDir.resolve("src");
        Path share = root.resolve("java.base/share/classes/java/lang/Dup.java");
        Path linux = root.resolve("java.base/linux/classes/java/lang/Dup.java");
        Files.createDirectories(share.getParent());
        Files.createDirectories(linux.getParent());
        Files.writeString(share, "package java.lang; public class Dup {}");
        Files.writeString(linux, "package java.lang; public class Dup {}");

        SourceTreeIndex idx = SourceTreeIndex.getInstance(root.toString());
        Path found = idx.find("java/lang/Dup.java");
        assertTrue(found.toString().contains("/share/"), "应优先share版本: " + found);
    }
}
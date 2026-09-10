package edu.tju.ista.llm4test.llm.tools;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimplifiedJavaDocSearchTool 源码注释文档检索测试。
 * 依赖本地 jdk17u-dev 源码树（无则跳过）。
 */
class SimplifiedJavaDocSearchToolTest {

    private static SimplifiedJavaDocSearchTool tool;
    private static boolean hasHtmlDocs;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(Files.exists(Path.of("jdk17u-dev/src/java.base/share/classes/java/lang/String.java")),
                "jdk17u-dev 源码树不存在，跳过");
        hasHtmlDocs = Files.exists(Path.of("JavaDoc/docs/api/java.base/java/lang/String.html"));
        tool = new SimplifiedJavaDocSearchTool("jdk17u-dev/src",
                hasHtmlDocs ? "JavaDoc/docs/api/java.base" : null);
    }

    @Test
    void byClassFullName() {
        ToolResponse<String> r = tool.searchDocByClassName("java.util.HashMap");
        assertTrue(r.isSuccess(), r.getFailMessage());
        String content = r.getResult();
        assertTrue(content.contains("java.util.HashMap"));
        assertTrue(content.contains("方法列表"), "应包含方法列表: " + preview(content));
        assertTrue(content.contains("put"), "应包含put方法");
        assertTrue(content.contains("hashing"), "类描述应包含关键内容: " + preview(content));
    }

    @Test
    void byClassSimpleName() {
        ToolResponse<String> r = tool.searchDocByClassName("ArrayList");
        assertTrue(r.isSuccess(), r.getFailMessage());
        assertTrue(r.getResult().contains("ArrayList"));
    }

    @Test
    void byClassInnerClass() {
        ToolResponse<String> r = tool.searchDocByClassName("java.util.Map.Entry");
        assertTrue(r.isSuccess(), r.getFailMessage());
        String content = r.getResult();
        // 应定位到内部类Entry而非外部Map
        assertTrue(content.contains("Map.Entry"), "应渲染内部类Entry: " + preview(content));
    }

    @Test
    void byPathJavaForm() {
        ToolResponse<String> r = tool.getDocByPath("java/util/HashMap.java");
        assertTrue(r.isSuccess(), r.getFailMessage());
        assertTrue(r.getResult().contains("java.util.HashMap"));
    }

    @Test
    void byPathLegacyHtmlForm() {
        // LLM习惯可能给.html路径，应自动转换到源码
        ToolResponse<String> r = tool.getDocByPath("java/lang/String.html");
        assertTrue(r.isSuccess(), r.getFailMessage());
        assertTrue(r.getResult().contains("java.lang.String"));
        assertTrue(r.getResult().contains("方法列表"));
    }

    @Test
    void byPathModuleNamePrefix() {
        ToolResponse<String> r = tool.getDocByPath("java.base/java/io/PrintStream.html");
        assertTrue(r.isSuccess(), r.getFailMessage());
        assertTrue(r.getResult().contains("java.io.PrintStream"));
    }

    @Test
    void byPackageListsClasses() {
        ToolResponse<String> r = tool.listPackageClasses("java.util.concurrent.locks");
        assertTrue(r.isSuccess(), r.getFailMessage());
        String content = r.getResult();
        assertTrue(content.contains("ReentrantLock"), "包内应列出ReentrantLock: " + preview(content));
        assertTrue(content.contains("子包") || content.contains("类"), "应包含结构信息");
    }

    @Test
    void listFilesShowsStructure() {
        ToolResponse<String> r = tool.listAvailableFiles("java.util");
        assertTrue(r.isSuccess(), r.getFailMessage());
        String content = r.getResult();
        assertTrue(content.contains("子包"), "应列出子包: " + preview(content));
        assertTrue(content.contains("concurrent"), "java.util下应有concurrent子包");
    }

    @Test
    void generatedClassFallsBackToHtml() {
        Assumptions.assumeTrue(hasHtmlDocs, "本地无HTML JavaDoc，跳过");
        ToolResponse<String> r = tool.searchDocByClassName("java.nio.ByteBuffer");
        assertTrue(r.isSuccess(), "模板生成类应回退HTML: " + (r.getFailMessage() == null ? "" : r.getFailMessage()));
        assertTrue(r.getResult().contains("ByteBuffer"));
    }

    private static String preview(String s) {
        return s.substring(0, Math.min(300, s.length()));
    }
}
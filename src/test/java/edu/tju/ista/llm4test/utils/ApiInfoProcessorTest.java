package edu.tju.ista.llm4test.utils;

import edu.tju.ista.llm4test.javaparser.APISignatureExtractor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiInfoProcessor 源码定位/匹配/文档提取能力测试。
 * 依赖本地存在 jdk17u-dev 源码树（无则跳过，不影响CI）。
 */
class ApiInfoProcessorTest {

    private static ApiInfoProcessor processor;
    private static boolean hasHtmlDocs;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(Files.exists(Path.of("jdk17u-dev/src/java.base/share/classes/java/lang/String.java")),
                "jdk17u-dev 源码树不存在，跳过");
        hasHtmlDocs = Files.exists(Path.of("JavaDoc/docs/api/java.base/java/lang/String.html"));
        processor = new ApiInfoProcessor(
                "JavaDoc/docs/api/java.base", "jdk17u-dev/src", "java.base/share/classes");
    }

    private File writeTest(String name, String code) throws Exception {
        Path f = tempDir.resolve(name);
        Files.writeString(f, code);
        return f.toFile();
    }

    @Test
    void innerClassMethodMatches() throws Exception {
        File f = writeTest("Inner.java", """
                import java.awt.geom.Path2D;
                public class Inner {
                    public static void main(String[] args) {
                        Path2D.Float p = new Path2D.Float();
                        p.moveTo(0.0, 0.0);
                        p.lineTo(1.0, 1.0);
                    }
                }
                """);
        Map<String, String> r = processor.getApiDocWithSource(f);
        String info = r.get("Path2D.Float.moveTo");
        assertNotNull(info, "内部类方法应能定位");
        assertTrue(info.contains("=== 方法源码 ==="), "内部类方法应匹配到源码: " + info);
        assertTrue(info.contains("Path2D.java") || info.contains("Path2D"), "应定位到Path2D源码文件");
    }

    @Test
    void varargsMethodMatches() throws Exception {
        File f = writeTest("Var.java", """
                public class Var {
                    public static void main(String[] args) {
                        String s = String.format("%d items", 42);
                        System.out.println(s);
                    }
                }
                """);
        Map<String, String> r = processor.getApiDocWithSource(f);
        String info = r.get("String.format");
        assertNotNull(info);
        assertTrue(info.contains("=== 方法源码 ==="), "varargs方法应匹配到源码: " + info);
    }

    @Test
    void constructorMatchesWithDoc() throws Exception {
        File f = writeTest("Ctor.java", """
                public class Ctor {
                    public static void main(String[] args) {
                        throw new RuntimeException("boom");
                    }
                }
                """);
        Map<String, String> r = processor.getApiDocWithSource(f);
        String info = r.get("RuntimeException.RuntimeException");
        assertNotNull(info);
        assertTrue(info.contains("=== 方法源码 ==="), "构造函数应匹配到源码: " + info);
        assertTrue(info.contains("=== API 文档 ==="), "构造函数应有文档: " + info);
    }

    @Test
    void methodDocExtractedFromSourceComment() throws Exception {
        File f = writeTest("Sub.java", """
                public class Sub {
                    public static void main(String[] args) {
                        String s = "hello world";
                        String sub = s.substring(0, 5);
                        System.out.println(sub);
                    }
                }
                """);
        Map<String, String> r = processor.getApiDocWithSource(f);
        String info = r.get("String.substring");
        assertNotNull(info);
        int docIdx = info.indexOf("=== API 文档 ===");
        assertTrue(docIdx >= 0, "方法应有文档: " + info);
        String doc = info.substring(docIdx);
        assertTrue(doc.contains("substring"), "文档应包含方法签名");
        assertTrue(doc.contains("@param"), "文档应包含参数说明: " + doc.substring(0, Math.min(400, doc.length())));
        assertTrue(doc.contains("@return") || doc.contains("@throws"), "文档应包含返回值/异常说明");
    }

    @Test
    void classDocContainsHierarchyAndDescription() throws Exception {
        File f = writeTest("Ps.java", """
                import java.io.PrintStream;
                public class Ps {
                    public static void main(String[] args) {
                        PrintStream out = System.out;
                        out.println("hi");
                    }
                }
                """);
        Map<String, String> r = processor.getApiDocWithSource(f);
        String doc = r.get("PrintStream_CLASS_DOC");
        assertNotNull(doc, "应有类文档");
        assertTrue(doc.contains("类: java.io.PrintStream"));
        assertTrue(doc.contains("继承: java.io.FilterOutputStream"), "应包含继承信息");
        assertTrue(doc.contains("PrintStream adds functionality"), "应包含类描述");
    }

    @Test
    void generatedBufferClassDocFallback() throws Exception {
        File f = writeTest("Buf.java", """
                import java.nio.ByteBuffer;
                public class Buf {
                    public static void main(String[] args) {
                        ByteBuffer b = ByteBuffer.allocate(10);
                        b.flip();
                    }
                }
                """);
        Map<String, String> r = processor.getApiDocWithSource(f);
        String info = r.get("ByteBuffer.allocate");
        assertNotNull(info);
        // ByteBuffer由构建模板生成，原始源码树中无.java文件；有HTML文档时应回退成功
        Assumptions.assumeTrue(hasHtmlDocs, "本地无HTML JavaDoc镜像，跳过文档回退断言");
        assertTrue(info.contains("=== API 文档 ==="), "模板生成类应回退到HTML文档: " + info);
    }

    @Test
    void signatureExtractionSkipsLocalMethods() throws Exception {
        File f = writeTest("Local.java", """
                public class Local {
                    private static int helper(int x) { return x + 1; }
                    public static void main(String[] args) {
                        System.out.println(helper(1));
                    }
                }
                """);
        Set<APISignatureExtractor.MethodSignature> sigs = extract(f);
        // 本地方法helper不应出现在签名中（包名为空被过滤）
        assertTrue(sigs.stream().noneMatch(s -> s.getMethodName().equals("helper")));
        assertTrue(sigs.stream().anyMatch(s -> s.getMethodName().equals("println")));
    }

    // 暴露extractor给测试用
    private Set<APISignatureExtractor.MethodSignature> extract(File f) {
        return new APISignatureExtractor().extractSignatures(f.getPath());
    }
}
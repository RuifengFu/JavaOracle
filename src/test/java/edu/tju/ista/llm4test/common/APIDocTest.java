package edu.tju.ista.llm4test.common;

import com.thoughtworks.qdox.model.JavaMethod;
import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.javaparser.JavaParser;
import edu.tju.ista.llm4test.utils.ApiInfoProcessor;
import edu.tju.ista.llm4test.utils.HtmlParser;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.junit.Assert;

public class APIDocTest {
    @Test
    public void getApiDocs() throws IOException {
        File file = new File("jdk17u-dev/test/jdk/java/net/CookieHandler/B6277794.java");
        List<JavaMethod> methods = JavaParser.fileToMethods(file);

        ApiInfoProcessor processor = ApiInfoProcessor.fromConfig();
        var docs = processor.processApiDocs(file);
        System.out.println(docs);
    }

    @Test
    public void ClassNoteTest() throws Exception {
        File file = new File("JavaDoc/docs/api/java.sql/java/sql/Timestamp.html");
        var doc = HtmlParser.getDocumentFromFile(file);
        var description = HtmlParser.getClassDescriptionText(doc);
        System.out.println(description);
    }

    @Test
    public void constructor() throws Exception {
        File file = new File("JavaDoc/docs/api/java.sql/java/sql/Timestamp.html");
        var doc = HtmlParser.getDocumentFromFile(file);
        var constructors = HtmlParser.getConstructorDetails(doc);
        System.out.println(constructors);
    }

    @Test
    public void getConstructorSourceTest() throws Exception {
        File file = new File("JavaTest/jdk/java/net/CookieHandler/B6644726.java");
        // 初始化ApiInfoProcessor，并提供JDK源码路径
        // baseDocPath 指向api文档根目录，jdkSourcePath 指向源码根目录
        ApiInfoProcessor processor = ApiInfoProcessor.fromConfig();
        
        var result = processor.getApiDocWithSource(file);

        // 打印结果方便调试
        result.forEach((key, value) -> System.out.println("Key: " + key + "\nValue:\n" + value));


    }

    @Test
    public void comprehensiveSourceExtractionTest() throws Exception {
        // 创建一个包含多种方法调用的临时 Java 文件
        String sourceCode = "package tmp;\n" +
                "import java.util.*;\n" +
                "import java.nio.file.Paths;\n" +
                "import java.text.MessageFormat;\n" +
                "import java.awt.font.NumericShaper;\n" +
                "public class ComprehensiveTest {\n" +
                "    public void test() throws Exception {\n" +
                "        Arrays.asList(\"a\", \"b\", \"c\");\n" +
                "        System.out.format(\"%s\", \"hello\");\n" +
                "        Paths.get(\"/tmp\", \"a\", \"b\");\n" +
                "        MessageFormat.format(\"{0}\", \"world\");\n" +
                "        Map.Entry<String, String> entry = null;\n" +
                "        NumericShaper.getContextualShaper(Set.of(NumericShaper.Range.EUROPEAN), NumericShaper.Range.EUROPEAN);\n" +
                "    }\n" +
                "}";

        File tempDir = new File("tmp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File tempFile = new File(tempDir, "ComprehensiveTest.java");
        try (java.io.FileWriter writer = new java.io.FileWriter(tempFile)) {
            writer.write(sourceCode);
        }

        try {
            ApiInfoProcessor processor = ApiInfoProcessor.fromConfig();
            var result = processor.getApiDocWithSource(tempFile);

            // 打印结果方便调试
            result.forEach((key, value) -> System.out.println("Key: " + key + "\nValue:\n" + value));

        } finally {
            // 清理临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }
            if (tempDir.exists()) {
                tempDir.delete();
            }
        }
    }

    @Test
    public void printStreamFormatTest() throws Exception {
        // 创建一个只包含 PrintStream.format 调用的临时 Java 文件
        String sourceCode = "package tmp;\n" +
                "public class PrintStreamTest {\n" +
                "    public void test() {\n" +
                "        System.out.format(\"Hello %s\", \"World\");\n" +
                "    }\n" +
                "}";

        File tempDir = new File("tmp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File tempFile = new File(tempDir, "PrintStreamTest.java");
        try (java.io.FileWriter writer = new java.io.FileWriter(tempFile)) {
            writer.write(sourceCode);
        }

        try {
            ApiInfoProcessor processor = ApiInfoProcessor.fromConfig();
            var result = processor.getApiDocWithSource(tempFile);

            // 打印结果方便调试
            result.forEach((key, value) -> System.out.println("Key: " + key + "\nValue:\n" + value));

            // 验证是否成功获取了 format 的源码
            boolean found = result.values().stream()
                    .anyMatch(v -> v.contains("public PrintStream format(String format, Object... args)"));

            Assert.assertTrue("未找到 PrintStream.format 的源码", found);

        } finally {
            // 清理临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }
            if (tempDir.exists()) {
                tempDir.delete();
            }
        }
    }

    @Test
    public void ReadTest() throws Exception {
        String code = "public class B6277794 {\n" +
                      "    public static void main(String[] args) throws Exception {\n" +
                      "        testCookieStore();\n" +
                      "    }\n" +
                      "\n" +
                      "    private static void testCookieStore() throws Exception {\n" +
                      "        CookieManager cm = new CookieManager();\n" +
                      "        CookieStore cs = cm.getCookieStore();\n" +
                      "\n" +
                      "        HttpCookie c1 = new HttpCookie(\"COOKIE1\", \"COOKIE1\");\n" +
                      "        HttpCookie c2 = new HttpCookie(\"COOKIE2\", \"COOKIE2\");\n" +
                      "        cs.add(new URI(\"http://www.sun.com/solaris\"), c1);\n" +
                      "        cs.add(new URI(\"http://www.sun.com/java\"), c2);\n" +
                      "\n" +
                      "        List<URI> uris = cs.getURIs();\n" +
                      "        if (uris.size() != 1 ||\n" +
                      "            !uris.get(0).equals(new URI(\"http://www.sun.com\"))) {\n" +
                      "            fail(\"CookieStore.getURIs() fail.\");\n" +
                      "        }\n" +
                      "    }\n" +
                      "\n" +
                      "    private static void fail(String msg) throws Exception {\n" +
                      "        throw new RuntimeException(msg);\n" +
                      "    }\n" +
                      "}";

        File tempDir = new File("tmp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File tempFile = new File(tempDir, "PrintStreamTest.java");
        try (java.io.FileWriter writer = new java.io.FileWriter(tempFile)) {
            writer.write(code);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            ApiInfoProcessor processor = ApiInfoProcessor.fromConfig();
            var result = processor.getApiDocWithSource(tempFile);

            // 打印结果方便调试
            result.forEach((key, value) -> System.out.println("Key: " + key + "\nValue:\n" + value));



        } finally {
            // 清理临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }
            if (tempDir.exists()) {
                tempDir.delete();
            }
        }
    }

    @Test
    public void BaselineTest() {
        String code = "/*\n" +
                      " * @test\n" +
                      " *\n" +
                      " * @bug 4117335 4432617\n" +
                      " * @modules jdk.localedata\n" +
                      " */\n" +
                      "\n" +
                      " import java.text.DateFormatSymbols;\n" +
                      " import java.util.Arrays;\n" +
                      " import java.util.Locale;\n" +
                      " \n" +
                      " public class bug4117335 {\n" +
                      " \n" +
                      "     public static void main(String[] args) throws Exception\n" +
                      "     {\n" +
                      "         DateFormatSymbols symbols = new DateFormatSymbols(Locale.JAPAN);\n" +
                      "         String[] eras = symbols.getEras();\n" +
                      "         System.out.println(\"BC = \" + eras[0]);\n" +
                      "         if (!eras[0].equals(bc)) {\n" +
                      "             System.out.println(\"*** Should have been \" + bc);\n" +
                      "             throw new Exception(\"Error in BC\");\n" +
                      "         }\n" +
                      "         System.out.println(\"AD = \" + eras[1]);\n" +
                      "         if (!eras[1].equals(ad)) {\n" +
                      "             System.out.println(\"*** Should have been \" + ad);\n" +
                      "             throw new Exception(\"Error in AD\");\n" +
                      "         }\n" +
                      "     }\n" +
                      "     static final String bc = \"\\u7d00\\u5143\\u524d\";\n" +
                      "     static final String ad = \"\\u897f\\u66a6\";\n" +
                      "     static final String jstLong = \"\\u65e5\\u672c\\u6a19\\u6e96\\u6642\";\n" +
                      "     static final String jstShort = \"JST\";\n" +
                      "     static final String jdtLong = \"\\u65e5\\u672c\\u590f\\u6642\\u9593\";\n" +
                      "     static final String jdtShort = \"JDT\";\n" +
                      "}\n" +
                      " ";
        File tempDir = new File("tmp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File tempFile = new File(tempDir, "bug4117335.java");
        try (java.io.FileWriter writer = new java.io.FileWriter(tempFile)) {
            writer.write(code);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            ApiInfoProcessor processor = ApiInfoProcessor.fromConfig();
            var result = processor.processApiDocs(tempFile);
            System.out.println(result);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            // 清理临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }
            if (tempDir.exists()) {
                tempDir.delete();
            }
        }

    }
}

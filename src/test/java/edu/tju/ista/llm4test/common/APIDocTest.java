package edu.tju.ista.llm4test.common;

import com.thoughtworks.qdox.model.JavaMethod;
import edu.tju.ista.llm4test.config.GlobalConfig;
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
        File file = new File("JavaTest/jdk/javax/security/auth/callback/Mutability.java");
        List<JavaMethod> methods = JavaParser.fileToMethods(file);

        ApiInfoProcessor processor = new ApiInfoProcessor("JavaDoc/docs/api/java.base");
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
        String code = "import java.io.*;\n" +
                      "\n" +
                      "public class ReadParams {\n" +
                      "\n" +
                      "    // Test closed stream behavior: read operations should throw IOException after close\n" +
                      "    public static void doTest2(InputStream in) throws Exception {\n" +
                      "        in.close();\n" +
                      "        byte[] buffer = new byte[10];\n" +
                      "        try {\n" +
                      "            int result = in.read(buffer, 0, 5);\n" +
                      "            throw new RuntimeException(in.getClass().getName() + \" Failed closed stream test - read() should throw IOException after close. Instead returned: \" + result);\n" +
                      "        } catch (IOException e) {\n" +
                      "            System.err.println(\"Successfully completed closed stream test on \" + in.getClass().getName());\n" +
                      "        } catch (Exception e) {\n" +
                      "            throw new RuntimeException(in.getClass().getName() + \" Unexpected exception type after close: \" + e.getClass().getName() + \": \" + e.getMessage());\n" +
                      "        }\n" +
                      "    }\n" +
                      "\n" +
                      "    public static void main(String args[]) throws Exception {\n" +
                      "        // Create a simple ObjectInputStream with minimal data\n" +
                      "        File tempFile = File.createTempFile(\"test\", \".obj\");\n" +
                      "        tempFile.deleteOnExit();\n" +
                      "        \n" +
                      "        FileOutputStream fos = new FileOutputStream(tempFile);\n" +
                      "        ObjectOutputStream oos = new ObjectOutputStream(fos);\n" +
                      "        oos.writeInt(12345);\n" +
                      "        oos.close();\n" +
                      "        \n" +
                      "        ObjectInputStream ois = new ObjectInputStream(new FileInputStream(tempFile));\n" +
                      "        doTest2(ois);\n" +
                      "        ois.close();\n" +
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
}

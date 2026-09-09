package edu.tju.ista.llm4test.llm.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * write_test_file 工具：文件名/包名从源码推导，只有一个 content 参数。
 * <p>
 * 锁住的三件事正是旧「最后一个 java 代码块」约定反复出问题的地方：
 * 拿不到代码、文件名与 public 类不一致、写到用例目录之外。
 */
class WriteTestFileToolTest {

    @TempDir
    Path caseDir;

    private ToolResponse<String> write(WriteTestFileTool tool, String content) {
        return tool.execute(Map.of(WriteTestFileTool.PARAM_CONTENT, content));
    }

    @Test
    void derivesFileNameFromDeclaredType() throws Exception {
        WriteTestFileTool tool = new WriteTestFileTool(caseDir);

        ToolResponse<String> response = write(tool, """
                package com.example;
                import org.junit.jupiter.api.Test;
                public class FooTest {
                    @Test
                    void works() {}
                }
                """);

        assertTrue(response.isSuccess(), String.valueOf(response.getResult()));
        Path written = caseDir.resolve("FooTest.java");
        assertTrue(Files.exists(written), "应按声明的类名落盘: " + written);
        assertTrue(Files.readString(written).contains("class FooTest"));
        assertEquals(java.util.List.of(written.toFile()), tool.getWrittenFiles());
    }

    @Test
    void severalCallsProduceMultiFileTestCase() {
        WriteTestFileTool tool = new WriteTestFileTool(caseDir);

        assertTrue(write(tool, """
                package com.example;
                import org.junit.jupiter.api.Test;
                public class MultiTest {
                    @Test
                    void probe() { new Fixture(); }
                }
                """).isSuccess());
        assertTrue(write(tool, """
                package com.example;
                public class Fixture {
                    public int value() { return 1; }
                }
                """).isSuccess());

        assertTrue(Files.exists(caseDir.resolve("MultiTest.java")));
        assertTrue(Files.exists(caseDir.resolve("Fixture.java")));
        assertEquals(2, tool.getWrittenFiles().size(), "两次调用即两文件用例");
    }

    @Test
    void reportsWhetherFileDeclaresTests() {
        WriteTestFileTool tool = new WriteTestFileTool(caseDir);

        String withTests = write(tool, """
                package com.example;
                import org.junit.jupiter.api.Test;
                public class HasTests { @Test void t() {} }
                """).getResult();
        String helperOnly = write(tool, """
                package com.example;
                public class Helper { int x; }
                """).getResult();

        assertTrue(withTests.contains("将被执行"), withTests);
        assertTrue(helperOnly.contains("仅参与编译"), helperOnly);
    }

    @Test
    void blankContentWritesNothing() {
        WriteTestFileTool tool = new WriteTestFileTool(caseDir);

        assertFalse(write(tool, "").isSuccess());
        assertFalse(write(tool, "   ").isSuccess());
        assertFalse(tool.wroteAnything(), "空 content 不应产生文件");
    }

    @Test
    void proseWithoutTypeDeclarationWritesNothing() {
        WriteTestFileTool tool = new WriteTestFileTool(caseDir);

        // 这正是历史上被原样写进 .java 的那种回复
        ToolResponse<String> response = write(tool,
                "No original test case was supplied. Please resend with both sections filled in.");

        assertFalse(response.isSuccess());
        assertFalse(tool.wroteAnything());
        assertEquals(0, caseDir.toFile().listFiles().length, "目录应保持为空");
    }

    @Test
    void missingContentParameterIsRejected() {
        WriteTestFileTool tool = new WriteTestFileTool(caseDir);

        assertFalse(tool.execute(Map.of()).isSuccess());
        assertFalse(tool.execute(null).isSuccess());
        assertFalse(tool.wroteAnything());
    }

    @Test
    void schemaHasSingleContentParameter() {
        WriteTestFileTool tool = new WriteTestFileTool(caseDir);

        // 没有 path 参数 —— 模型无法指定路径，也就无从写到用例目录之外
        assertEquals(java.util.List.of("content"), tool.getParameters());
        assertEquals("write_test_file", tool.getName());
        assertDoesNotThrow(tool::toMap);
        assertTrue(tool.getDescription().contains("several times"),
                "描述里要写明可多次调用（多文件用例）: " + tool.getDescription());
    }
}

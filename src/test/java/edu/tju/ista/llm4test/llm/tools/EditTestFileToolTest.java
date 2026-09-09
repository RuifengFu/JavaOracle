package edu.tju.ista.llm4test.llm.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * edit_test_file：精确匹配替换。
 * <p>
 * 关键性质是**要么唯一命中就改、要么一个字节都不改**——这正是不用 unified diff 的原因，
 * 行号算错导致的「部分应用」会把文件留在谁也没预期的中间态。
 */
class EditTestFileToolTest {

    @TempDir
    Path caseDir;

    private static final String ORIGINAL = """
            /*
             * Licensed to the Apache Software Foundation (ASF) ... copyright ownership.
             */
            package com.example;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            public class FooTest {

                @Test
                void existing() {
                    assertEquals(1, 1);
                }

                @Test
                void alsoExisting() {
                    assertEquals(1, 1);
                }
            }
            """;

    private EditTestFileTool tool;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(caseDir.resolve("FooTest.java"), ORIGINAL);
        tool = new EditTestFileTool(caseDir, "FooTest.java");
    }

    private ToolResponse<String> edit(String oldStr, String newStr) {
        Map<String, Object> args = new HashMap<>();
        args.put(EditTestFileTool.PARAM_OLD, oldStr);
        args.put(EditTestFileTool.PARAM_NEW, newStr);
        return tool.execute(args);
    }

    private String content() throws Exception {
        return Files.readString(caseDir.resolve("FooTest.java"));
    }

    @Test
    void insertsNewTestAndKeepsLicenseHeader() throws Exception {
        ToolResponse<String> response = edit("""
                    @Test
                    void alsoExisting() {
                        assertEquals(1, 1);
                    }
                }
                """, """
                    @Test
                    void alsoExisting() {
                        assertEquals(1, 1);
                    }

                    @Test
                    void addedByModel() {
                        assertEquals(2, 1 + 1);
                    }
                }
                """);

        assertTrue(response.isSuccess(), String.valueOf(response.getResult()));
        String updated = content();
        assertTrue(updated.contains("void addedByModel()"), "新用例应插入");
        assertTrue(updated.contains("void existing()"), "原用例应保留");
        assertTrue(updated.contains("Licensed to the Apache Software Foundation"),
                "未触及的 license 头必须原样保留——这是整份重写做不到的");
        assertEquals(1, tool.getEditedFiles().size());
    }

    @Test
    void missingOldStrChangesNothing() throws Exception {
        ToolResponse<String> response = edit("void doesNotExist() {}", "whatever");

        assertFalse(response.isSuccess());
        assertTrue(response.getFailMessage().contains("找不到"), response.getFailMessage());
        assertEquals(ORIGINAL, content(), "命中失败时文件必须一个字节都不变");
        assertFalse(tool.editedAnything());
    }

    @Test
    void ambiguousOldStrChangesNothing() throws Exception {
        // assertEquals(1, 1) 在两个用例里各出现一次，不足以唯一定位
        ToolResponse<String> response = edit("assertEquals(1, 1);", "assertEquals(2, 2);");

        assertFalse(response.isSuccess());
        assertTrue(response.getFailMessage().contains("出现"), response.getFailMessage());
        assertTrue(response.getFailMessage().contains("无法唯一定位"), response.getFailMessage());
        assertEquals(ORIGINAL, content(), "不唯一时文件必须一个字节都不变");
    }

    @Test
    void emptyNewStrDeletesMatchedText() throws Exception {
        assertTrue(edit("""
                    @Test
                    void alsoExisting() {
                        assertEquals(1, 1);
                    }
                """, "").isSuccess());

        assertFalse(content().contains("void alsoExisting()"), "new_str 为空即删除");
        assertTrue(content().contains("public class FooTest"), "其余部分保留");
    }

    @Test
    void identicalOldAndNewIsRejected() throws Exception {
        ToolResponse<String> response = edit("public class FooTest", "public class FooTest");

        assertFalse(response.isSuccess());
        assertEquals(ORIGINAL, content());
    }

    @Test
    void editsNamedFileInMultiFileCase() throws Exception {
        Files.writeString(caseDir.resolve("Helper.java"),
                "package com.example;\npublic class Helper { int v() { return 1; } }\n");

        Map<String, Object> args = new HashMap<>();
        args.put(EditTestFileTool.PARAM_FILE, "Helper.java");
        args.put(EditTestFileTool.PARAM_OLD, "return 1;");
        args.put(EditTestFileTool.PARAM_NEW, "return 42;");
        assertTrue(tool.execute(args).isSuccess());

        assertTrue(Files.readString(caseDir.resolve("Helper.java")).contains("return 42;"));
        assertEquals(ORIGINAL, content(), "主文件不该被动到");
    }

    @Test
    void refusesPathsOutsideCaseDirectory() throws Exception {
        Path outside = caseDir.getParent().resolve("Outside.java");
        Files.writeString(outside, "public class Outside {}\n");

        for (String name : java.util.List.of("../Outside.java", "/etc/passwd", "sub/dir/X.java")) {
            Map<String, Object> args = new HashMap<>();
            args.put(EditTestFileTool.PARAM_FILE, name);
            args.put(EditTestFileTool.PARAM_OLD, "public class Outside {}");
            args.put(EditTestFileTool.PARAM_NEW, "hacked");
            assertFalse(tool.execute(args).isSuccess(), "应拒绝: " + name);
        }
        assertTrue(Files.readString(outside).contains("public class Outside {}"),
                "用例目录之外的文件不能被改动");
    }

    @Test
    void missingParametersAreRejected() {
        assertFalse(tool.execute(Map.of()).isSuccess());
        assertFalse(tool.execute(null).isSuccess());
        assertFalse(edit("", "x").isSuccess());
        assertFalse(tool.editedAnything());
    }
}

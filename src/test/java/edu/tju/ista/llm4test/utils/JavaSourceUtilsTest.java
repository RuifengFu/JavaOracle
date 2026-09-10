package edu.tju.ista.llm4test.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java 源码轻量解析工具的行为锁定。
 * <p>
 * 这段逻辑自 {@code JtregExecuteTool} 迁出，被两个 harness 共用：
 * 推导出的类名决定源码落盘时的文件名，包名决定目录层级。推导错了，
 * javac 会以"类名与文件名不匹配"失败，而失败原因看起来像是 LLM 写错了代码。
 */
class JavaSourceUtilsTest {

    // ==================== extractMainClassName ====================

    @Test
    void extractsPublicClassName() {
        String code = """
                package java.lang;

                public class DecodeProbe {
                    public static void main(String[] args) {}
                }
                """;

        assertEquals("DecodeProbe", JavaSourceUtils.extractMainClassName(code));
    }

    @Test
    void extractsPublicFinalClassName() {
        String code = "public final class FinalProbe {\n}\n";

        assertEquals("FinalProbe", JavaSourceUtils.extractMainClassName(code),
                "public 与 class 之间的修饰符（final/abstract/strictfp）不应干扰类名提取");
    }

    @Test
    void extractsGenericClassNameWithoutTypeParameters() {
        String code = "public class Box<T> {\n    T value;\n}\n";

        assertEquals("Box", JavaSourceUtils.extractMainClassName(code),
                "泛型参数不属于类名，落盘文件名只能是 Box.java");
    }

    @Test
    void extractsDefaultAccessClassNameWhenNoPublicClass() {
        String code = """
                import java.util.List;

                class PackagePrivateProbe {
                    void probe() {}
                }
                """;

        assertEquals("PackagePrivateProbe", JavaSourceUtils.extractMainClassName(code),
                "无 public 类时退化到默认访问级别的类（jtreg 用例常见写法）");
    }

    @Test
    void returnsNullWhenNoClassDeclaration() {
        assertNull(JavaSourceUtils.extractMainClassName("int x = 1;\n"),
                "只有片段时返回 null，由调用侧兜底命名，不能抛异常");
    }

    @Test
    void returnsNullForNullOrBlankSource() {
        assertNull(JavaSourceUtils.extractMainClassName(null));
        assertNull(JavaSourceUtils.extractMainClassName(""));
        assertNull(JavaSourceUtils.extractMainClassName("   \n\t "));
    }

    @Test
    void ignoresClassNameInsideLineComment() {
        String code = """
                // 参考 class Decoy 的写法
                public class Real {
                }
                """;

        assertEquals("Real", JavaSourceUtils.extractMainClassName(code),
                "行注释里出现的 class 字样不能被当成真实类声明");
    }

    @Test
    void ignoresClassNameInsideBlockComment() {
        String code = """
                /*
                 * @test
                 * @summary 原型来自 class Decoy
                 */
                class Real {
                }
                """;

        assertEquals("Real", JavaSourceUtils.extractMainClassName(code),
                "jtreg 用例头部就是块注释，注释里的 class 字样必须先被剥掉");
    }

    // ==================== extractPackageName ====================

    @Test
    void extractsPackageName() {
        String code = "package com.example.deep.pkg;\n\npublic class Probe {}\n";

        assertEquals("com.example.deep.pkg", JavaSourceUtils.extractPackageName(code));
    }

    @Test
    void returnsNullWhenNoPackageDeclaration() {
        assertNull(JavaSourceUtils.extractPackageName("public class Probe {}\n"),
                "无包声明返回 null：调用侧据此决定是否建包目录");
    }

    @Test
    void returnsNullPackageForNullSource() {
        assertNull(JavaSourceUtils.extractPackageName(null));
    }

    // ==================== stripComments ====================

    @Test
    void stripsBlockAndLineCommentsButKeepsCode() {
        String code = """
                /* 头部块注释 class BlockDecoy */
                public class Real { // 尾部行注释 class LineDecoy
                    int x = 1;
                }
                """;

        String stripped = JavaSourceUtils.stripComments(code);

        assertFalse(stripped.contains("BlockDecoy"), "块注释应被移除，实际: " + stripped);
        assertFalse(stripped.contains("LineDecoy"), "行注释应被移除，实际: " + stripped);
        assertTrue(stripped.contains("public class Real"), "代码本身必须保留，实际: " + stripped);
        assertTrue(stripped.contains("int x = 1;"), "代码本身必须保留，实际: " + stripped);
    }

    @Test
    void stripsMultiLineBlockComment() {
        String code = """
                /*
                 * class Decoy
                 */
                class Real {}
                """;

        String stripped = JavaSourceUtils.stripComments(code);

        assertFalse(stripped.contains("Decoy"), "跨行块注释应整段移除，实际: " + stripped);
        assertTrue(stripped.contains("class Real"));
    }

    @Test
    void stripCommentsReturnsEmptyStringForNull() {
        assertEquals("", JavaSourceUtils.stripComments(null),
                "null 返回空串而非 null，后续 matcher 无需再判空");
    }
}

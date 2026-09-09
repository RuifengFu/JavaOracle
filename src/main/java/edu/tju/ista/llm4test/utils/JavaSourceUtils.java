package edu.tju.ista.llm4test.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 源码文本的轻量解析工具（与被测项目形态、测试框架均无关）。
 * <p>
 * 自 {@code JtregExecuteTool} 迁出：从源码推导主类名/包名这件事对 jtreg 与
 * JUnit console 是同一套逻辑，不该跟着某个 harness 的工具类走。
 */
public final class JavaSourceUtils {

    private static final Pattern PUBLIC_CLASS = Pattern.compile(
            "public\\s+(?:final|abstract|strictfp|\\s)*class\\s+(\\w+)(?:<[^>]*>)?",
            Pattern.DOTALL);

    private static final Pattern DEFAULT_CLASS = Pattern.compile(
            "(?<!private|protected|public)\\s+(?:final|abstract|strictfp|\\s)*class\\s+(\\w+)(?:<[^>]*>)?",
            Pattern.DOTALL);

    private static final Pattern PACKAGE = Pattern.compile("package\\s+([\\w.]+);");

    private JavaSourceUtils() {
    }

    /**
     * 推导主类名：优先 public 类，其次默认访问级别的类；都没有则返回 null。
     */
    public static String extractMainClassName(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return null;
        }
        String code = stripComments(sourceCode);

        Matcher matcher = PUBLIC_CLASS.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // 前置一个换行：DEFAULT_CLASS 需要 class 前有空白来承载“不被访问修饰符
        // 修饰”的否定回顾，否则位于输入第 0 位的 "class Foo {}" 匹配不到
        matcher = DEFAULT_CLASS.matcher("\n" + code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /** 提取包名；无包声明返回 null */
    public static String extractPackageName(String sourceCode) {
        if (sourceCode == null) {
            return null;
        }
        Matcher matcher = PACKAGE.matcher(sourceCode);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 去掉块注释与行注释，避免注释里的 class 字样干扰匹配 */
    public static String stripComments(String code) {
        if (code == null) {
            return "";
        }
        String noBlockComments = code.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        StringBuilder result = new StringBuilder();
        for (String line : noBlockComments.split("\n")) {
            int slashIndex = line.indexOf("//");
            result.append(slashIndex >= 0 ? line.substring(0, slashIndex) : line).append("\n");
        }
        return result.toString();
    }
}

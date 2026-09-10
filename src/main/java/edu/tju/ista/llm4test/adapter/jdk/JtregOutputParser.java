package edu.tju.ista.llm4test.adapter.jdk;

import edu.tju.ista.llm4test.adapter.HarnessOutputParser;

/**
 * jtreg 输出解析器。
 * 逻辑自 {@code TestOutput#parseJtregOutput} 原样迁移（含历史行为细节）：
 * - "STDERR:"/"STDOUT:" 区块捕获（区块外 ACTION/JavaTest Message 行结束 STDERR 捕获）
 * - 区块外仅保留 TEST:/TEST JDK:/TEST RESULT:/Test results:/ACTION:/Compilation failed 行
 * - stdout 为空时 testerr 直接保留原始 stderr（不过滤）
 * - 回退过滤：先 trim 再匹配前缀（"\tat " 前缀实际永不匹配，属历史怪癖，保持不变）
 */
public class JtregOutputParser implements HarnessOutputParser {

    @Override
    public ParsedOutput parse(String stdout, String stderr) {
        if (stdout == null || stdout.isEmpty()) {
            return new ParsedOutput("", stderr != null ? stderr : "");
        }

        StringBuilder testOutput = new StringBuilder();
        StringBuilder testError = new StringBuilder();

        // 使用保留尾部空元素的 split，避免丢失末尾空行
        String[] lines = stdout.split("\n", -1);
        boolean inStderr = false;
        boolean inStdout = false;

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();

            // 先处理区块切换标记（使用trimmed判断）
            if ("STDOUT:".equals(trimmed)) {
                inStdout = true;
                inStderr = false;
                continue;
            }
            if ("STDERR:".equals(trimmed)) {
                inStdout = false;
                inStderr = true;
                continue;
            }

            // 在 STDOUT/STDERR 区块内保留原始行（含空行与空白）
            if (inStdout) {
                testOutput.append(rawLine).append("\n");
                continue;
            }
            if (inStderr) {
                // 遇到新的段落标记则结束 STDERR 捕获
                if (trimmed.startsWith("ACTION:") || trimmed.startsWith("JavaTest Message:")) {
                    inStderr = false;
                    // 不 return，下面的通用逻辑会正常处理 ACTION 等行
                } else {
                    testError.append(rawLine).append("\n");
                    continue;
                }
            }

            // 区块外逻辑：此处可以使用 trimmed 并跳过无意义的空行与分隔线
            if (trimmed.contains("Compilation failed")) {
                testOutput.append(trimmed).append("\n");
                continue;
            }
            // 跳过空行和分隔线（仅限区块外）
            if (trimmed.isEmpty() || trimmed.startsWith("---")) {
                continue;
            }

            // 提取测试名称和JDK信息
            if (trimmed.startsWith("TEST:")) {
                testOutput.append(trimmed).append("\n");
                continue;
            }

            if (trimmed.startsWith("TEST JDK:")) {
                testOutput.append(trimmed).append("\n");
                continue;
            }

            // 提取最终测试结果
            if (trimmed.startsWith("TEST RESULT:")) {
                testOutput.append(trimmed).append("\n");
                continue;
            }

            // 提取测试结果摘要
            if (trimmed.startsWith("Test results:")) {
                testOutput.append(trimmed).append("\n");
                continue;
            }

            if (trimmed.startsWith("ACTION:")) {
                testOutput.append(trimmed).append("\n");
                continue;
            }
        }

        String testout = testOutput.toString().trim();
        String testerr = testError.toString().trim();

        // 如果没有解析到测试错误，但有stderr，则提取stderr中的关键错误信息
        if (testerr.isEmpty() && stderr != null && !stderr.isEmpty()) {
            String[] stderrLines = stderr.split("\n");
            StringBuilder stderrBuilder = new StringBuilder();

            for (String line : stderrLines) {
                line = line.trim();
                // 只保留异常、错误消息，跳过WARNING
                if (line.startsWith("java.lang.") || line.startsWith("\tat ") ||
                    line.startsWith("Exception") || line.startsWith("Error:") ||
                    line.startsWith("JavaTest Message:")) {
                    stderrBuilder.append(line).append("\n");
                }
            }

            testerr = stderrBuilder.toString().trim();
        }

        return new ParsedOutput(testout, testerr);
    }
}
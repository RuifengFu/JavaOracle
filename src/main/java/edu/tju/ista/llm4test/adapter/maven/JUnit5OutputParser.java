package edu.tju.ista.llm4test.adapter.maven;

import edu.tju.ista.llm4test.adapter.HarnessOutputParser;

/**
 * JUnit Platform Console Launcher 输出解析器。
 * <p>
 * 目标输出形如：
 * <pre>
 * ├─ JUnit Jupiter ✔
 * │  └─ CalcTest ✔
 * │     └─ fails() ✘ msg ==> expected: &lt;5&gt; but was: &lt;4&gt;
 * Failures (1):
 *   JUnit Jupiter:CalcTest:fails()
 *     MethodSource [...]
 *     =&gt; org.opentest4j.AssertionFailedError: ...
 * Test run finished after 43 ms
 * [         2 tests found           ]
 * [         1 tests failed          ]
 * </pre>
 * 解析规则：testout 保留测试树与汇总；testerr 保留 Failures 区块（含异常与栈）。
 */
public class JUnit5OutputParser implements HarnessOutputParser {

    @Override
    public ParsedOutput parse(String stdout, String stderr) {
        if (stdout == null || stdout.isEmpty()) {
            return new ParsedOutput("", stderr != null ? stderr : "");
        }

        StringBuilder testOutput = new StringBuilder();
        StringBuilder testError = new StringBuilder();
        boolean inFailures = false;

        for (String rawLine : stdout.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            String trimmed = line.trim();

            if (trimmed.startsWith("Failures (") || trimmed.startsWith("Aborted (") || trimmed.startsWith("Skipped (")) {
                inFailures = true;
                testError.append(trimmed).append("\n");
                continue;
            }
            if (trimmed.startsWith("Test run finished")) {
                inFailures = false;
                testOutput.append(trimmed).append("\n");
                continue;
            }

            if (inFailures) {
                // Failures区块内的行（测试ID/MethodSource/=>异常/缩进栈）
                if (!trimmed.isEmpty()) {
                    testError.append(line).append("\n");
                }
                continue;
            }

            // testout：测试树行、汇总行
            if (line.contains("├─") || line.contains("└─") || line.contains("╷")
                    || trimmed.startsWith("[")
                    || trimmed.startsWith("Thanks for using JUnit")) {
                testOutput.append(line).append("\n");
            }
        }

        // 汇总括号行之间的空行去除，保持紧凑
        return new ParsedOutput(testOutput.toString().trim(), testError.toString().trim());
    }
}
package edu.tju.ista.llm4test.execute;

public class TestOutput {

    public final String stdout;
    public final String stderr;
    public final int exitValue;
    public TestResultKind kind;

    // 解析后的测试输出
    private String testout;
    private String testerr;

    // 环境信息
    private String env;

    public TestOutput(String stdout, String stderr, int exitValue) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.exitValue = exitValue;
        parseJtregOutput();
    }

    /**
     * 解析JTreg输出，提取关键信息
     */
    private void parseJtregOutput() {
        if (stdout == null || stdout.isEmpty()) {
            this.testout = "";
            this.testerr = stderr != null ? stderr : "";
            return;
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

        this.testout = testOutput.toString().trim();
        this.testerr = testError.toString().trim();

        // 如果没有解析到测试错误，但有stderr，则提取stderr中的关键错误信息
        if (this.testerr.isEmpty() && stderr != null && !stderr.isEmpty()) {
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

            this.testerr = stderrBuilder.toString().trim();
        }
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public int getExitValue() {
        return exitValue;
    }

    public TestResultKind getKind() {
        return kind;
    }

    public void setKind(TestResultKind kind) {
        this.kind = kind;
    }

    /**
     * 获取解析后的测试输出
     */
    public String getTestout() {
        return testout;
    }

    /**
     * 获取解析后的测试错误
     */
    public String getTesterr() {
        return testerr;
    }

    /**
     * 获取简化的输出表示
     */
    public String getSimpleOutput() {
        StringBuilder sb = new StringBuilder();

        // 添加退出码和含义
        sb.append("exitValue: ").append(exitValue);
        switch (exitValue) {
            case 0:
                sb.append(" (SUCCESS)");
                break;
            case 2:
                sb.append(" (TEST_FAIL)");
                break;
            case 3:
                sb.append(" (ENV_ERROR)");
                break;
            case 124:
                sb.append(" (TIMEOUT)");
                break;
            default:
                sb.append(" (UNKNOWN)");
                break;
        }
        sb.append("\n");

        // 添加解析后的测试输出
        if (testout != null && !testout.isEmpty()) {
            sb.append("testout:\n").append(testout).append("\n");
        } else {
            sb.append("stdout: " + stdout);
        }

        // 添加解析后的测试错误
        if (testerr != null && !testerr.isEmpty()) {
            sb.append("testerr:\n").append(testerr).append("\n");
        }
        
        return sb.toString();
    }

    @Override
    public String toString() {
        return getSimpleOutput();
    }
}
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

        String[] lines = stdout.split("\n");
        boolean inStderr = false;
        boolean inStdout = false;

        for (String line : lines) {
            line = line.trim();

            // 跳过空行和分隔线
            if (line.isEmpty() || line.startsWith("---")) {
                continue;
            }

            // 提取测试名称和JDK信息
            if (line.startsWith("TEST:")) {
                testOutput.append(line).append("\n");
                continue;
            }

            if (line.startsWith("TEST JDK:")) {
                testOutput.append(line).append("\n");
                continue;
            }

            // 提取最终测试结果
            if (line.startsWith("TEST RESULT:")) {
                testOutput.append(line).append("\n");
                continue;
            }

            // 提取测试结果摘要
            if (line.startsWith("Test results:")) {
                testOutput.append(line).append("\n");
                continue;
            }

            if (line.equals("STDOUT:")) {
                inStdout = true;
                continue;
            }

            // 检测STDERR部分开始
            if (line.equals("STDERR:")) {
                inStdout = false;
                inStderr = true;
                continue;
            }

            // 提取STDERR中的内容（实际的程序输出错误）
            if (inStdout) {
                testOutput.append(line).append("\n");
            } else if (inStderr) {
                // 遇到下一个ACTION或其他关键字段时停止STDERR提取
                if (line.startsWith("ACTION:") || line.startsWith("JavaTest Message:")) {
                    inStderr = false;
                } else {
                    testError.append(line).append("\n");
                    continue;
                }
            }

//            // 提取JavaTest Message（测试框架的消息）
//            if (line.startsWith("JavaTest Message:")) {
//                testError.append(line).append("\n");
//                continue;
//            }


            if (line.startsWith("ACTION:")) {
                testOutput.append(line).append("\n");
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
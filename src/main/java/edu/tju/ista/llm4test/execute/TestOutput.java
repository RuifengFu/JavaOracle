package edu.tju.ista.llm4test.execute;

import edu.tju.ista.llm4test.adapter.jdk.JtregOutputParser;

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
        // 解析逻辑已抽取到 JtregOutputParser（后续harness适配的过渡期默认使用jtreg解析）
        JtregOutputParser.ParsedOutput parsed = new JtregOutputParser().parse(stdout, stderr);
        this.testout = parsed.testout();
        this.testerr = parsed.testerr();
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
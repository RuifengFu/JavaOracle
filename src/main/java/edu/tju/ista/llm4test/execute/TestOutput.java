package edu.tju.ista.llm4test.execute;

import edu.tju.ista.llm4test.adapter.AdapterRegistry;
import edu.tju.ista.llm4test.adapter.HarnessOutputParser;

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

    /**
     * 是否编译失败。
     * <p>
     * 判定文本与历史一致（{@link #getSimpleOutput()}），但结果在构造时算一次并
     * 作为显式字段暴露：原先由 {@code TestResult} 对 {@code toString()} 做子串
     * 匹配，依赖「testout 为空 → 回退原始 stdout」这个副作用才能保住标记。
     */
    private final boolean compilationFailed;

    /**
     * 用当前适配器的解析器构造。
     * <p>
     * 原先这里写死 {@code new JtregOutputParser()}，核心层因此反向依赖
     * {@code adapter.jdk}，且 Maven 模式下 core 路径会静默拿到 jtreg 解析。
     * 改由 {@link AdapterRegistry} 提供当前 harness 的解析器：JDK 模式取值不变。
     */
    public TestOutput(String stdout, String stderr, int exitValue) {
        this(stdout, stderr, exitValue, AdapterRegistry.get().outputParser());
    }

    /**
     * 指定harness解析器构造（供各ProjectAdapter使用）
     * @param parser 与执行框架匹配的输出解析器
     */
    public TestOutput(String stdout, String stderr, int exitValue,
                      HarnessOutputParser parser) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.exitValue = exitValue;
        var parsed = parser.parse(stdout, stderr);
        this.testout = parsed.testout();
        this.testerr = parsed.testerr();
        this.compilationFailed = getSimpleOutput().contains("Compilation failed");
    }

    /** 是否编译失败（构造时判定，见字段注释） */
    public boolean isCompilationFailed() {
        return compilationFailed;
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

        // 退出码含义由当前 harness 解释（这段文本会进 prompt）
        sb.append("exitValue: ").append(exitValue)
                .append(" (").append(AdapterRegistry.get().describeExitValue(exitValue)).append(")")
                .append("\n");

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
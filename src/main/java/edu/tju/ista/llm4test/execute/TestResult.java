package edu.tju.ista.llm4test.execute;

import edu.tju.ista.llm4test.adapter.AdapterRegistry;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class TestResult {

    private TestResultKind kind;
    private TestOutput compileResult;
    private TestOutput jtregResult;
    private HashMap<String, TestOutput> execResults = new HashMap<>();
    private String output;
    private boolean compilationFailed;

    public TestResult() {
        this.kind = TestResultKind.UNKNOWN;
        this.compileResult = null;
    }

    public TestResult(TestResultKind kind) {
        this.kind = kind;
        this.compileResult = null;
        this.execResults = null;
        this.compilationFailed = false;
    }


    public TestResult(TestOutput jtregResult) {
        this.jtregResult = jtregResult;
        // 分类码表由当前 harness 提供（JDK 模式取值与迁移前逐字一致）
        kind = AdapterRegistry.get().classifyExitValue(jtregResult.exitValue);
        compilationFailed = jtregResult.isCompilationFailed();
    }

    public boolean isDiff() {
        return kind == TestResultKind.DIFF;
    }
    public boolean isTestFail() {
        return kind == TestResultKind.TEST_FAIL;
    }
    public boolean isCompileSuccess() {
        return compileResult.exitValue == 0;
    }
    public boolean isCompileTimeout() {
        return compileResult.exitValue == 124;
    }

    public boolean isBug() {
        return kind == TestResultKind.VERIFIED_BUG;
    }

    /*
     * Returns true if the test case is a failure
     */
    public boolean isFail() {
        return switch (kind) {
            case COMPILE_FAIL, TEST_FAIL, DIFF, MAYBE_TEST_FAIL, VERIFIED_BUG, EXECUTE_ERROR, WRONG_FORMAT -> true;
            default -> false;
        };
    }

    public boolean isSuccess() {
        return kind.isSuccess();
    }

    @Deprecated
    public void setResult(String option, TestOutput output) {
        execResults.put(option, output);
        if (output.exitValue != 0) {
            if (output.exitValue == 124) {
                kind = TestResultKind.EXECUTE_TIMEOUT;
            } else if (output.exitValue == 3) {
                kind = TestResultKind.EXECUTE_ERROR;
            } else if (output.exitValue == 5) {
                kind = TestResultKind.WRONG_FORMAT;
            } else {
                kind = TestResultKind.TEST_FAIL;
            }
        } else {
            kind = TestResultKind.SUCCESS;
        }
    }

    public void mergeResults(Map<String, TestOutput> results) {
        execResults.putAll(results);
        List<Integer> list = execResults.values().stream().map(TestOutput::getExitValue).distinct().collect(Collectors.toList());
        if (list.isEmpty()) {
            // 空结果集：历史实现会在 list.get(0) 抛 IndexOutOfBounds
            kind = TestResultKind.EXECUTE_ERROR;
            LoggerUtil.logExec(Level.WARNING, "合并测试结果时没有任何执行输出，按执行错误处理");
        } else if (list.size() > 1) {
            kind = TestResultKind.DIFF;
        } else {
            this.jtregResult = results.values().stream().findFirst().orElse(null);
            kind = AdapterRegistry.get().classifyExitValue(list.get(0));
        }
        compilationFailed = results.values().stream().anyMatch(TestOutput::isCompilationFailed);
    }

    public void setKind(TestResultKind kind) {
        this.kind = kind;
    }
    public TestResultKind getKind() {
        return kind;
    }

    @Deprecated
    public TestOutput getCompileResult() {
        return compileResult;
    }

    @Deprecated
    public HashMap<String, TestOutput> getExecResults() {
        return execResults;
    }

    public TestOutput getJtregResult() {
        return jtregResult;
    }

    public boolean getCompilationFailed() {
        return compilationFailed;
    }

    public String toString() {
        if (!execResults.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, TestOutput> entry : execResults.entrySet()) {
                sb.append(entry.getKey()).append("Execute Result").append(": ").append(entry.getValue()).append("\n");
            }
            return sb.toString();
        }
        return String.valueOf(jtregResult);
    }

    /**
     * 设置测试结果的成功状态
     * @param success 是否成功
     */
    public void setSuccess(boolean success) {
        if (success) {
            kind = TestResultKind.SUCCESS;
        } else {
            // 如果已有更具体的失败类型，则保留
            if (kind == TestResultKind.SUCCESS || kind == TestResultKind.UNKNOWN) {
                kind = TestResultKind.TEST_FAIL;
            }
        }
    }

    /**
     * 设置测试输出结果
     * @param output 测试输出文本
     */
    public void setOutput(String output) {
        this.output = output;
    }

    /**
     * 获取测试输出结果
     * @return 测试输出文本
     */
    public String getOutput() {
        if (output != null) {
            return output;
        }
        // 如果没有设置输出，尝试从jtregResult或其他结果中获取
        if (jtregResult != null) {
            return jtregResult.toString();
        }
        return toString();
    }

}


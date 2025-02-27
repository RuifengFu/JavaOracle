package edu.tju.ista.llm4test.execute;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TestResult {

    private TestResultKind kind;
    private TestOutput compileResult;
    private TestOutput jtregResult;
    private HashMap<String, TestOutput> execResults = new HashMap<>();

    public TestResult() {
        this.kind = TestResultKind.UNKNOWN;
        this.compileResult = null;
    }

    public TestResult(TestResultKind kind) {
        this.kind = kind;
        this.compileResult = null;
        this.execResults = null;
    }


    public TestResult(TestOutput jtregResult) {
        this.jtregResult = jtregResult;
        if (jtregResult.exitValue != 0) {
            if (jtregResult.exitValue == 124) {
                kind = TestResultKind.EXECUTE_TIMEOUT;
            } else {
                kind = TestResultKind.TEST_FAIL;
            }
        } else {
            kind = TestResultKind.SUCCESS;
        }
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
            case COMPILE_FAIL, TEST_FAIL, DIFF, MAYBE_TEST_FAIL -> true;
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
            } else {
                kind = TestResultKind.TEST_FAIL;
            }
        } else {
            kind = TestResultKind.SUCCESS;
        }
    }

    public void mergeResults(Map<String, TestOutput> results) {
        execResults.putAll(results);
        List<Integer> list = execResults.values().stream().map(TestOutput::getExitValue).distinct().toList();
        if (list.size() > 1) {
            kind = TestResultKind.DIFF;
        } else {
            int value = list.get(0);
            if (value == 124) {
                kind = TestResultKind.EXECUTE_TIMEOUT;
            } else if (value != 0) {
                kind = TestResultKind.TEST_FAIL;
            } else {
                kind = TestResultKind.SUCCESS;
            }
        }
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

    @Deprecated
    public String toString() {
        return String.valueOf(jtregResult);
    }

}


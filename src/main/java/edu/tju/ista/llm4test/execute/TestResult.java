package edu.tju.ista.llm4test.execute;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TestResult {

    private TestResultKind kind;
    private TestOutput compileResult;
    private HashMap<String, TestOutput> execResults = new HashMap<>();

    public TestResult() {
        this.kind = TestResultKind.UNKNOWN;
    }

    public TestResult(TestResultKind kind) {
        this.kind = kind;
    }

    public TestResult(TestOutput compileResult) {
        this();
        this.compileResult = compileResult;
        if (compileResult.exitValue != 0) {
            if (compileResult.exitValue == 124) {
                kind = TestResultKind.COMPILE_TIMEOUT;
            } else {
                kind = TestResultKind.COMPILE_FAIL;
            }
        }
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

    public boolean isSuccess() {
        return kind.isSuccess();
    }

    public void setResult(String option, TestOutput output) {
        execResults.put(option, output);
        if (output.exitValue != 0) {
            if (output.exitValue == 124) {
                kind = TestResultKind.EXECUTE_TIMEOUT;
            } else {
                kind = TestResultKind.TEST_FAIL;
            }
        }
    }

    public void mergeResults(Map<String, TestOutput> results) {
        execResults.putAll(results);
        List<Integer> list = execResults.values().stream().map(TestOutput::getExitValue).distinct().collect(Collectors.toList());
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

    public TestResultKind getKind() {
        return kind;
    }

    public TestOutput getCompileResult() {
        return compileResult;
    }

    public HashMap<String, TestOutput> getExecResults() {
        return execResults;
    }



}


package edu.tju.ista.llm4test.execute;

public enum TestResultKind {
    SUCCESS,
    COMPILE_FAIL,
    TEST_FAIL,
    UNKNOWN,
    DIFF,
    COMPILE_TIMEOUT,
    EXECUTE_TIMEOUT,
    PASS,
    CRASH; // 我没有判断Crash的Oracle


    public boolean isSuccess() {
        return this == SUCCESS;
    }

}


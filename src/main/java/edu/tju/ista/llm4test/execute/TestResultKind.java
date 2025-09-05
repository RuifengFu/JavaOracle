package edu.tju.ista.llm4test.execute;

public enum TestResultKind {
    SUCCESS,
    COMPILE_FAIL,
    TEST_FAIL,
    UNKNOWN, // 未初始化
    DIFF,
    COMPILE_TIMEOUT,
    EXECUTE_TIMEOUT,
    VERIFIED_BUG,
    MAYBE_TEST_FAIL,
    PASS,
    EXECUTE_ERROR, // exitValue == 3
    WRONG_FORMAT; // exitValue == 5


    public boolean isSuccess() {
        return this == SUCCESS;
    }

}


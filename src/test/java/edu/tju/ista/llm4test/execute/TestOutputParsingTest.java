package edu.tju.ista.llm4test.execute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TestOutput 的 jtreg 输出解析回归测试。
 * 样本取自真实 jtreg 7.5.2 输出（通过/断言失败/编译失败）。
 */
class TestOutputParsingTest {

    /** 真实通过用例输出（节选自 jtreg -va 运行 java/lang/Integer/Unsigned.java） */
    private static final String PASS_STDOUT = """
            ACTION: build -- Passed. Build successful
            REASON: Named class compiled on demand
            TIME:   0.1 seconds
            messages:
            command: build Unsigned
            ACTION: main -- Passed. Execution successful
            STATUS:Passed.
            TEST RESULT: Passed. Execution successful
            Test results: passed: 1
            """;

    /** 真实失败用例输出（节选自断言抛 AssertionError 的用例） */
    private static final String FAIL_STDOUT = """
            ACTION: build -- Passed. Build successful
            ACTION: main -- Failed. Execution failed: `main' threw exception: java.lang.AssertionError: boom-expected-failure
            STDERR:
            java.lang.AssertionError: boom-expected-failure
            \tat FailTest.main(FailTest.java:6)
            ACTION: main -- Failed. Execution failed: `main' threw exception: java.lang.AssertionError: boom-expected-failure
            JavaTest Message: Test threw exception: java.lang.AssertionError: boom-expected-failure
            STATUS:Failed.`main' threw exception: java.lang.AssertionError: boom-expected-failure
            TEST RESULT: Failed. Execution failed: `main' threw exception: java.lang.AssertionError: boom-expected-failure
            Test results: failed: 1
            """;

    /** 编译失败输出 */
    private static final String COMPILE_FAIL_STDOUT = """
            ACTION: build -- Failed. Compilation failed: Compilation failed
            ACTION: compile -- Failed. Compilation failed: Compilation failed
            TEST RESULT: Failed. Compilation failed: Compilation failed
            Test results: failed: 1
            """;

    @Test
    void parsesPassOutput() {
        TestOutput out = new TestOutput(PASS_STDOUT, "", 0);
        assertTrue(out.getTestout().contains("TEST RESULT: Passed."), out.getTestout());
        assertTrue(out.getTestout().contains("Test results: passed: 1"));
        assertTrue(out.getTestout().contains("ACTION: main -- Passed."));
        assertEquals("", out.getTesterr());
    }

    @Test
    void parsesFailOutputWithStderrBlock() {
        TestOutput out = new TestOutput(FAIL_STDOUT, "", 2);
        // STDERR区块内的异常栈进入testerr
        assertTrue(out.getTesterr().contains("java.lang.AssertionError: boom-expected-failure"), out.getTesterr());
        assertTrue(out.getTesterr().contains("\tat FailTest.main"), out.getTesterr());
        // 区块外的关键行进入testout
        assertTrue(out.getTestout().contains("TEST RESULT: Failed."), out.getTestout());
        assertTrue(out.getTestout().contains("ACTION: main -- Failed."), out.getTestout());
    }

    @Test
    void parsesCompileFailOutput() {
        TestOutput out = new TestOutput(COMPILE_FAIL_STDOUT, "", 2);
        assertTrue(out.getTestout().contains("Compilation failed"));
        assertEquals(2, out.getExitValue());
    }

    @Test
    void stderrFallbackKeepsOnlyErrors() {
        // stdout非空且无STDERR区块时，从stderr提取关键错误行，跳过WARNING
        String stderr = """
                WARNING: some noise here
                java.lang.IllegalStateException: bad state
                \tat Foo.bar(Foo.java:10)
                WARNING: more noise
                """;
        TestOutput out = new TestOutput("TEST RESULT: Failed.\n", stderr, 2);
        assertTrue(out.getTesterr().contains("java.lang.IllegalStateException"));
        assertFalse(out.getTesterr().contains("WARNING"), out.getTesterr());
        // 行为锁定（历史怪癖）：fallback过滤先trim再匹配"\tat "前缀，栈行实际不会被保留
        assertFalse(out.getTesterr().contains("\tat Foo.bar"), out.getTesterr());
    }

    @Test
    void emptyStdoutDumpsWholeStderr() {
        // 行为锁定：stdout为空时testerr直接保留完整原始stderr（不过滤、不trim）
        String stderr = "WARNING: noise\njava.lang.Exception: boom\n";
        TestOutput out = new TestOutput("", stderr, 2);
        assertEquals(stderr, out.getTesterr());
    }

    @Test
    void emptyOutputSafe() {
        TestOutput out = new TestOutput(null, null, -1);
        assertEquals("", out.getTestout());
        assertEquals("", out.getTesterr());
        assertEquals(-1, out.getExitValue());
    }

    @Test
    void simpleOutputExitCodeLabels() {
        assertTrue(new TestOutput("", "", 0).getSimpleOutput().contains("(SUCCESS)"));
        assertTrue(new TestOutput("", "", 2).getSimpleOutput().contains("(TEST_FAIL)"));
        assertTrue(new TestOutput("", "", 124).getSimpleOutput().contains("(TIMEOUT)"));
        assertTrue(new TestOutput("", "", 99).getSimpleOutput().contains("(UNKNOWN)"));
    }
}
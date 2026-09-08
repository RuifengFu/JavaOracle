package edu.tju.ista.llm4test.execute;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TestResult 的结果分类回归测试（exitValue → TestResultKind 映射与多JDK合并逻辑）。
 */
class TestResultClassificationTest {

    @Test
    void singleOutputClassification() {
        assertEquals(TestResultKind.SUCCESS, new TestResult(new TestOutput("ok", "", 0)).getKind());
        assertEquals(TestResultKind.TEST_FAIL, new TestResult(new TestOutput("fail", "", 2)).getKind());
        assertEquals(TestResultKind.EXECUTE_TIMEOUT, new TestResult(new TestOutput("", "", 124)).getKind());
        assertEquals(TestResultKind.EXECUTE_ERROR, new TestResult(new TestOutput("", "", 3)).getKind());
        assertEquals(TestResultKind.WRONG_FORMAT, new TestResult(new TestOutput("", "", 5)).getKind());
    }

    @Test
    void compilationFailedFlag() {
        TestResult compileFail = new TestResult(new TestOutput("ACTION: build -- Failed. Compilation failed", "", 2));
        assertTrue(compileFail.getCompilationFailed());
        TestResult normalFail = new TestResult(new TestOutput("TEST RESULT: Failed.", "", 2));
        assertFalse(normalFail.getCompilationFailed());
    }

    @Test
    void mergeAllSuccess() {
        TestResult r = new TestResult();
        r.mergeResults(Map.of(
                "jdk-a", new TestOutput("ok", "", 0),
                "jdk-b", new TestOutput("ok", "", 0)));
        assertEquals(TestResultKind.SUCCESS, r.getKind());
        assertFalse(r.getCompilationFailed());
    }

    @Test
    void mergeAllSameFailure() {
        TestResult r = new TestResult();
        r.mergeResults(Map.of(
                "jdk-a", new TestOutput("fail", "", 2),
                "jdk-b", new TestOutput("fail", "", 2)));
        assertEquals(TestResultKind.TEST_FAIL, r.getKind());
    }

    @Test
    void mergeDifferentResultsIsDiff() {
        TestResult r = new TestResult();
        Map<String, TestOutput> results = new LinkedHashMap<>();
        results.put("jdk-a", new TestOutput("ok", "", 0));
        results.put("jdk-b", new TestOutput("fail", "", 2));
        r.mergeResults(results);
        assertEquals(TestResultKind.DIFF, r.getKind());
        assertTrue(r.isDiff());
        assertTrue(r.isFail(), "DIFF应计入失败");
    }

    @Test
    void mergeDetectsCompilationFailure() {
        TestResult r = new TestResult();
        r.mergeResults(Map.of(
                "jdk-a", new TestOutput("Compilation failed", "", 2),
                "jdk-b", new TestOutput("ok", "", 0)));
        assertTrue(r.getCompilationFailed());
        assertEquals(TestResultKind.DIFF, r.getKind());
    }

    @Test
    void mergeTimeoutClassification() {
        TestResult r = new TestResult();
        r.mergeResults(Map.of("jdk-a", new TestOutput("", "", 124)));
        assertEquals(TestResultKind.EXECUTE_TIMEOUT, r.getKind());
    }

    @Test
    void isFailSemantics() {
        assertTrue(new TestResult(TestResultKind.VERIFIED_BUG).isFail());
        assertTrue(new TestResult(TestResultKind.COMPILE_FAIL).isFail());
        assertFalse(new TestResult(TestResultKind.SUCCESS).isFail());
        assertTrue(new TestResult(TestResultKind.SUCCESS).isSuccess());
        assertTrue(new TestResult(TestResultKind.VERIFIED_BUG).isBug());
    }
}
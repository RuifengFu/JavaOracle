package edu.tju.ista.llm4test.execute;

import edu.tju.ista.llm4test.utils.ConcurrentEnumCounter;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.logging.Level;

public class TestStatistics {
    private final ConcurrentEnumCounter<TestResultKind> counter = new ConcurrentEnumCounter<>(TestResultKind.class);

    public void recordResult(TestResultKind result) {
        counter.increment(result);
    }

    public void logStatistics() {
        LoggerUtil.logResult(Level.INFO,
                "Success: " + counter.get(TestResultKind.SUCCESS) +
//                        "\nCompile Fail: " + counter.get(TestResultKind.COMPILE_FAIL) +
//                        "\nTest Fail: " + counter.get(TestResultKind.TEST_FAIL) +
                        "\nVerified Test Fail: " + counter.get(TestResultKind.VERIFIED_BUG) +
                        "\nUnverified Test Fail " + counter.get(TestResultKind.MAYBE_TEST_FAIL) +
                        "\nDiff: " + counter.get(TestResultKind.DIFF) +
                        "\nTimeout: " + (counter.get(TestResultKind.COMPILE_TIMEOUT) + counter.get(TestResultKind.EXECUTE_TIMEOUT)) +
                        "\nUnknown Fail: " + counter.get(TestResultKind.UNKNOWN) +
                        "\nPass: " + counter.get(TestResultKind.PASS));
    }
}
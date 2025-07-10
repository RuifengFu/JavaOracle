package edu.tju.ista.llm4test.execute;

import edu.tju.ista.llm4test.utils.ConcurrentEnumCounter;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class TestStatistics {
    private final ConcurrentEnumCounter<TestResultKind> counter = new ConcurrentEnumCounter<>(TestResultKind.class);

    private final AtomicInteger compilationFailed = new AtomicInteger(0);

    public void recordResult(TestResultKind result) {
        counter.increment(result);
    }

    public void recordResult(TestResult result) {
        if (result == null) {
            recordResult(TestResultKind.UNKNOWN);
            return;
        }
        if (result.getCompilationFailed()) {
            compilationFailed.incrementAndGet();
        }
        recordResult(result.getKind());
    }

    public void logStatistics() {
        LoggerUtil.logResult(Level.INFO,
                "Success: " + counter.get(TestResultKind.SUCCESS) +
//                        "\nCompile Fail: " + counter.get(TestResultKind.COMPILE_FAIL) +
//                        "\nTest Fail: " + counter.get(TestResultKind.TEST_FAIL) +
                        "\nVerified Test Fail: " + counter.get(TestResultKind.VERIFIED_BUG) +
                        "\nUnverified Test Fail " + counter.get(TestResultKind.MAYBE_TEST_FAIL) +
                        "\nCompile Failed: " + compilationFailed.get() +
                        "\nExecute Failed: " + (counter.get(TestResultKind.VERIFIED_BUG) + counter.get(TestResultKind.MAYBE_TEST_FAIL) - compilationFailed.get()) +
                        "\nDiff: " + counter.get(TestResultKind.DIFF) +
                        "\nTimeout: " + (counter.get(TestResultKind.COMPILE_TIMEOUT) + counter.get(TestResultKind.EXECUTE_TIMEOUT)) +
                        "\nUnknown Fail: " + counter.get(TestResultKind.UNKNOWN) +
                        "\nPass: " + counter.get(TestResultKind.PASS));
    }
}
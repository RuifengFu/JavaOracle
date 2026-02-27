package edu.tju.ista.llm4test.llm;

import org.junit.Test;

public class TokenUsageTrackerTest {

    @Test
    public void canRecordWithContext() {
        TokenUsageTracker tracker = TokenUsageTracker.getInstance();
        tracker.reset();

        tracker.setContext("caseA", TokenUsagePhase.GENERATION);
        tracker.record("model-a", new RequestTokenUsage(10, 20, 30, 4, 3, 1, 0));
        tracker.clearContext();

        tracker.setContext("caseA", TokenUsagePhase.VERIFICATION);
        tracker.record("model-a", new RequestTokenUsage(6, 4, 10, 0, 0, 0, 0));
        tracker.clearContext();

        tracker.logSummary(1);
    }
}

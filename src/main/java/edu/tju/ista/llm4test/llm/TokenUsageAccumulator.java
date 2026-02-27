package edu.tju.ista.llm4test.llm;

import java.util.concurrent.atomic.LongAdder;

public class TokenUsageAccumulator {
    private final LongAdder requestCount = new LongAdder();
    private final LongAdder promptTokens = new LongAdder();
    private final LongAdder completionTokens = new LongAdder();
    private final LongAdder totalTokens = new LongAdder();
    private final LongAdder promptCachedTokens = new LongAdder();
    private final LongAdder reasoningTokens = new LongAdder();
    private final LongAdder acceptedPredictionTokens = new LongAdder();
    private final LongAdder rejectedPredictionTokens = new LongAdder();

    public void add(RequestTokenUsage usage) {
        requestCount.increment();
        promptTokens.add(usage.promptTokens());
        completionTokens.add(usage.completionTokens());
        totalTokens.add(usage.totalTokens());
        promptCachedTokens.add(usage.promptCachedTokens());
        reasoningTokens.add(usage.reasoningTokens());
        acceptedPredictionTokens.add(usage.acceptedPredictionTokens());
        rejectedPredictionTokens.add(usage.rejectedPredictionTokens());
    }

    public long getRequestCount() { return requestCount.sum(); }
    public long getPromptTokens() { return promptTokens.sum(); }
    public long getCompletionTokens() { return completionTokens.sum(); }
    public long getTotalTokens() { return totalTokens.sum(); }
    public long getPromptCachedTokens() { return promptCachedTokens.sum(); }
    public long getReasoningTokens() { return reasoningTokens.sum(); }
    public long getAcceptedPredictionTokens() { return acceptedPredictionTokens.sum(); }
    public long getRejectedPredictionTokens() { return rejectedPredictionTokens.sum(); }

    public void reset() {
        requestCount.reset();
        promptTokens.reset();
        completionTokens.reset();
        totalTokens.reset();
        promptCachedTokens.reset();
        reasoningTokens.reset();
        acceptedPredictionTokens.reset();
        rejectedPredictionTokens.reset();
    }

    public TokenUsageAccumulator snapshotCopy() {
        TokenUsageAccumulator copy = new TokenUsageAccumulator();
        copy.requestCount.add(getRequestCount());
        copy.promptTokens.add(getPromptTokens());
        copy.completionTokens.add(getCompletionTokens());
        copy.totalTokens.add(getTotalTokens());
        copy.promptCachedTokens.add(getPromptCachedTokens());
        copy.reasoningTokens.add(getReasoningTokens());
        copy.acceptedPredictionTokens.add(getAcceptedPredictionTokens());
        copy.rejectedPredictionTokens.add(getRejectedPredictionTokens());
        return copy;
    }
}

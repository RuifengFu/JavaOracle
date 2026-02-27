package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class TokenUsageTracker {
    private static final TokenUsageTracker INSTANCE = new TokenUsageTracker();

    private final TokenUsageAccumulator total = new TokenUsageAccumulator();
    private final ConcurrentHashMap<String, TokenUsageAccumulator> byTestCase = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TokenUsagePhase, TokenUsageAccumulator> byPhase = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<TokenUsagePhase, TokenUsageAccumulator>> byTestCasePhase = new ConcurrentHashMap<>();

    private final ThreadLocal<RequestContext> contextHolder = new ThreadLocal<>();

    private record RequestContext(String testCaseName, TokenUsagePhase phase) {}

    public static TokenUsageTracker getInstance() {
        return INSTANCE;
    }

    private TokenUsageTracker() {
    }

    public void reset() {
        total.reset();
        byTestCase.clear();
        byPhase.clear();
        byTestCasePhase.clear();
        contextHolder.remove();
    }

    public void setContext(String testCaseName, TokenUsagePhase phase) {
        contextHolder.set(new RequestContext(testCaseName, phase == null ? TokenUsagePhase.OTHER : phase));
    }

    public void clearContext() {
        contextHolder.remove();
    }

    public void record(String model, RequestTokenUsage usage) {
        if (usage == null || usage.totalTokens() <= 0) {
            return;
        }
        total.add(usage);

        RequestContext context = contextHolder.get();
        String testCaseName = context != null ? context.testCaseName() : "GLOBAL";
        TokenUsagePhase phase = context != null ? context.phase() : TokenUsagePhase.OTHER;

        byTestCase.computeIfAbsent(testCaseName, key -> new TokenUsageAccumulator()).add(usage);
        byPhase.computeIfAbsent(phase, key -> new TokenUsageAccumulator()).add(usage);
        byTestCasePhase
                .computeIfAbsent(testCaseName, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(phase, key -> new TokenUsageAccumulator())
                .add(usage);

        LoggerUtil.logOpenAI(Level.INFO, String.format(
                "[TokenUsage] model=%s, testcase=%s, phase=%s, %s",
                model, testCaseName, phase, usage.toCompactString()));
    }

    public void logSummary(int expectedTestCaseCount) {
        long testCaseCount = Math.max(1, expectedTestCaseCount);
        long totalTokens = total.getTotalTokens();

        long generationAvg = averagePerTestCaseInPhase(TokenUsagePhase.GENERATION);
        long verificationAvg = averagePerTestCaseInPhase(TokenUsagePhase.VERIFICATION);

        LoggerUtil.logResult(Level.INFO,
                "Token Usage Summary:" +
                        "\nTotal tokens: " + totalTokens +
                        "\nTotal requests: " + total.getRequestCount() +
                        "\nPrompt tokens: " + total.getPromptTokens() +
                        "\nCompletion tokens: " + total.getCompletionTokens() +
                        "\nPrompt cache read tokens: " + total.getPromptCachedTokens() +
                        "\nReasoning tokens: " + total.getReasoningTokens() +
                        "\nAccepted prediction tokens: " + total.getAcceptedPredictionTokens() +
                        "\nRejected prediction tokens: " + total.getRejectedPredictionTokens() +
                        "\nAverage tokens per testcase: " + (totalTokens / testCaseCount) +
                        "\nAverage generation tokens per testcase: " + generationAvg +
                        "\nAverage verification tokens per testcase: " + verificationAvg
        );
    }

    private long averagePerTestCaseInPhase(TokenUsagePhase phase) {
        long count = 0;
        long sum = 0;
        for (Map<TokenUsagePhase, TokenUsageAccumulator> map : byTestCasePhase.values()) {
            TokenUsageAccumulator acc = map.get(phase);
            if (acc != null && acc.getTotalTokens() > 0) {
                count++;
                sum += acc.getTotalTokens();
            }
        }
        if (count == 0) {
            return 0;
        }
        return sum / count;
    }

    public Map<TokenUsagePhase, TokenUsageAccumulator> phaseSnapshot() {
        Map<TokenUsagePhase, TokenUsageAccumulator> snapshot = new EnumMap<>(TokenUsagePhase.class);
        for (Map.Entry<TokenUsagePhase, TokenUsageAccumulator> entry : byPhase.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().snapshotCopy());
        }
        return snapshot;
    }
}

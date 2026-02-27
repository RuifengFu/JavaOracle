package edu.tju.ista.llm4test.llm;

import java.util.Map;

public record RequestTokenUsage(
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long promptCachedTokens,
        long reasoningTokens,
        long acceptedPredictionTokens,
        long rejectedPredictionTokens
) {

    public static RequestTokenUsage empty() {
        return new RequestTokenUsage(0, 0, 0, 0, 0, 0, 0);
    }

    @SuppressWarnings("unchecked")
    public static RequestTokenUsage fromUsageObject(Object usageObj) {
        if (!(usageObj instanceof Map<?, ?> usageMapRaw)) {
            return RequestTokenUsage.empty();
        }

        Map<String, Object> usageMap = (Map<String, Object>) usageMapRaw;
        long promptTokens = toLong(usageMap.get("prompt_tokens"));
        long completionTokens = toLong(usageMap.get("completion_tokens"));
        long totalTokens = toLong(usageMap.get("total_tokens"));

        Map<String, Object> promptDetails = asStringObjectMap(usageMap.get("prompt_tokens_details"));
        long promptCachedTokens = toLong(promptDetails.get("cached_tokens"));

        Map<String, Object> completionDetails = asStringObjectMap(usageMap.get("completion_tokens_details"));
        long reasoningTokens = toLong(completionDetails.get("reasoning_tokens"));
        long acceptedPredictionTokens = toLong(completionDetails.get("accepted_prediction_tokens"));
        long rejectedPredictionTokens = toLong(completionDetails.get("rejected_prediction_tokens"));

        if (totalTokens == 0) {
            totalTokens = promptTokens + completionTokens;
        }

        return new RequestTokenUsage(
                promptTokens,
                completionTokens,
                totalTokens,
                promptCachedTokens,
                reasoningTokens,
                acceptedPredictionTokens,
                rejectedPredictionTokens
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringObjectMap(Object obj) {
        if (obj instanceof Map<?, ?> mapObj) {
            return (Map<String, Object>) mapObj;
        }
        return Map.of();
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str) {
            try {
                return Long.parseLong(str);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    public String toCompactString() {
        return String.format(
                "prompt=%d, completion=%d, total=%d, prompt_cache_read=%d, reasoning=%d, accepted_prediction=%d, rejected_prediction=%d",
                promptTokens, completionTokens, totalTokens, promptCachedTokens,
                reasoningTokens, acceptedPredictionTokens, rejectedPredictionTokens
        );
    }
}

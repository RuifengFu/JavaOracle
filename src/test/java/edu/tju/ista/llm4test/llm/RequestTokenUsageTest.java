package edu.tju.ista.llm4test.llm;

import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

public class RequestTokenUsageTest {

    @Test
    public void parseOpenAIUsageShape() {
        Map<String, Object> usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50,
                "total_tokens", 150,
                "prompt_tokens_details", Map.of("cached_tokens", 20),
                "completion_tokens_details", Map.of(
                        "reasoning_tokens", 15,
                        "accepted_prediction_tokens", 5,
                        "rejected_prediction_tokens", 2
                )
        );

        RequestTokenUsage tokenUsage = RequestTokenUsage.fromUsageObject(usage);

        Assert.assertEquals(100, tokenUsage.promptTokens());
        Assert.assertEquals(50, tokenUsage.completionTokens());
        Assert.assertEquals(150, tokenUsage.totalTokens());
        Assert.assertEquals(20, tokenUsage.promptCachedTokens());
        Assert.assertEquals(15, tokenUsage.reasoningTokens());
        Assert.assertEquals(5, tokenUsage.acceptedPredictionTokens());
        Assert.assertEquals(2, tokenUsage.rejectedPredictionTokens());
    }

    @Test
    public void fallbackTotalTokenCalculation() {
        Map<String, Object> usage = Map.of(
                "prompt_tokens", 7,
                "completion_tokens", 11
        );

        RequestTokenUsage tokenUsage = RequestTokenUsage.fromUsageObject(usage);

        Assert.assertEquals(18, tokenUsage.totalTokens());
    }
}

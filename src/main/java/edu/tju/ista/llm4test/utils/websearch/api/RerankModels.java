package edu.tju.ista.llm4test.utils.websearch.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 定义了Rerank API的请求和响应模型。
 */
public class RerankModels {

    // Rerank API Request
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RerankRequest {
        @JsonProperty("model")
        public String model;

        @JsonProperty("query")
        public String query;

        @JsonProperty("documents")
        public List<String> documents;

        @JsonProperty("top_n")
        public Integer topN;

        @JsonProperty("return_documents")
        public Boolean returnDocuments;
    }

    // Rerank API Response
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RerankApiResponse {
        @JsonProperty("code")
        public int code;

        @JsonProperty("log_id")
        public String logId;

        @JsonProperty("msg")
        public String msg;

        @JsonProperty("data")
        public RerankData data;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RerankData {
        @JsonProperty("model")
        public String model;

        @JsonProperty("results")
        public List<RerankResult> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RerankResult {
        @JsonProperty("index")
        public int index;

        @JsonProperty("relevance_score")
        public double relevanceScore;
    }
} 
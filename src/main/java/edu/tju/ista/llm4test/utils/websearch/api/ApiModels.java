package edu.tju.ista.llm4test.utils.websearch.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 定义了Web搜索和Rerank API的请求和响应模型。
 */
public class ApiModels {
    
    // API请求类
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearchRequest {
        @JsonProperty("query")
        public String query;
        
        @JsonProperty("freshness")
        public String freshness;
        
        @JsonProperty("summary")
        public Boolean summary;
        
        @JsonProperty("include")
        public String include;
        
        @JsonProperty("exclude")
        public String exclude;
        
        @JsonProperty("count")
        public Integer count;
    }
    
    // API响应类
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiResponse {
        @JsonProperty("code")
        public int code;
        
        @JsonProperty("log_id")
        public String logId;
        
        @JsonProperty("msg")
        public String msg;
        
        @JsonProperty("data")
        public SearchData data;
    }
} 
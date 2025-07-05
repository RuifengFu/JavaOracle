package edu.tju.ista.llm4test.utils.websearch.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 封装了搜索查询的上下文信息。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryContext {
    @JsonProperty("originalQuery")
    public String originalQuery;
} 
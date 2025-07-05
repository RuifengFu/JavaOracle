package edu.tju.ista.llm4test.utils.websearch.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 聚合了搜索API返回的各种数据，如网页和图片结果。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchData {
    @JsonProperty("_type")
    public String type;
    
    @JsonProperty("queryContext")
    public QueryContext queryContext;
    
    @JsonProperty("webPages")
    public WebPages webPages;
    
    @JsonProperty("images")
    public Images images;
} 
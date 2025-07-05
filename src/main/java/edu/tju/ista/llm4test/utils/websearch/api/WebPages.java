package edu.tju.ista.llm4test.utils.websearch.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 表示网页搜索结果的集合。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebPages {
    @JsonProperty("totalEstimatedMatches")
    public long totalEstimatedMatches;
    
    @JsonProperty("value")
    public List<WebPageValue> value;
    
    @JsonProperty("someResultsRemoved")
    public boolean someResultsRemoved;
} 
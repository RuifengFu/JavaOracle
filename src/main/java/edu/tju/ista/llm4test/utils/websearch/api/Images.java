package edu.tju.ista.llm4test.utils.websearch.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 表示图片搜索结果的集合。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Images {
    @JsonProperty("value")
    public List<ImageValue> value;
    
    @JsonProperty("isFamilyFriendly")
    public Boolean isFamilyFriendly;
} 
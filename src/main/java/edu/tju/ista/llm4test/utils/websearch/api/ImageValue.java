package edu.tju.ista.llm4test.utils.websearch.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示单个图片搜索结果的详细信息。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageValue {
    @JsonProperty("name")
    public String name;
    
    @JsonProperty("contentUrl")
    public String contentUrl;
    
    @JsonProperty("thumbnailUrl")
    public String thumbnailUrl;
    
    @JsonProperty("hostPageUrl")
    public String hostPageUrl;
    
    @JsonProperty("hostPageDisplayUrl")
    public String hostPageDisplayUrl;
    
    @JsonProperty("width")
    public int width;
    
    @JsonProperty("height")
    public int height;
    
    @JsonProperty("datePublished")
    public String datePublished;
} 
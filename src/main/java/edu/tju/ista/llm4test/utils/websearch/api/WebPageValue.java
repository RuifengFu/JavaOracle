package edu.tju.ista.llm4test.utils.websearch.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示单个网页搜索结果的详细信息。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebPageValue {
    @JsonProperty("id")
    public String id;
    
    @JsonProperty("name")
    public String name;
    
    @JsonProperty("url")
    public String url;
    
    @JsonProperty("displayUrl")
    public String displayUrl;
    
    @JsonProperty("snippet")
    public String snippet;
    
    @JsonProperty("summary")
    public String summary;
    
    @JsonProperty("siteName")
    public String siteName;
    
    @JsonProperty("siteIcon")
    public String siteIcon;
    
    @JsonProperty("datePublished")
    public String datePublished;
    
    @JsonProperty("dateLastCrawled")
    public String dateLastCrawled;
} 
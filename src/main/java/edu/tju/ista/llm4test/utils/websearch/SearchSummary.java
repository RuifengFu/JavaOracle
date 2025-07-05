package edu.tju.ista.llm4test.utils.websearch;

import edu.tju.ista.llm4test.utils.websearch.api.SearchData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供搜索结果的统计和摘要信息。
 */
public class SearchSummary {
    private final String originalQuery;
    private final long totalEstimatedMatches;
    private final int actualResults;
    private final List<String> topDomains;
    private final String combinedSnippets;
    private final boolean someResultsRemoved;
    
    public SearchSummary(SearchData data, List<SearchResult> results) {
        this.originalQuery = data.queryContext != null ? data.queryContext.originalQuery : "";
        this.totalEstimatedMatches = data.webPages != null ? data.webPages.totalEstimatedMatches : 0;
        this.actualResults = results.size();
        this.topDomains = extractTopDomains(results);
        this.combinedSnippets = combineSnippets(results);
        this.someResultsRemoved = data.webPages != null ? data.webPages.someResultsRemoved : false;
    }
    
    private List<String> extractTopDomains(List<SearchResult> results) {
        Map<String, Integer> domainCount = new HashMap<>();
        
        for (SearchResult result : results) {
            try {
                String domain = extractDomain(result.getUrl());
                domainCount.put(domain, domainCount.getOrDefault(domain, 0) + 1);
            } catch (Exception e) {
                // 忽略URL解析错误
            }
        }
        
        return domainCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
    
    private String extractDomain(String url) {
        if (url != null && url.startsWith("http")) {
            String[] parts = url.split("/");
            if (parts.length >= 3) {
                return parts[2];
            }
        }
        return url != null ? url.split("/")[0] : "";
    }
    
    private String combineSnippets(List<SearchResult> results) {
        StringBuilder combined = new StringBuilder();
        for (SearchResult result : results) {
            if (result.getSnippet() != null && !result.getSnippet().isEmpty()) {
                combined.append(result.getSnippet()).append(" ");
            }
        }
        return combined.toString().trim();
    }
    
    // Getters
    public String getOriginalQuery() { return originalQuery; }
    public long getTotalEstimatedMatches() { return totalEstimatedMatches; }
    public int getActualResults() { return actualResults; }
    public List<String> getTopDomains() { return topDomains; }
    public String getCombinedSnippets() { return combinedSnippets; }
    public boolean isSomeResultsRemoved() { return someResultsRemoved; }
    
    @Override
    public String toString() {
        return String.format("SearchSummary{query='%s', totalMatches=%d, actualResults=%d, topDomains=%s}", 
                           originalQuery, totalEstimatedMatches, actualResults, topDomains);
    }
} 
package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.utils.WebSearch;

import java.util.ArrayList;
import java.util.List;

public class BoChaSearchTool {

    private final String API_KEY = System.getenv("BOCHA_API_KEY");
    private WebSearch.SearchConfig config = new WebSearch.SearchConfig().setApiKey(API_KEY).setSummary(true);
    private WebSearch webSearch = new WebSearch(config);
    public List<WebSearch.SearchResult> search(String query, int maxResults) {
        if (API_KEY == null || API_KEY.isEmpty()) {
            throw new IllegalStateException("BOCHA_API_KEY environment variable is not set.");
        }
        // 使用WebSearch类进行搜索
        return webSearch.search(query);
    }

    public List<WebSearch.SearchResult> reRankResult(List<WebSearch.SearchResult> results, String context) {
        return webSearch.rerank(context, results);
    }
}

package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.utils.websearch.SearchConfig;
import edu.tju.ista.llm4test.utils.websearch.SearchResult;
import edu.tju.ista.llm4test.utils.websearch.WebSearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ReRankerTool implements Tool<List<SearchResult>> {

    private List<SearchResult> searchResults;
    private WebSearch webSearch = new WebSearch();

    public ReRankerTool(List<SearchResult> results) {
        this.searchResults = results;
    }

    @Override
    public String getName() {
        return "search result reranker, based query sort by relevance";
    }

    @Override
    public String getDescription() {
        return "the results are sorted by relevance to the query";
    }

    @Override
    public List<String> getParameters() {
        return List.of("query");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of("query", "The search query to find relevant information");
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of("query", "string");
    }

    @Override
    public ToolResponse<List<SearchResult>> execute(Map<String, Object> args) {
        if (args.containsKey("query")) {
            String query = (String) args.get("query");
            List<SearchResult> results = webSearch.rerank(query, this.searchResults);
            return ToolResponse.success(results);
        }
        return ToolResponse.failure("query is required");
    }

    public List<SearchResult> getSearchResults() {
        return searchResults;
    }

    public void setSearchResults(List<SearchResult> searchResults) {
        this.searchResults = searchResults;
    }

    public void setSearchResult(List<String> results) {
        List<SearchResult> searchResults = new ArrayList<>();
        for (String s: results) {
            var result = new SearchResult(s);
            searchResults.add(result);
        }
        setSearchResults(searchResults);
    }

}

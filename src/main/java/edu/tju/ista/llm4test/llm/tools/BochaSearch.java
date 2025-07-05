package edu.tju.ista.llm4test.llm.tools;

import edu.tju.ista.llm4test.utils.websearch.WebSearch;
import edu.tju.ista.llm4test.utils.websearch.SearchConfig;
import edu.tju.ista.llm4test.utils.websearch.SearchResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BochaSearch implements Tool<String> {

    private final WebSearch webSearch;

    public BochaSearch() {
        String apiKey = System.getenv("BOCHA_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("BOCHA_API_KEY environment variable not set.");
        }
        SearchConfig config = new SearchConfig().setApiKey(apiKey).setSummary(true);
        this.webSearch = new WebSearch(config);
    }

    @Override
    public String getName() {
        return "bocha_search";
    }

    @Override
    public String getDescription() {
        return "Performs a web search using the Bocha search engine, which is a powerful meta-search API. " +
               "It returns a list of search results including title, URL, and snippet.";
    }

    @Override
    public List<String> getParameters() {
        return List.of("query", "max_results");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                "query", "The search query to find relevant information.",
                "max_results", "The maximum number of search results to return."
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
                "query", "string",
                "max_results", "integer"
        );
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        if (args == null || !args.containsKey("query")) {
            return ToolResponse.failure("参数错误，必须提供 query");
        }
        String query = (String) args.get("query");
        int maxResults = args.containsKey("max_results") ? (int) args.get("max_results") : 10;

        try {
            List<SearchResult> results = webSearch.search(query, maxResults);
            if (results == null || results.isEmpty()) {
                return ToolResponse.success("No search results found.");
            }
            String formattedResults = results.stream()
                    .map(SearchResult::toString)
                    .collect(Collectors.joining("\n\n"));
            return ToolResponse.success(formattedResults);
        } catch (Exception e) {
            return ToolResponse.failure("Failed to execute search: " + e.getMessage());
        }
    }
}

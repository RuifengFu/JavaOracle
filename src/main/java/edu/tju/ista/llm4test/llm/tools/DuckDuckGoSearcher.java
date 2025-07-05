package edu.tju.ista.llm4test.llm.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;


public class DuckDuckGoSearcher implements Tool<String> {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
    private static final int MAX_RETRIES = 3;
    private static final int CONNECTION_TIMEOUT = 15000; // 15秒
    private static final Random RANDOM = new Random();

    // 广告相关的关键词或域名
    private static final String[] AD_KEYWORDS = {
            "adservice", "adclick", "ads", "ad_domain", "ad_provider", "ad_type", "adgroup"
    };

    @Override
    public String getName() {
        return "duckduckgo_search";
    }

    @Override
    public String getDescription() {
        return "Performs a web search using the DuckDuckGo search engine and returns a list of results. " +
               "This tool is privacy-focused and provides title, URL, and a snippet for each search result.";
    }

    @Override
    public List<String> getParameters() {
        return List.of("query", "max_results");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                "query", "The search query string.",
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

        List<SearchResult> results = search(query, maxResults, null, null);

        if (results.isEmpty()) {
            return ToolResponse.failure("No search results found.");
        }

        String formattedResults = results.stream()
                .map(SearchResult::toString)
                .collect(Collectors.joining("\n\n"));

        return ToolResponse.success(formattedResults);
    }

    private static List<SearchResult> search(String query, int maxResults, String proxyHost, Integer proxyPort) {
        List<SearchResult> links = new ArrayList<>();

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                // 对查询参数进行URL编码
                String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString());

                // 设置连接
                org.jsoup.Connection connection = Jsoup.connect("https://html.duckduckgo.com/html/?q=" + encodedQuery)
                        .userAgent(USER_AGENT)
                        .timeout(CONNECTION_TIMEOUT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.5")
                        .header("Connection", "keep-alive")
                        .header("DNT", "1") // Do Not Track 请求
                        .header("Upgrade-Insecure-Requests", "1")
                        .followRedirects(true)
                        .ignoreHttpErrors(true);

                // 如果提供了代理设置，添加代理
                if (proxyHost != null && proxyPort != null) {
                    Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
                    connection = connection.proxy(proxy);
                }

                // 执行请求
                Document doc = connection.get();

                // 检查响应状态码
                int statusCode = connection.response().statusCode();
                if (statusCode != 200) {
                    System.err.println("HTTP错误 " + statusCode + ": " + connection.response().statusMessage());
                    if (attempt < MAX_RETRIES - 1) {
                        continue; // 如果不是最后一次尝试，则继续下一次
                    }
                }

                // 解析结果
                Elements bodys = doc.select("div.links_main.links_deep.result__body");

                for (Element body : bodys) {

                    if (links.size() >= maxResults) break;
                    var result = body.select("a.result__url").first();
                    var titleElement = body.select("a.result__a").first();
                    var snippetElement = body.select("a.result__snippet").first();
                    var timestampElement = body.select("div.result__extras__url").select("span:eq(2)").first();
                    if (result == null){
                        continue;
                    }

                    String url = result.attr("href");
                    if (!url.isEmpty() && !url.startsWith("//")) {
                        // 从重定向URL中提取实际URL
                        if (url.contains("/l/?uddg=")) {
                            String encodedRedirectUrl = url.substring(url.indexOf("/l/?uddg=") + 9);
                            url = java.net.URLDecoder.decode(encodedRedirectUrl, StandardCharsets.UTF_8.toString());
                        }

                        // 检查URL是否包含广告关键词
                        if (!isAdUrl(url)) {
                            String title = titleElement != null ? titleElement.text() : "";
                            String snippet = snippetElement != null ? snippetElement.text() : "";
                            Instant timestamp = null;
                            if (timestampElement != null) {
                                String timestampText = timestampElement.text().trim().replaceFirst("^\\s*", "");
                                String isoFormatted = timestampText.replaceAll("\\.\\d+", ".0Z");
                                try {
                                    timestamp = Instant.parse(isoFormatted);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    // Handle parsing error if needed
                                }
                            }
                            links.add(new SearchResult(title, snippet, url, timestamp));
                        }
                    }
                }

                // 如果成功获取数据，就跳出重试循环
                break;

            } catch (Exception e) {
                System.err.println("尝试 " + (attempt + 1) + "/" + MAX_RETRIES + " 失败: " + e.getMessage());
                if (attempt == MAX_RETRIES - 1) {
                    e.printStackTrace(); // 最后一次尝试失败时打印完整堆栈
                }
            }
            try {
                // 添加随机延迟，避免被识别为机器人
                int delayTime = 3000 + RANDOM.nextInt(5000); // 3-8秒的随机延迟
                Thread.sleep(delayTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("线程被中断: " + e.getMessage());
                break;
            }
        }

        return links;
    }

    // 检查URL是否是广告链接
    private static boolean isAdUrl(String url) {
        for (String keyword : AD_KEYWORDS) {
            if (url.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
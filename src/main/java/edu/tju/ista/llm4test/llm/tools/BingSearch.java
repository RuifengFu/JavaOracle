package edu.tju.ista.llm4test.llm.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public class BingSearch implements Tool<String> {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0";
    private static final int MAX_RETRIES = 3;
    private static final int CONNECTION_TIMEOUT = 15000; // 15秒
    private static final Random RANDOM = new Random();

    // 广告相关的关键词或域名
    private static final String[] AD_KEYWORDS = {
            "adservice", "adclick", "ads", "ad_domain", "ad_provider", "ad_type", "adgroup"
    };

    @Override
    public String getName() {
        return "bing_search";
    }

    @Override
    public String getDescription() {
        return "Performs a web search using the Bing search engine and returns a list of results. " +
               "It provides the title, URL, and a snippet for each search result.";
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
        // 注释掉cookies相关代码
        // Map<String, String> cookies = new HashMap<>();

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                // 预处理搜索词 - 将空格替换为加号
                String processedQuery = query.replace(" ", "+");
                // 对查询参数进行URL编码
                String encodedQuery = URLEncoder.encode(processedQuery, StandardCharsets.UTF_8.toString());

                // 设置连接 - 增强反爬虫对抗能力
                org.jsoup.Connection connection = Jsoup.connect("https://cn.bing.com/search?q=" + encodedQuery)
                        .userAgent(USER_AGENT)
                        .timeout(CONNECTION_TIMEOUT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.5")
                        .header("Connection", "keep-alive")
                        .header("DNT", "1")
                        .header("Upgrade-Insecure-Requests", "1")
                        .header("sec-ch-ua", "\"Not_A Brand\";v=\"99\", \"Microsoft Edge\";v=\"120\", \"Chromium\";v=\"120\"")
                        .header("sec-ch-ua-mobile", "?0")
                        .header("sec-ch-ua-platform", "\"Windows\"")
                        // 添加Referer头，模拟从Bing首页来
                        .header("Referer", "https://www.bing.com/")
                        // 添加真实浏览器常见头
                        .header("sec-fetch-dest", "document")
                        .header("sec-fetch-mode", "navigate")
                        .header("sec-fetch-site", "same-origin")
                        .header("sec-fetch-user", "?1")
                        // 注释掉cookies相关设置
                        // .cookies(cookies)
                        .followRedirects(true)
                        .ignoreHttpErrors(true);

                // 如果提供了代理设置，添加代理
                if (proxyHost != null && proxyPort != null) {
                    Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
                    connection = connection.proxy(proxy);
                }

                // 注释掉获取cookies的代码
                /*
                // 首先，尝试访问Bing首页获取cookies
                if (cookies.isEmpty()) {
                    try {
                        org.jsoup.Connection.Response homeResponse = Jsoup.connect("https://cn.bing.com/")
                                .userAgent(USER_AGENT)
                                .method(org.jsoup.Connection.Method.GET)
                                .execute();
                        cookies.putAll(homeResponse.cookies());
                        // 添加必要的随机延迟，模拟人类行为
                        Thread.sleep(1000 + RANDOM.nextInt(2000));
                    } catch (Exception e) {
                        LoggerUtil.logExec(Level.WARNING, "获取Bing首页cookies失败: " + e.getMessage());
                    }
                }
                */

                // 执行请求
                org.jsoup.Connection.Response response = connection.execute();
                Document doc = response.parse();

//                System.out.println(doc);
                // 注释掉保存cookies的代码
                // cookies.putAll(response.cookies());

                // 检查响应状态码
                int statusCode = response.statusCode();
                if (statusCode != 200) {
                    LoggerUtil.logExec(Level.WARNING, "HTTP错误 " + statusCode + ": " + response.statusMessage());
                    if (attempt < MAX_RETRIES - 1) {
                        // Consider different retry strategies based on status code
                        continue;
                    } else {
                         // Last attempt failed due to HTTP error, try fallback if not already done
                         if (attempt == MAX_RETRIES - 1) {
                             LoggerUtil.logExec(Level.INFO, "最后尝试因HTTP错误失败，尝试使用DuckDuckGo");
                             List<SearchResult> duckResults = fallbackToDuckDuckGo(query, maxResults, proxyHost, proxyPort);
                             if (!duckResults.isEmpty()) {
                                 return duckResults;
                             }
                         }
                         // If fallback also fails or wasn't applicable, return empty
                         return links;
                    }
                }
                
                // 检查是否是明确的 "无结果" 页面
                Element noResultElement = doc.selectFirst("li.b_no h1");
                if (noResultElement != null && noResultElement.text().contains("There are no results for")) {
                    LoggerUtil.logExec(Level.INFO, "尝试 " + (attempt + 1) + ": Bing 返回 '无结果' 页面针对查询: " + query);
                    if (attempt < MAX_RETRIES - 1) {
                         // Decide if retrying makes sense for a 'no results' page
                         // Let's continue the loop to potentially fallback based on existing logic
                        continue; // Try next attempt or fallback
                    } else {
                         // If this was the last attempt and it explicitly returned "no results"
                         LoggerUtil.logExec(Level.WARNING, "最终尝试: Bing 明确返回无结果.");
                         // The fallback logic currently runs on attempt MAX_RETRIES - 2 if links is empty
                         // If fallback hasn't been tried, we could try it here explicitly,
                         // but let's stick to the current fallback trigger for now.
                         // If the last attempt gave "No Results", and fallback wasn't triggered before,
                         // we might still want to try it.
                         if (attempt == MAX_RETRIES - 1) { // Ensure fallback is attempted on the very last try if needed
                            LoggerUtil.logExec(Level.INFO, "最后尝试返回无结果，尝试使用DuckDuckGo");
                            List<SearchResult> duckResults = fallbackToDuckDuckGo(query, maxResults, proxyHost, proxyPort);
                            if (!duckResults.isEmpty()) {
                                return duckResults;
                            }
                         }
                         return links; // Return empty list
                    }
                }

                // System.out.println(doc.html()); // Replaced with logging below

                // 打印HTML，用于调试 (use appropriate level)
                 // Log a snippet of the body's HTML to avoid overly large logs
                 String bodyHtml = doc.select("body").html();
                 int snippetLength = Math.min(bodyHtml.length(), 1500); // Limit log snippet size
                 LoggerUtil.logExec(Level.FINEST, "Bing搜索响应HTML长度: " + doc.html().length() + "\nHTML Snippet:\n" + bodyHtml.substring(0, snippetLength));

                // 检查页面是否包含正常搜索结果的标记
                if (!doc.html().contains("b_algo") && !doc.select("li.b_algo").isEmpty() && !doc.select(".b_results > li:not(.b_no)").isEmpty()) { // Check selectors directly
                    LoggerUtil.logExec(Level.WARNING, "未找到'b_algo' class标记，但可能存在结果结构，尝试解析");
                    // Allow parsing to proceed using selectors below
                } else if (doc.select("li.b_algo").isEmpty() && doc.select(".b_results > li:not(.b_no)").isEmpty()) {
                    LoggerUtil.logExec(Level.WARNING, "未找到搜索结果标记(li.b_algo 或 .b_results > li:not(.b_no))，可能被反爬虫拦截或页面结构改变");
                     if (attempt < MAX_RETRIES - 1) {
                        // 如果未找到结果且不是最后一次尝试，改变策略重试
                        // cookies.clear(); // 清除cookies重新获取 (Consider if re-enabling cookies)
                        continue;
                    }
                    // If it's the last attempt and no results found, proceed to fallback logic after the loop.
                }

                // 解析结果 - 尝试多种选择器模式
                Elements results = doc.select("li.b_algo");
                if (results.isEmpty()) {
                    results = doc.select(".b_results > li:not(.b_no)");  // 备用选择器, explicitly exclude .b_no
                }

                for (Element result : results) {
                    if (links.size() >= maxResults) break;
                    
                     if (result.hasClass("b_no")) { // Double check within loop
                         continue;
                     }
                    Element titleElement = result.selectFirst("h2 > a");
                    Element snippetElement = result.selectFirst("div.b_caption > p");
                    
                    // Add stricter null checks before accessing properties
                    if (titleElement == null || titleElement.attr("href") == null || titleElement.attr("href").isEmpty()) {
                        LoggerUtil.logExec(Level.FINER, "跳过结果，因为标题或链接为空");
                        continue;
                    }

                    String url = titleElement.attr("href");
                    // Check for ads slightly more robustly (optional)
                    if (url.isEmpty() || isAdUrl(url) || result.className().toLowerCase().contains("ad") || result.html().toLowerCase().contains(" Sponso")) { // Enhanced ad check
                        LoggerUtil.logExec(Level.FINER, "跳过广告链接: " + url);
                        continue;
                    }
                    
                    String title = titleElement.text();
                    // Ensure snippet element exists before getting text
                    String snippet = (snippetElement != null) ? snippetElement.text() : "";
                    
                    if (title == null || title.trim().isEmpty()){
                         LoggerUtil.logExec(Level.FINER, "跳过结果，因为标题为空: " + url);
                         continue; // Skip if title is empty even if link exists
                    }

                    // 尝试提取日期（Bing有时会在搜索结果中包含日期）
                    Instant timestamp = null;
                    Element dateElement = result.selectFirst("span.news_dt");
                    if (dateElement != null) {
                        try {
                            // 提取并解析日期文本
                            String dateText = dateElement.text().trim();
                            // 尝试解析多种常见日期格式
                            timestamp = parseDate(dateText);
                        } catch (Exception e) {
                            LoggerUtil.logExec(Level.FINEST, "日期解析失败: " + dateElement.text() + " - " + e.getMessage());
                            // 日期解析失败时忽略
                        }
                    }
                    
                    links.add(new SearchResult(title, snippet, url, timestamp));
                }
                
                // 如果成功获取数据，就跳出重试循环
                if (!links.isEmpty()) {
                     LoggerUtil.logExec(Level.INFO, "成功获取 " + links.size() + " 条结果，在尝试 " + (attempt + 1) + ".");
                    break; // Exit retry loop on success
                } else if (attempt < MAX_RETRIES - 1) {
                    // This block is reached if parsing finished but found 0 links (and wasn't a "no results" page initially)
                    LoggerUtil.logExec(Level.WARNING, "尝试 " + (attempt+1) + ": 解析完成但未找到有效链接，尝试更换策略");
                    // 尝试DuckDuckGo作为备选方案
                    if (attempt == MAX_RETRIES - 2) { // Existing fallback trigger point
                        LoggerUtil.logExec(Level.INFO, "尝试使用DuckDuckGo作为备选搜索引擎");
                        List<SearchResult> duckResults = fallbackToDuckDuckGo(query, maxResults, proxyHost, proxyPort);
                        if (!duckResults.isEmpty()) {
                            return duckResults;
                        }
                        // If fallback on attempt MAX_RETRIES-2 fails, the loop continues to the last attempt.
                    }
                } else {
                     // This is the last attempt, and it resulted in an empty links list (and wasn't caught by specific errors above)
                     LoggerUtil.logExec(Level.WARNING, "最后尝试 " + (attempt + 1) + " 完成，未找到任何结果.");
                     // Trigger fallback on the very last attempt if it hasn't succeeded yet
                     if (attempt == MAX_RETRIES - 1) {
                         LoggerUtil.logExec(Level.INFO, "最终尝试未找到结果，尝试使用DuckDuckGo");
                         List<SearchResult> duckResults = fallbackToDuckDuckGo(query, maxResults, proxyHost, proxyPort);
                         if (!duckResults.isEmpty()) {
                             return duckResults;
                         }
                     }
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "Bing搜索尝试 " + (attempt + 1) + "/" + MAX_RETRIES + " 失败: " + e.getMessage());
                if (attempt == MAX_RETRIES - 1) {
                    LoggerUtil.logExec(Level.SEVERE, "最后尝试失败，放弃 " + e.getMessage()); // Log exception stack trace on last failure
                    // Attempt fallback on last attempt exception
//                    List<SearchResult> duckResults = fallbackToDuckDuckGo(query, maxResults, proxyHost, proxyPort);
//                    if (!duckResults.isEmpty()) {
//                        return duckResults;
//                    }
                }
            }
            
            try {
                // 添加随机延迟，避免被识别为机器人
                int delayTime = 3000 + RANDOM.nextInt(5000); // 3-8秒的随机延迟
                Thread.sleep(delayTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LoggerUtil.logExec(Level.WARNING, "线程被中断: " + e.getMessage());
                break;
            }
        }

        // Return whatever links were collected, or an empty list if all attempts/fallbacks failed
        if (links.isEmpty()) {
             LoggerUtil.logExec(Level.WARNING, "所有尝试和备选方案均失败，返回空列表。");
        }
        return links;
    }

    // 尝试解析各种日期格式
    private static Instant parseDate(String dateText) {
        // 移除不必要的文本和标点符号
        dateText = dateText.replaceAll("发布于\\s*|·\\s*|\\s*ago", "").trim();
        
        try {
            // 尝试处理相对日期格式 ("2 days ago", "5 hours ago" 等)
            if (dateText.contains("day") || dateText.contains("hour") || 
                dateText.contains("minute") || dateText.contains("second")) {
                return parseRelativeDate(dateText);
            }
            
            // 尝试ISO格式
            return Instant.parse(dateText);
        } catch (DateTimeParseException e) {
            // 进一步尝试其他可能的日期格式
            try {
                // 常见格式如 "Jan 15, 2023" 或 "2023-01-15"
                Pattern datePattern = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");
                Matcher matcher = datePattern.matcher(dateText);
                if (matcher.find()) {
                    int year = Integer.parseInt(matcher.group(1));
                    int month = Integer.parseInt(matcher.group(2));
                    int day = Integer.parseInt(matcher.group(3));
                    return Instant.parse(String.format("%04d-%02d-%02dT00:00:00Z", year, month, day));
                }
            } catch (Exception ignored) {
                // 忽略，继续尝试其他格式
            }
            
            return null; // 无法解析时返回null
        }
    }
    
    // 解析相对日期（如"2 days ago"）
    private static Instant parseRelativeDate(String relativeDate) {
        try {
            Pattern pattern = Pattern.compile("(\\d+)\\s+(day|hour|minute|second)s?");
            Matcher matcher = pattern.matcher(relativeDate);
            if (matcher.find()) {
                int amount = Integer.parseInt(matcher.group(1));
                String unit = matcher.group(2);
                Instant now = Instant.now();
                
                switch (unit) {
                    case "day":
                        return now.minusSeconds(amount * 24 * 60 * 60);
                    case "hour":
                        return now.minusSeconds(amount * 60 * 60);
                    case "minute":
                        return now.minusSeconds(amount * 60);
                    case "second":
                        return now.minusSeconds(amount);
                }
            }
        } catch (Exception ignored) {
            // 解析失败时忽略
        }
        
        return null;
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

    // 备选搜索引擎方法
    private static List<SearchResult> fallbackToDuckDuckGo(String query, int maxResults, String proxyHost, Integer proxyPort) {
        try {
            // 实现一个简单的DuckDuckGo搜索
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString());
            org.jsoup.Connection connection = Jsoup.connect("https://html.duckduckgo.com/html/?q=" + encodedQuery)
                    .userAgent(USER_AGENT)
                    .timeout(CONNECTION_TIMEOUT);
            
            if (proxyHost != null && proxyPort != null) {
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
                connection = connection.proxy(proxy);
            }
            
            Document doc = connection.get();
            Elements results = doc.select(".result");
            
            List<SearchResult> links = new ArrayList<>();
            for (Element result : results) {
                if (links.size() >= maxResults) break;
                
                Element titleElement = result.selectFirst(".result__title > a");
                Element snippetElement = result.selectFirst(".result__snippet");
                
                if (titleElement != null) {
                    String url = titleElement.attr("href");
                    String title = titleElement.text();
                    String snippet = snippetElement != null ? snippetElement.text() : "";
                    
                    links.add(new SearchResult(title, snippet, url, null));
                }
            }
            
            return links;
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "DuckDuckGo备选搜索失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }
} 

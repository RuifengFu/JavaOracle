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
import java.util.Random;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BingSearch implements Tool<List<SearchResult>> {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0";
    private static final int MAX_RETRIES = 3;
    private static final int CONNECTION_TIMEOUT = 15000; // 15秒
    private static final Random RANDOM = new Random();

    // 广告相关的关键词或域名
    private static final String[] AD_KEYWORDS = {
            "adservice", "adclick", "ads", "ad_domain", "ad_provider", "ad_type", "adgroup"
    };

    public static List<SearchResult> search(String query, int maxResults) {
        return search(query, maxResults, null, null);
    }

    public static List<SearchResult> search(String query, int maxResults, String proxyHost, Integer proxyPort) {
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
                
                // 注释掉保存cookies的代码
                // cookies.putAll(response.cookies());

                // 检查响应状态码
                int statusCode = response.statusCode();
                if (statusCode != 200) {
                    LoggerUtil.logExec(Level.WARNING, "HTTP错误 " + statusCode + ": " + response.statusMessage());
                    if (attempt < MAX_RETRIES - 1) {
                        continue;
                    }
                }

                System.out.println(doc.html());

                // 打印HTML，用于调试
                LoggerUtil.logExec(Level.FINE, "Bing搜索响应HTML长度: " + doc.html().length());
                // 检查页面是否包含正常搜索结果的标记
                if (!doc.html().contains("b_algo") && !doc.html().contains("b_results")) {
                    LoggerUtil.logExec(Level.WARNING, "未找到搜索结果标记，可能被反爬虫拦截");
                    // 尝试备用解析方法
                    Elements altResults = doc.select("li.b_algo, .b_results > li, .b_entityTP");
                    if (!altResults.isEmpty()) {
                        LoggerUtil.logExec(Level.INFO, "使用备用选择器找到结果: " + altResults.size() + " 个");
                    } else if (attempt < MAX_RETRIES - 1) {
                        // 如果未找到结果且不是最后一次尝试，改变策略重试
                        // cookies.clear(); // 清除cookies重新获取
                        continue;
                    }
                }

                // 解析结果 - 尝试多种选择器模式
                Elements results = doc.select("li.b_algo");
                if (results.isEmpty()) {
                    results = doc.select(".b_results > li");  // 备用选择器
                }

                for (Element result : results) {
                    if (links.size() >= maxResults) break;
                    
                    Element titleElement = result.selectFirst("h2 > a");
                    Element snippetElement = result.selectFirst("div.b_caption > p");
                    
                    if (titleElement == null) {
                        continue;
                    }

                    String url = titleElement.attr("href");
                    if (url.isEmpty() || isAdUrl(url)) {
                        continue;
                    }
                    
                    String title = titleElement.text();
                    String snippet = snippetElement != null ? snippetElement.text() : "";
                    
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
                            // 日期解析失败时忽略
                        }
                    }
                    
                    links.add(new SearchResult(title, snippet, url, timestamp));
                }
                
                // 如果成功获取数据，就跳出重试循环
                if (!links.isEmpty()) {
                    break;
                } else if (attempt < MAX_RETRIES - 1) {
                    LoggerUtil.logExec(Level.WARNING, "没有找到搜索结果，尝试更换策略");
                    // 尝试DuckDuckGo作为备选方案
                    if (attempt == MAX_RETRIES - 2) {
                        LoggerUtil.logExec(Level.INFO, "尝试使用DuckDuckGo作为备选搜索引擎");
                        List<SearchResult> duckResults = fallbackToDuckDuckGo(query, maxResults, proxyHost, proxyPort);
                        if (!duckResults.isEmpty()) {
                            return duckResults;
                        }
                    }
                }
            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "Bing搜索尝试 " + (attempt + 1) + "/" + MAX_RETRIES + " 失败: " + e.getMessage());
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
                LoggerUtil.logExec(Level.WARNING, "线程被中断: " + e.getMessage());
                break;
            }
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

    @Override
    public String getName() {
        return "BingSearch";
    }

    @Override
    public String getDescription() {
        return "使用Bing搜索引擎，根据查询内容搜索网页并返回结果链接。";
    }

    @Override
    public ToolResponse<List<SearchResult>> execute(String query) {
        var list = search(query, 10);
        if (list.isEmpty()) {
            return ToolResponse.failure("无搜索结果");
        } else {
            return ToolResponse.success(list);
        }
    }

    public static void main(String[] args) {
        System.setProperty("java.net.useSystemProxies", "true");
        System.out.println("Bing搜索测试:");
        List<SearchResult> results = search("Java HashMap", 10);
        results.forEach(System.out::println);

    }
} 

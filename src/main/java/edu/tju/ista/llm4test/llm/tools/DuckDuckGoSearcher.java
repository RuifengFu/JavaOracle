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
import java.util.Random;

public class DuckDuckGoSearcher implements Tool<List<SearchResult>> {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
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

    public static void main(String[] args) {
        // 使用代理的例子
        // 请替换为实际的代理服务器地址和端口
//        System.out.println("\n使用代理的搜索结果:");
//        String proxyHost = "127.0.0.1"; // 替换为实际代理主机
//        int proxyPort = 7890; // 替换为实际代理端口

        // 使用代理进行搜索的例子
        System.setProperty("java.net.useSystemProxies", "true");
        List<SearchResult> proxyResults = search("Elon Musk latest news", 10);
        proxyResults.forEach(System.out::println);
    }

    @Override
    public String getName() {
        return "WebSearch";
    }

    @Override
    public String getDescription() {
        return "According to the query, search the web and return links.";
    }

    @Override
    public ToolResponse<List<SearchResult>> execute(String query) {
        var list = search(query, 10);
        if (list.isEmpty()) {
            return ToolResponse.failure("No results found.");
        } else {
            return ToolResponse.success(list);
        }
    }
}
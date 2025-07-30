package edu.tju.ista.llm4test.llm.tools;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.ins.InsExtension;
import com.vladsch.flexmark.ext.superscript.SuperscriptExtension;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.http.HttpHeaders;
import java.util.regex.Matcher;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 智能网页内容提取器 - 静态优先，动态降级策略
 * 根据网站类型自动选择最优提取方式，大幅提高成功率和性能
 */
public class WebContentExtractor implements Tool<String> {
    private static final Logger logger = Logger.getLogger(WebContentExtractor.class.getName());
    
    // 用户代理字符串
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36";
    
    // 超时配置
    private static final int HTTP_TIMEOUT_SECONDS = 15;
    private static final int PLAYWRIGHT_WAIT_MS = 8000;
    private static final int PLAYWRIGHT_NAVIGATION_TIMEOUT = 12000;
    
    // 重试配置
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 500;
    
    // 内容质量阈值
    private static final int MIN_CONTENT_LENGTH = 100; // 最小有效内容长度
    private static final int MIN_PARAGRAPH_COUNT = 1;  // 最小段落数
    private static final int MAX_CONTENT_LENGTH = 50000; // 最大内容长度
    
    // HTTP客户端（用于静态页面）
    private final HttpClient httpClient;
    // Markdown转换器
    private final FlexmarkHtmlConverter markdownConverter;
    // Playwright实例（延迟初始化）
    private Playwright playwright;
    private Browser browser;
    private final AtomicBoolean isPlaywrightInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final ReentrantLock playwrightInitLock = new ReentrantLock();
    
    // 缓存已访问的URL以避免重复处理
    private final Map<String, ExtractionResult> urlCache = Collections.synchronizedMap(new LinkedHashMap<String, ExtractionResult>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ExtractionResult> eldest) {
            return size() > 1000; // 限制缓存大小
        }
    });
    
    // 需要动态渲染的网站模式
    private static final Set<String> DYNAMIC_SITE_PATTERNS = Set.of(
        "react", "angular", "vue", "spa", "app", "dashboard", "admin",
        "twitter.com", "facebook.com", "instagram.com", "linkedin.com",
        "medium.com", "dev.to", "stackoverflow.com"
    );
    
    // 静态内容网站模式
    private static final Set<String> STATIC_SITE_PATTERNS = Set.of(
        "blog", "news", "wiki", "doc", "documentation", "tutorial",
        "github.io", "readthedocs", "gitbook", "w3.org", "ietf.org", "rfc-editor.org"
    );
    
    /**
     * 提取结果封装类
     */
    private static class ExtractionResult {
        final String content;
        final boolean isSuccess;
        final String method;
        final long timestamp;
        
        ExtractionResult(String content, boolean isSuccess, String method) {
            this.content = content;
            this.isSuccess = isSuccess;
            this.method = method;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 300000; // 5分钟过期
        }
    }
    
    public WebContentExtractor(boolean enablePlaywright) {
        // 初始化HTTP客户端
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .build();
        
        // 初始化Markdown转换器
        MutableDataSet options = new MutableDataSet();
        options.set(com.vladsch.flexmark.parser.Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                InsExtension.create(),
                SuperscriptExtension.create()
        ));
        markdownConverter = FlexmarkHtmlConverter.builder(options).build();
        
        // 如果启用Playwright，则在第一次需要时初始化
        // 这里不立即初始化，避免启动时的性能问题
    }
    
    /**
     * 智能内容提取 - 主入口方法
     */
    public String extractContent(String url) {
        if (isClosed.get()) {
            throw new RuntimeException("WebContentExtractor已关闭");
        }
        
        // 检查缓存
        ExtractionResult cached = urlCache.get(url);
        if (cached != null && !cached.isExpired() && cached.isSuccess) {
            logger.info("使用缓存结果: " + url + " (缓存内容长度: " + cached.content.length() + ")");
            return cached.content;
        }
        
        logger.info("开始智能提取网页内容: " + url);
        
        // 1. 分析URL，决定提取策略
        ExtractionStrategy strategy = analyzeUrlAndDetermineStrategy(url);
        logger.info("选择提取策略: " + strategy + " for " + url);
        
        String content = null;
        String method = "";
        
        try {
            // 2. 根据策略执行提取
            switch (strategy) {
                case STATIC_FIRST:
                    content = extractWithStaticFirst(url);
                    method = "static_first";
                    break;
                case DYNAMIC_FIRST:
                    content = extractWithDynamicFirst(url);
                    method = "dynamic_first";
                    break;
                case STATIC_ONLY:
                    content = extractStaticContent(url);
                    method = "static_only";
                    break;
            }
            
            // 3. 验证内容质量
            if (isContentValid(content)) {
                logger.info("内容提取成功: " + url + " (方法: " + method + ", 长度: " + content.length() + ")");
                urlCache.put(url, new ExtractionResult(content, true, method));
                return content;
            } else {
                logger.warning("提取的内容质量不佳，尝试备用方法: " + url);
                // 尝试备用方法
                content = extractWithFallback(url, strategy);
                if (isContentValid(content)) {
                    logger.info("备用方法提取成功: " + url + " (长度: " + content.length() + ")");
                    urlCache.put(url, new ExtractionResult(content, true, "fallback"));
                    return content;
                }
            }
        } catch (Exception e) {
            logger.warning("内容提取异常: " + url + " - " + e.getMessage());
        }
        
        // 4. 所有方法都失败，返回基础信息
        String fallbackContent = "# 网页内容提取失败\n\n无法提取网页内容，请检查URL是否可访问：" + url;
        urlCache.put(url, new ExtractionResult(fallbackContent, false, "failed"));
        return fallbackContent;
    }
    
    /**
     * 分析URL并确定提取策略
     */
    private ExtractionStrategy analyzeUrlAndDetermineStrategy(String url) {
        String lowerUrl = url.toLowerCase();
        
        // 检查是否为明显的静态内容网站
        for (String pattern : STATIC_SITE_PATTERNS) {
            if (lowerUrl.contains(pattern)) {
                return ExtractionStrategy.STATIC_FIRST;
            }
        }
        
        // 检查是否为明显的动态网站
        for (String pattern : DYNAMIC_SITE_PATTERNS) {
            if (lowerUrl.contains(pattern)) {
                return ExtractionStrategy.DYNAMIC_FIRST;
            }
        }
        
        // 根据域名特征判断
        if (lowerUrl.contains("github.com") || lowerUrl.contains("stackoverflow.com") || 
            lowerUrl.contains("reddit.com") || lowerUrl.contains("medium.com")) {
            return ExtractionStrategy.DYNAMIC_FIRST;
        }
        
        // 文档、新闻、博客类网站优先静态
        if (lowerUrl.contains("doc") || lowerUrl.contains("blog") || 
            lowerUrl.contains("news") || lowerUrl.contains("wiki") ||
            lowerUrl.contains("oracle.com") || lowerUrl.contains("baeldung.com")) {
            return ExtractionStrategy.STATIC_FIRST;
        }
        
        // 默认策略：静态优先
        return ExtractionStrategy.STATIC_FIRST;
    }
    
    /**
     * 静态优先策略
     */
    private String extractWithStaticFirst(String url) {
        // 先尝试静态提取
        String staticContent = extractStaticContent(url);
        if (isContentValid(staticContent)) {
            return staticContent;
        }
        
        // 静态提取失败或内容不够，尝试动态提取
        logger.info("静态提取内容不足，尝试动态提取: " + url);
        return extractDynamicContent(url, null);
    }
    
    /**
     * 动态优先策略
     */
    private String extractWithDynamicFirst(String url) {
        // 先尝试动态提取
        try {
            String dynamicContent = extractDynamicContent(url, null);
            if (isContentValid(dynamicContent)) {
                return dynamicContent;
            }
        } catch (Exception e) {
            logger.warning("动态提取失败，回退到静态提取: " + url + " - " + e.getMessage());
        }
        
        // 动态提取失败，尝试静态提取
        return extractStaticContent(url);
    }
    
    /**
     * 备用提取方法
     */
    private String extractWithFallback(String url, ExtractionStrategy originalStrategy) {
        try {
            if (originalStrategy == ExtractionStrategy.STATIC_FIRST) {
                // 原来是静态优先，现在尝试动态
                return extractDynamicContent(url, null);
            } else {
                // 原来是动态优先，现在尝试静态
                return extractStaticContent(url);
            }
        } catch (Exception e) {
            logger.warning("备用提取方法也失败: " + url + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 改进的静态内容提取
     */
    public String extractStaticContent(String url) {
        if (isClosed.get()) {
            throw new RuntimeException("WebContentExtractor已关闭");
        }
        
        int retryCount = 0;
        while (retryCount <= MAX_RETRIES) {
            try {
                logger.info("开始静态内容提取: " + url + " (尝试 " + (retryCount + 1) + "/" + (MAX_RETRIES + 1) + ")");
                
                // 创建更真实的HTTP请求头
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(new URI(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                        .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
                        .header("Accept-Encoding", "gzip, deflate")
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .header("Upgrade-Insecure-Requests", "1")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "none")
                        .header("Sec-Fetch-User", "?1")
                        .header("Sec-Ch-Ua", "\"Chromium\";v=\"119\", \"Not?A_Brand\";v=\"24\"")
                        .header("Sec-Ch-Ua-Mobile", "?0")
                        .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                        .header("DNT", "1")
                        .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                        .GET();
                
                // 根据URL添加特定的请求头
                if (url.contains("github.com")) {
                    requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                } else if (url.contains("stackoverflow.com")) {
                    requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                } else if (url.contains("medium.com")) {
                    requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                }
                
                HttpRequest request = requestBuilder.build();
                
                // 发送请求并获取响应 - 使用字节处理器以便手动处理编码
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                
                // 改进的状态码处理
                int statusCode = response.statusCode();
                if (statusCode != 200) {
                    if (shouldRetry(statusCode, retryCount)) {
                        Thread.sleep(calculateRetryDelay(retryCount));
                        retryCount++;
                        continue;
                    }
                    logger.warning("HTTP错误: " + statusCode + " for URL: " + url);
                    return "页面获取失败: HTTP " + statusCode;
                }
                
                // 处理响应体编码
                byte[] bodyBytes = response.body();
                if (bodyBytes == null || bodyBytes.length == 0) {
                    if (retryCount < MAX_RETRIES) {
                        Thread.sleep(calculateRetryDelay(retryCount));
                        retryCount++;
                        continue;
                    }
                    return "页面内容为空";
                }
                
                // 检测并应用正确的字符编码
                String html = decodeResponseBody(bodyBytes, response.headers());
                if (html == null || html.trim().isEmpty()) {
                    if (retryCount < MAX_RETRIES) {
                        Thread.sleep(calculateRetryDelay(retryCount));
                        retryCount++;
                        continue;
                    }
                    return "页面内容为空";
                }
                
                Document doc = Jsoup.parse(html, url);
                
                // 改进的内容提取
                String extractedContent = extractMainContentAdvanced(doc, url);
                
                logger.info("静态内容提取完成: " + url + " (长度: " + extractedContent.length() + ")");
                return extractedContent;
                
            } catch (IOException | InterruptedException | URISyntaxException e) {
                logger.warning("静态页面获取失败 (尝试 " + (retryCount + 1) + "): " + e.getMessage());
                if (retryCount < MAX_RETRIES) {
                    try {
                        Thread.sleep(calculateRetryDelay(retryCount));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    retryCount++;
                } else {
                    return "页面获取失败: " + e.getMessage();
                }
            }
        }
        
        return "页面获取失败: 超过最大重试次数";
    }
    
    /**
     * 判断是否应该重试
     */
    private boolean shouldRetry(int statusCode, int retryCount) {
        if (retryCount >= MAX_RETRIES) {
            return false;
        }
        
        // 可重试的状态码
        return statusCode == 429 ||  // Too Many Requests
               statusCode == 500 ||  // Internal Server Error
               statusCode == 502 ||  // Bad Gateway
               statusCode == 503 ||  // Service Unavailable
               statusCode == 504 ||  // Gateway Timeout
               statusCode == 408 ||  // Request Timeout
               statusCode == 520 ||  // Cloudflare: Unknown Error
               statusCode == 521 ||  // Cloudflare: Web Server Is Down
               statusCode == 522 ||  // Cloudflare: Connection Timed Out
               statusCode == 523 ||  // Cloudflare: Origin Is Unreachable
               statusCode == 524;    // Cloudflare: A Timeout Occurred
    }
    
    /**
     * 计算重试延迟（指数退避）
     */
    private long calculateRetryDelay(int retryCount) {
        return RETRY_DELAY_MS * (long) Math.pow(2, retryCount);
    }
    
    /**
     * 改进的动态内容提取
     */
    public String extractDynamicContent(String url, String waitSelector) {
        if (isClosed.get()) {
            throw new RuntimeException("WebContentExtractor已关闭");
        }
        
        // 确保Playwright已初始化
        if (!isPlaywrightInitialized.get()) {
            initializePlaywright();
        }
        
        if (browser == null) {
            logger.warning("Playwright初始化失败，回退到静态提取");
            return extractStaticContent(url);
        }
        
        BrowserContext context = null;
        Page page = null;
        
        try {
            logger.info("开始动态内容提取: " + url);
            
            // 创建页面上下文 - 优化性能设置
            context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(USER_AGENT)
                    .setHasTouch(false)
                    .setLocale("en-US")
                    .setViewportSize(1920, 1080)
                    .setIgnoreHTTPSErrors(true)
                    .setJavaScriptEnabled(true)
                    .setBypassCSP(true)
                    // 禁用图片和字体加载以提高性能
                    .setExtraHTTPHeaders(Map.of(
                        "Accept-Language", "en-US,en;q=0.9",
                        "Accept-Encoding", "gzip, deflate"
                    )));
            
            // 创建新页面
            page = context.newPage();
            
            // 拦截不必要的资源以提高性能
            page.route("**/*", route -> {
                String resourceType = route.request().resourceType();
                String requestUrl = route.request().url();
                
                // 阻止图片、字体、媒体文件等
                if (resourceType.equals("image") || 
                    resourceType.equals("font") || 
                    resourceType.equals("media") ||
                    resourceType.equals("manifest") ||
                    requestUrl.contains(".jpg") || requestUrl.contains(".jpeg") ||
                    requestUrl.contains(".png") || requestUrl.contains(".gif") ||
                    requestUrl.contains(".svg") || requestUrl.contains(".webp") ||
                    requestUrl.contains(".mp4") || requestUrl.contains(".mp3") ||
                    requestUrl.contains(".pdf") || requestUrl.contains(".zip")) {
                    route.abort();
                } else if (resourceType.equals("stylesheet") && 
                          (requestUrl.contains("font") || requestUrl.contains("icon"))) {
                    // 阻止字体相关的CSS
                    route.abort();
                } else {
                    route.resume();
                }
            });
            
            // 设置超时
            page.setDefaultTimeout(PLAYWRIGHT_WAIT_MS);
            page.setDefaultNavigationTimeout(PLAYWRIGHT_NAVIGATION_TIMEOUT);
            
            // 导航到目标URL
            Response response = page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(PLAYWRIGHT_NAVIGATION_TIMEOUT));
            
            // 检查响应状态
            if (response != null && response.status() >= 400) {
                logger.warning("页面响应错误: " + response.status() + " for URL: " + url);
                return "页面加载失败: HTTP " + response.status();
            }
            
            // 智能等待策略
            boolean contentLoaded = waitForContentToLoad(page, url, waitSelector);
            if (!contentLoaded) {
                logger.info("内容加载检测超时，继续处理: " + url);
            }
            
            // 获取页面内容
            String html = page.content();
            String title = page.title();
            
            if (html == null || html.trim().isEmpty()) {
                return "页面内容为空";
            }
            
            // 使用Jsoup解析HTML
            Document doc = Jsoup.parse(html, url);
            
            // 提取内容
            String extractedContent = extractMainContentAdvanced(doc, url);
            
            logger.info("动态内容提取完成: " + url + " (长度: " + extractedContent.length() + ")");
            return extractedContent;
            
        } catch (Exception e) {
            logger.warning("动态页面提取失败: " + url + " - " + e.getMessage());
            return "动态页面提取失败: " + e.getMessage();
        } finally {
            // 确保资源被正确清理
            if (page != null) {
                try {
                    page.close();
                } catch (Exception e) {
                    logger.warning("关闭页面失败: " + e.getMessage());
                }
            }
            if (context != null) {
                try {
                    context.close();
                } catch (Exception e) {
                    logger.warning("关闭上下文失败: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * 智能等待内容加载完成
     */
    private boolean waitForContentToLoad(Page page, String url, String waitSelector) {
        try {
            // 1. 等待基本DOM加载
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, 
                new Page.WaitForLoadStateOptions().setTimeout(5000));
            
            // 2. 如果指定了选择器，等待特定元素
            if (waitSelector != null && !waitSelector.isEmpty()) {
                try {
                    page.waitForSelector(waitSelector, 
                        new Page.WaitForSelectorOptions().setTimeout(PLAYWRIGHT_WAIT_MS));
                    logger.info("成功等待到指定选择器: " + waitSelector);
                } catch (PlaywrightException e) {
                    logger.info("等待选择器超时，继续处理: " + waitSelector);
                }
            }
            
            // 3. 智能检测内容加载 - 等待网络相对空闲
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, 
                    new Page.WaitForLoadStateOptions().setTimeout(6000));
                logger.info("网络空闲状态达到");
            } catch (PlaywrightException e) {
                logger.info("网络空闲等待超时，使用其他策略");
            }
            
            // 4. 基于内容变化的等待策略
            return waitForContentStability(page, url);
            
        } catch (Exception e) {
            logger.warning("等待内容加载过程出错: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 等待内容稳定（基于内容变化检测）
     */
    private boolean waitForContentStability(Page page, String url) {
        try {
            String previousContent = "";
            int stableCount = 0;
            int maxChecks = 8;
            int checkInterval = 500;
            
            for (int i = 0; i < maxChecks; i++) {
                Thread.sleep(checkInterval);
                
                // 获取主要内容区域的文本
                String currentContent = page.evaluate("() => {" +
                    "const main = document.querySelector('main, article, .content, .main, #content, #main');" +
                    "if (main) return main.innerText;" +
                    "return document.body.innerText;" +
                "}").toString();
                
                if (currentContent.equals(previousContent)) {
                    stableCount++;
                    if (stableCount >= 2) {
                        logger.info("内容已稳定，停止等待");
                        return true;
                    }
                } else {
                    stableCount = 0;
                    previousContent = currentContent;
                }
                
                // 检查是否有明显的加载指示器
                boolean hasLoadingIndicator = (Boolean) page.evaluate("() => {" +
                    "const selectors = ['.loading', '.spinner', '.loader', '[data-loading]', '.skeleton'];" +
                    "return selectors.some(sel => document.querySelector(sel) !== null);" +
                "}");
                
                if (!hasLoadingIndicator && i >= 2) {
                    logger.info("未检测到加载指示器，内容可能已加载完成");
                    return true;
                }
            }
            
            logger.info("内容稳定性检测超时");
            return false;
            
        } catch (Exception e) {
            logger.warning("内容稳定性检测失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 高级内容提取算法
     */
    private String extractMainContentAdvanced(Document doc, String url) {
        // 检查是否为W3C规范页面
        boolean isW3CPage = url.contains("w3.org/TR/");
        
        if (isW3CPage) {
            // 特殊处理W3C规范格式
            return extractW3CSpecificationContent(doc, url);
        }
        
        // 标准HTML内容提取
        // 1. 清理无用元素
        cleanDocument(doc);
        
        // 2. 查找主内容
        Element mainContent = findMainContentAdvanced(doc);
        
        // 3. 提取标题
        String title = extractTitle(doc);
        
        // 4. 清理HTML
        String cleanedHtml = mainContent != null ? mainContent.outerHtml() : doc.body().html();
        
        // 5. 转换为Markdown
        String markdown = htmlToMarkdown(cleanedHtml, title);
        
        // 6. 后处理
        return postProcessMarkdown(markdown);
    }
    
    /**
     * 改进的文档清理
     */
    private void cleanDocument(Document doc) {
        // 移除脚本、样式等
        doc.select("script, style, svg, iframe, noscript, object, embed, video, audio").remove();
        
        // 移除广告和导航元素
        doc.select("nav, footer, .footer, .header, header, .navigation, .sidebar, .menu, .ad, "
                + ".advertisement, .banner, .cookie-notice, .popup, .modal, .overlay, "
                + ".social-share, .share-buttons, .comments, .comment-section, "
                + "[role=complementary], [role=banner], [role=navigation], [role=contentinfo], "
                + ".breadcrumb, .pagination, .related-posts, .sidebar-widget").remove();
        
        // 移除常见的垃圾类名
        doc.select("[class*=advert], [class*=banner], [class*=cookie], [class*=popup], "
                + "[class*=overlay], [class*=newsletter], [class*=social], [class*=share], "
                + "[id*=cookie], [id*=banner], [id*=ad], [id*=popup], "
                + "[data-ad], [data-advertisement]").remove();
        
        // 移除隐藏元素
        doc.select("[style*=display:none], [style*=visibility:hidden], .hidden, .invisible, "
                + "[aria-hidden=true]").remove();
        
        // 移除空元素
        Elements emptyElements = doc.select("p:empty, div:empty, span:empty, h1:empty, h2:empty, h3:empty, h4:empty, h5:empty, h6:empty");
        emptyElements.remove();
    }
    
    /**
     * 高级主内容查找算法
     */
    private Element findMainContentAdvanced(Document doc) {
        // 策略1: 语义化标签
        Element semanticMain = findSemanticMainContent(doc);
        if (semanticMain != null && calculateContentQuality(semanticMain) > 50) {
            return semanticMain;
        }
        
        // 策略2: 常见的内容容器
        Element containerMain = findContainerMainContent(doc);
        if (containerMain != null && calculateContentQuality(containerMain) > 50) {
            return containerMain;
        }
        
        // 策略3: 基于内容密度的分析
        Element densityMain = findMainContentByDensity(doc);
        if (densityMain != null && calculateContentQuality(densityMain) > 30) {
            return densityMain;
        }
        
        // 策略4: 现代Web应用内容检测
        Element modernAppMain = findModernAppContent(doc);
        if (modernAppMain != null && calculateContentQuality(modernAppMain) > 30) {
            return modernAppMain;
        }
        
        // 策略5: 最大文本块
        return findLargestTextBlock(doc);
    }
    
    /**
     * 现代Web应用内容检测
     */
    private Element findModernAppContent(Document doc) {
        // React/Vue/Angular等现代框架的常见容器
        String[] modernSelectors = {
            "#app", "#root", ".app", ".container-fluid", ".main-wrapper",
            "[data-reactroot]", "[data-react-root]", "[data-vue-root]", 
            ".vue-app", ".ng-app", ".react-app",
            ".page-content", ".main-content", ".content-wrapper",
            ".layout-main", ".site-main", ".primary-content"
        };
        
        Element bestElement = null;
        int maxScore = 0;
        
        for (String selector : modernSelectors) {
            Elements elements = doc.select(selector);
            for (Element element : elements) {
                int score = calculateEnhancedContentQuality(element);
                if (score > maxScore) {
                    maxScore = score;
                    bestElement = element;
                }
            }
        }
        
        return bestElement;
    }
    
    /**
     * 增强的内容质量计算
     */
    private int calculateEnhancedContentQuality(Element element) {
        int score = calculateContentQuality(element);
        
        // 额外的现代网页特征检测
        String className = element.className().toLowerCase();
        String id = element.id().toLowerCase();
        
        // React/Vue组件特征
        if (className.contains("component") || className.contains("widget") ||
            className.contains("module") || id.contains("app") || id.contains("root")) {
            score += 15;
        }
        
        // 检查是否包含丰富的交互元素
        int interactiveElements = element.select("button, input, select, textarea, form").size();
        if (interactiveElements > 0 && interactiveElements < 20) {
            score += Math.min(interactiveElements * 2, 10);
        } else if (interactiveElements >= 20) {
            // 过多交互元素可能是导航或工具栏
            score -= 10;
        }
        
        // 检查现代CSS框架的使用
        if (className.contains("bootstrap") || className.contains("material") ||
            className.contains("antd") || className.contains("element")) {
            score += 5;
        }
        
        // 检查视频、图片等媒体内容
        int mediaElements = element.select("img, video, audio, iframe").size();
        if (mediaElements > 0 && mediaElements <= 10) {
            score += Math.min(mediaElements, 5);
        }
        
        return score;
    }
    
    /**
     * 改进的容器主内容查找
     */
    private Element findContainerMainContent(Document doc) {
        String[] containerSelectors = {
            // 传统容器
            "#content", "#main", "#main-content", "#primary", "#main-container",
            ".content", ".main", ".main-content", ".primary", ".main-container",
            ".post", ".entry", ".blog-post", ".article", ".post-content", 
            ".entry-content", ".page-content", ".article-content", ".post-body",
            ".entry-body", ".content-body", ".story", ".body",
            
            // 现代框架容器
            ".container", ".container-fluid", ".row", ".col-main",
            ".layout-content", ".page-wrapper", ".content-area",
            ".site-content", ".main-area", ".content-main",
            
            // CMS和博客平台
            ".post-wrapper", ".article-wrapper", ".content-wrapper",
            ".single-post", ".blog-content", ".news-content",
            ".documentation", ".docs-content", ".wiki-content"
        };
        
        Element bestElement = null;
        int maxQuality = 0;
        
        for (String selector : containerSelectors) {
            Elements elements = doc.select(selector);
            for (Element element : elements) {
                int quality = calculateEnhancedContentQuality(element);
                if (quality > maxQuality) {
                    maxQuality = quality;
                    bestElement = element;
                }
            }
        }
        
        return bestElement;
    }
    
    /**
     * 基于内容密度查找主内容
     */
    private Element findMainContentByDensity(Document doc) {
        Elements allDivs = doc.select("div, section, article");
        
        Element bestElement = null;
        double maxDensity = 0;
        
        for (Element div : allDivs) {
            double density = calculateTextDensity(div);
            if (density > maxDensity && calculateContentQuality(div) > 20) {
                maxDensity = density;
                bestElement = div;
            }
        }
        
        return bestElement;
    }
    
    /**
     * 查找最大文本块
     */
    private Element findLargestTextBlock(Document doc) {
        Elements allElements = doc.select("div, section, article, main");
        
        Element largest = null;
        int maxTextLength = 0;
        
        for (Element element : allElements) {
            int textLength = element.text().length();
            if (textLength > maxTextLength) {
                maxTextLength = textLength;
                largest = element;
            }
        }
        
        return largest != null ? largest : doc.body();
    }
    
    /**
     * 改进的内容质量分数计算
     */
    private int calculateContentQuality(Element element) {
        int score = 0;
        
        // 基于文本长度（改进权重）
        int textLength = element.text().length();
        if (textLength > 3000) score += 100;
        else if (textLength > 1500) score += 80;
        else if (textLength > 800) score += 60;
        else if (textLength > 400) score += 40;
        else if (textLength > 100) score += 20;
        else score += textLength / 10;
        
        // 基于段落数量（提高权重）
        int paragraphCount = element.select("p").size();
        score += paragraphCount * 12;
        
        // 基于标题数量（层次结构重要性）
        int h1Count = element.select("h1").size();
        int h2Count = element.select("h2").size();
        int h3Count = element.select("h3").size();
        int h4Count = element.select("h4").size();
        
        score += h1Count * 20 + h2Count * 15 + h3Count * 10 + h4Count * 5;
        
        // 基于列表数量
        int listCount = element.select("ul, ol").size();
        int listItemCount = element.select("li").size();
        score += listCount * 8 + Math.min(listItemCount, 20) * 2;
        
        // 基于表格内容
        int tableCount = element.select("table").size();
        if (tableCount > 0 && tableCount <= 5) {
            score += tableCount * 10;
        }
        
        // 基于代码块
        int codeBlockCount = element.select("pre, code").size();
        if (codeBlockCount > 0 && codeBlockCount <= 10) {
            score += codeBlockCount * 5;
        }
        
        // 基于链接密度（改进算法）
        int linkCount = element.select("a").size();
        int linkTextLength = element.select("a").text().length();
        if (textLength > 0) {
            double linkDensity = (double) linkTextLength / textLength;
            if (linkDensity > 0.7) score -= 80;      // 导航密集区域
            else if (linkDensity > 0.5) score -= 50; // 链接过多
            else if (linkDensity > 0.3) score -= 20; // 适中
            else if (linkDensity > 0.1) score += 10; // 适量链接是好的
        }
        
        // 基于类名和ID（扩展识别）
        String className = element.className().toLowerCase();
        String id = element.id().toLowerCase();
        String tagName = element.tagName().toLowerCase();
        
        // 积极指标
        if (className.contains("content") || className.contains("main") || 
            className.contains("article") || className.contains("post") ||
            id.contains("content") || id.contains("main") || 
            id.contains("article") || id.contains("post")) {
            score += 25;
        }
        
        if (tagName.equals("main") || tagName.equals("article")) {
            score += 30;
        }
        
        // 消极指标
        if (className.contains("sidebar") || className.contains("nav") ||
            className.contains("menu") || className.contains("footer") ||
            className.contains("header") || className.contains("ad") ||
            id.contains("sidebar") || id.contains("nav") ||
            id.contains("menu") || id.contains("footer")) {
            score -= 50;
        }
        
        // 检查元素深度（避免过深嵌套）
        int depth = 0;
        Element current = element;
        while (current.parent() != null && depth < 20) {
            current = current.parent();
            depth++;
        }
        if (depth > 15) score -= 10;
        else if (depth < 5) score -= 5; // 过浅可能是body等
        
        return Math.max(0, score);
    }
    
    /**
     * 计算文本密度
     */
    private double calculateTextDensity(Element element) {
        String text = element.text();
        String html = element.html();
        
        if (html.length() == 0) return 0;
        
        return (double) text.length() / html.length();
    }
    
    /**
     * 改进的标题提取
     */
    private String extractTitle(Document doc) {
        // 优先级顺序尝试提取标题
        String title = null;
        
        // 1. 页面title标签
        title = doc.title();
        if (isValidTitle(title)) return cleanTitle(title);
        
        // 2. OpenGraph标题
        Element ogTitle = doc.selectFirst("meta[property=og:title]");
        if (ogTitle != null) {
            title = ogTitle.attr("content");
            if (isValidTitle(title)) return cleanTitle(title);
        }
        
        // 3. 第一个h1标签
        Element h1 = doc.selectFirst("h1");
        if (h1 != null) {
            title = h1.text();
            if (isValidTitle(title)) return cleanTitle(title);
        }
        
        // 4. 其他标题标签
        for (String tag : Arrays.asList("h2", "h3")) {
            Element heading = doc.selectFirst(tag);
            if (heading != null) {
                title = heading.text();
                if (isValidTitle(title)) return cleanTitle(title);
            }
        }
        
        return "提取的网页内容";
    }
    
    /**
     * 验证标题是否有效
     */
    private boolean isValidTitle(String title) {
        return title != null && 
               title.trim().length() > 3 && 
               title.trim().length() < 200 &&
               !title.toLowerCase().contains("404") &&
               !title.toLowerCase().contains("error");
    }
    
    /**
     * 清理标题
     */
    private String cleanTitle(String title) {
        return title.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[\\r\\n\\t]", " ");
    }
    
    /**
     * 改进的HTML到Markdown转换
     */
    private String htmlToMarkdown(String html, String title) {
        // 转换HTML为Markdown
        String markdown = markdownConverter.convert(html);
        
        // 添加标题
        if (title != null && !title.isEmpty()) {
            markdown = "# " + title + "\n\n" + markdown;
        }
        
        return markdown;
    }
    
    /**
     * Markdown后处理
     */
    private String postProcessMarkdown(String markdown) {
        if (markdown == null) return "";
        
        // 清理多余的空行
        markdown = markdown.replaceAll("\\n{3,}", "\n\n");
        
        // 移除行尾空格
        markdown = markdown.replaceAll("[ \\t]+\\n", "\n");
        
        // 确保列表项前有空行
        markdown = markdown.replaceAll("([^\\n])\\n([*\\-+] |\\d+\\. )", "$1\n\n$2");
        
        // 确保标题前有空行
        markdown = markdown.replaceAll("([^\\n])\\n(#{1,6} )", "$1\n\n$2");
        
        // 限制最终内容长度
        if (markdown.length() > MAX_CONTENT_LENGTH) {
            markdown = markdown.substring(0, MAX_CONTENT_LENGTH) + "\n\n...(内容已截断)";
        }
        
        return markdown.trim();
    }
    
    /**
     * 解码HTTP响应体，正确处理字符编码和压缩
     */
    private String decodeResponseBody(byte[] bodyBytes, HttpHeaders headers) {
        // 1. 检查并处理压缩编码
        byte[] decompressedBytes = decompressIfNeeded(bodyBytes, headers);
        
        // 2. 从Content-Type头中提取编码
        String charset = extractCharsetFromHeaders(headers);
        
        // 3. 如果没有找到编码，尝试从HTML内容中检测
        if (charset == null) {
            charset = detectCharsetFromHtml(decompressedBytes);
        }
        
        // 4. 如果还是没找到，尝试智能检测
        if (charset == null) {
            charset = detectCharsetByContent(decompressedBytes);
        }
        
        // 5. 使用检测到的编码或默认UTF-8解码
        if (charset == null) {
            charset = "UTF-8";
        }
        
        // 6. 尝试解码，如果失败则使用备用方案
        try {
            String result = new String(decompressedBytes, charset);
            // 检查是否有乱码字符
            if (containsGarbledText(result)) {
                logger.warning("检测到乱码，尝试其他编码");
                return tryAlternativeCharsets(decompressedBytes, charset);
            }
            return result;
        } catch (Exception e) {
            logger.warning("使用编码 " + charset + " 解码失败，尝试备用编码: " + e.getMessage());
            return tryAlternativeCharsets(decompressedBytes, charset);
        }
    }
    
    /**
     * 检测内容是否包含乱码字符
     */
    private boolean containsGarbledText(String text) {
        // 检查是否包含替换字符（乱码的常见标志）
        if (text.contains("\uFFFD")) {
            return true;
        }
        
        // 检查是否有过多的不可打印字符
        long unprintableCount = text.chars()
            .filter(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
            .count();
        
        return unprintableCount > text.length() * 0.1; // 超过10%是不可打印字符
    }
    
    /**
     * 尝试其他字符编码
     */
    private String tryAlternativeCharsets(byte[] bytes, String originalCharset) {
        String[] charsets = {"UTF-8", "ISO-8859-1", "GBK", "GB2312", "Big5", "Shift_JIS", "EUC-JP", "EUC-KR"};
        
        for (String charset : charsets) {
            if (charset.equals(originalCharset)) {
                continue; // 跳过已经尝试过的编码
            }
            
            try {
                String result = new String(bytes, charset);
                if (!containsGarbledText(result)) {
                    logger.info("成功使用备用编码: " + charset);
                    return result;
                }
            } catch (Exception e) {
                // 继续尝试下一个编码
            }
        }
        
        // 最后的备用方案：使用UTF-8并忽略错误
        logger.warning("所有编码尝试失败，使用UTF-8强制解码");
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    /**
     * 基于内容智能检测字符编码
     */
    private String detectCharsetByContent(byte[] bytes) {
        // 检查BOM
        if (bytes.length >= 3) {
            if (bytes[0] == (byte)0xEF && bytes[1] == (byte)0xBB && bytes[2] == (byte)0xBF) {
                return "UTF-8";
            }
        }
        if (bytes.length >= 2) {
            if (bytes[0] == (byte)0xFF && bytes[1] == (byte)0xFE) {
                return "UTF-16LE";
            }
            if (bytes[0] == (byte)0xFE && bytes[1] == (byte)0xFF) {
                return "UTF-16BE";
            }
        }
        
        // 简单的启发式检测
        int asciiCount = 0;
        int highBitCount = 0;
        int checkLength = Math.min(bytes.length, 1000);
        
        for (int i = 0; i < checkLength; i++) {
            int b = bytes[i] & 0xFF;
            if (b < 128) {
                asciiCount++;
            } else {
                highBitCount++;
            }
        }
        
        // 如果大部分是ASCII字符，可能是UTF-8或ISO-8859-1
        if (asciiCount > highBitCount * 2) {
            return "UTF-8";
        }
        
        return null; // 无法确定
    }
    
    /**
     * 根据Content-Encoding头解压缩内容
     */
    private byte[] decompressIfNeeded(byte[] bodyBytes, HttpHeaders headers) {
        Optional<String> contentEncoding = headers.firstValue("content-encoding");
        if (contentEncoding.isPresent()) {
            String encoding = contentEncoding.get().toLowerCase();
            logger.info("检测到内容编码: " + encoding);
            
            if ("gzip".equals(encoding)) {
                try {
                    return decompressGzip(bodyBytes);
                } catch (Exception e) {
                    logger.warning("Gzip解压失败: " + e.getMessage());
                    return bodyBytes;
                }
            } else if ("deflate".equals(encoding)) {
                try {
                    return decompressDeflate(bodyBytes);
                } catch (Exception e) {
                    logger.warning("Deflate解压失败: " + e.getMessage());
                    return bodyBytes;
                }
            }
        }
        return bodyBytes;
    }
    
    /**
     * Gzip解压缩
     */
    private byte[] decompressGzip(byte[] compressedData) throws IOException {
        try (java.util.zip.GZIPInputStream gzipInputStream = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(compressedData));
             java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            logger.info("Gzip解压成功，原始大小: " + compressedData.length + ", 解压后大小: " + outputStream.size());
            return outputStream.toByteArray();
        }
    }
    
    /**
     * Deflate解压缩
     */
    private byte[] decompressDeflate(byte[] compressedData) throws IOException {
        try (java.util.zip.InflaterInputStream inflaterInputStream = new java.util.zip.InflaterInputStream(
                new java.io.ByteArrayInputStream(compressedData));
             java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inflaterInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            logger.info("Deflate解压成功，原始大小: " + compressedData.length + ", 解压后大小: " + outputStream.size());
            return outputStream.toByteArray();
        }
    }
    
    /**
     * 从HTTP头中提取字符编码
     */
    private String extractCharsetFromHeaders(HttpHeaders headers) {
        Optional<String> contentType = headers.firstValue("content-type");
        if (contentType.isPresent()) {
            String ct = contentType.get().toLowerCase();
            Pattern charsetPattern = Pattern.compile("charset\\s*=\\s*([^;\\s]+)");
            Matcher matcher = charsetPattern.matcher(ct);
            if (matcher.find()) {
                String charset = matcher.group(1).trim().replace("\"", "");
                logger.info("从响应头检测到编码: " + charset);
                return charset;
            }
        }
        return null;
    }
    
    /**
     * 从HTML内容中检测字符编码
     */
    private String detectCharsetFromHtml(byte[] bodyBytes) {
        try {
            // 使用ASCII解码前1024字节来查找meta标签
            String htmlStart = new String(bodyBytes, 0, Math.min(1024, bodyBytes.length), StandardCharsets.US_ASCII);
            
            // 查找meta charset标签
            Pattern metaCharsetPattern = Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?([^\"'>\\s]+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = metaCharsetPattern.matcher(htmlStart);
            if (matcher.find()) {
                String charset = matcher.group(1).trim();
                logger.info("从HTML meta标签检测到编码: " + charset);
                return charset;
            }
            
            // 查找Content-Type meta标签
            Pattern metaContentTypePattern = Pattern.compile("<meta[^>]+content\\s*=\\s*[\"']?[^\"'>]*charset\\s*=\\s*([^\"'>\\s]+)", Pattern.CASE_INSENSITIVE);
            matcher = metaContentTypePattern.matcher(htmlStart);
            if (matcher.find()) {
                String charset = matcher.group(1).trim();
                logger.info("从HTML Content-Type meta标签检测到编码: " + charset);
                return charset;
            }
        } catch (Exception e) {
            logger.warning("从HTML检测编码失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 验证内容是否有效
     */
    private boolean isContentValid(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        
        // 基本长度检查
        if (content.length() < MIN_CONTENT_LENGTH) {
            return false;
        }
        
        // 检查是否包含有意义的内容
        String textContent = content.replaceAll("#", "").replaceAll("\\*", "").replaceAll("\\[", "").replaceAll("\\]", "").trim();
        if (textContent.length() < MIN_CONTENT_LENGTH / 3) {
            return false;
        }
        
        // 对于技术规范类内容，使用更宽松的验证标准
        String lowerContent = content.toLowerCase();
        boolean isTechnicalSpec = lowerContent.contains("specification") || 
                                 lowerContent.contains("w3c") ||
                                 lowerContent.contains("recommendation") ||
                                 lowerContent.contains("introduction") ||
                                 lowerContent.contains("abstract") ||
                                 lowerContent.contains("table of contents");
        
        if (isTechnicalSpec) {
            // 技术规范文档更宽松的验证
            long headingCount = content.lines().filter(line -> 
                line.trim().startsWith("#")).count();
            
            // 如果有足够的标题结构，即使段落数少也认为有效
            if (headingCount >= 3) {
                return true;
            }
            
            // 检查是否有列表结构
            long listCount = content.lines().filter(line -> 
                line.trim().startsWith("-") || line.trim().startsWith("*") ||
                line.trim().matches("\\d+\\..*")).count();
            
            if (listCount >= 5) {
                return true;
            }
        }
        
        // 检查段落数量 - 更宽松的判断
        long paragraphCount = content.lines().filter(line -> 
            line.trim().length() > 10 && !line.trim().startsWith("#")).count();
        
        boolean hasValidContent = paragraphCount >= MIN_PARAGRAPH_COUNT;
        
        // 如果内容很长，即使段落数少也认为有效
        if (content.length() > 1000) {
            hasValidContent = true;
        }
        
        // 对于W3C规范，如果包含目录或标准格式，也认为有效
        if (lowerContent.contains("table of contents") || 
            lowerContent.contains("1 introduction") ||
            content.contains("## ") || content.contains("### ")) {
            hasValidContent = true;
        }
        
        return hasValidContent;
    }
    
    /**
     * 延迟初始化Playwright
     */
    private void initializePlaywright() {
        if (isPlaywrightInitialized.get() || isClosed.get()) {
            return;
        }
        
        playwrightInitLock.lock();
        try {
            if (isPlaywrightInitialized.get() || isClosed.get()) {
                return;
            }
            
            logger.info("开始初始化Playwright");
            
            try {
                playwright = Playwright.create();
                
                // 配置浏览器选项
                BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(Arrays.asList(
                                "--disable-blink-features=AutomationControlled",
                                "--disable-dev-shm-usage",
                                "--disable-gpu",
                                "--no-sandbox",
                                "--disable-setuid-sandbox",
                                "--disable-web-security"
                        ))
                        .setTimeout(15000);
                
                browser = playwright.chromium().launch(launchOptions);
                isPlaywrightInitialized.set(true);
                
                logger.info("Playwright初始化完成");
            } catch (Exception e) {
                logger.severe("Playwright初始化失败: " + e.getMessage());
                cleanup();
            }
        } finally {
            playwrightInitLock.unlock();
        }
    }
    
    /**
     * 截图功能
     */
    public boolean takeScreenshot(String url, String outputPath) {
        if (!isPlaywrightInitialized.get()) {
            initializePlaywright();
        }
        
        if (browser == null) {
            logger.warning("Playwright未初始化，无法截图");
            return false;
        }
        
        BrowserContext context = null;
        Page page = null;
        
        try {
            context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(USER_AGENT)
                    .setViewportSize(1920, 1080));
            
            page = context.newPage();
            page.navigate(url);
            page.waitForTimeout(3000);
            
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get(outputPath))
                    .setFullPage(true));
            
            logger.info("截图成功: " + outputPath);
            return true;
            
        } catch (Exception e) {
            logger.warning("截图失败: " + e.getMessage());
            return false;
        } finally {
            if (page != null) page.close();
            if (context != null) context.close();
        }
    }
    
    /**
     * 保存到文件
     */
    public boolean saveToFile(String content, String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            
            Files.writeString(path, content, StandardCharsets.UTF_8);
            logger.info("文件保存成功: " + filePath);
            return true;
        } catch (IOException e) {
            logger.warning("保存文件失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 批量处理
     */
    public int processBatch(List<String> urls, String outputDir) {
        int successCount = 0;
        
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            try {
                logger.info("处理 " + (i+1) + "/" + urls.size() + ": " + url);
                
                String content = extractContent(url);
                
                String fileName = generateSafeFileName(url);
                String filePath = outputDir + "/" + fileName + ".md";
                
                if (saveToFile(content, filePath)) {
                    successCount++;
                }
                
                // 避免过快请求
                Thread.sleep(1000);
                
            } catch (Exception e) {
                logger.warning("处理URL失败: " + url + " - " + e.getMessage());
            }
        }
        
        logger.info("批量处理完成，成功处理 " + successCount + "/" + urls.size() + " 个URL");
        return successCount;
    }
    
    /**
     * 生成安全的文件名
     */
    private String generateSafeFileName(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            String path = uri.getPath();
            
            String fileName = host + path.replace("/", "_");
            fileName = fileName.replaceAll("[^a-zA-Z0-9.-]", "_").replaceAll("_{2,}", "_");
            
            if (fileName.length() > 100) {
                fileName = fileName.substring(0, 100);
            }
            
            return fileName;
        } catch (URISyntaxException e) {
            return "page_" + System.currentTimeMillis();
        }
    }
    
    /**
     * 清理资源
     */
    private void cleanup() {
        try {
            if (browser != null) {
                browser.close();
                browser = null;
            }
            if (playwright != null) {
                playwright.close();
                playwright = null;
            }
        } catch (Exception e) {
            logger.warning("清理资源时出错: " + e.getMessage());
        }
        isPlaywrightInitialized.set(false);
    }
    
    /**
     * 关闭提取器
     */
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            logger.info("开始关闭WebContentExtractor");
            
            playwrightInitLock.lock();
            try {
                cleanup();
                urlCache.clear();
                logger.info("WebContentExtractor已关闭");
            } finally {
                playwrightInitLock.unlock();
            }
        }
    }
    
    // Tool接口实现
    @Override
    public String getName() {
        return "extract_web_content";
    }

    @Override
    public String getDescription() {
        return "智能网页内容提取器，自动选择最优提取策略，支持静态和动态内容提取，大幅提高成功率和性能。";
    }

    @Override
    public List<String> getParameters() {
        return List.of("url");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of("url", "要提取内容的网页URL");
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of("url", "string");
    }

    @Override
    public ToolResponse<String> execute(Map<String, Object> args) {
        if (args == null || !args.containsKey("url") || !(args.get("url") instanceof String url)) {
            return ToolResponse.failure("参数错误，必须提供 url 且其类型为 String");
        }
        return execute(url);
    }

    public ToolResponse<String> execute(String url) {
        try {
            String content = extractContent(url);
            
            // 检查是否是失败消息
            if (isFailureContent(content)) {
                return ToolResponse.failure("Failed to extract content from URL: " + url + " " + content);
            }
            
            return ToolResponse.success(content);
        } catch (Exception e) {
            logger.warning("执行Web内容提取失败: " + e.getMessage());
            return ToolResponse.failure("Failed to extract content: " + e.getMessage());
        }
    }
    
    /**
     * 检查内容是否为失败消息
     */
    private boolean isFailureContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return true;
        }
        
        // 检查是否是错误消息格式
        return content.startsWith("# 网页内容提取失败") ||
               content.startsWith("页面获取失败") ||
               content.startsWith("页面内容为空") ||
               content.startsWith("动态页面提取失败") ||
               content.contains("无法提取网页内容，请检查URL是否可访问");
    }
    
    /**
     * 提取W3C规范内容（针对不同格式的W3C规范）
     */
    private String extractW3CSpecificationContent(Document doc, String url) {
        // 1. 提取标题
        String title = extractTitle(doc);

        // 2. 检查是否是<pre>格式的规范（如XPath）
        Element preBlock = doc.selectFirst("pre");
        if (preBlock != null) {
            String text = preBlock.text();
            // 检查是否是预格式化的规范内容
            if ((text.contains("1 Introduction") || text.matches(".*\\d+\\.\\s+[^\\n]+.*")) && 
                text.length() > 1000) {
                // For <pre> formatted specs, we treat the whole block as content
                // but convert it to markdown instead of plain text to preserve some structure.
                String markdown = htmlToMarkdown(preBlock.html(), title);
                return postProcessMarkdown(markdown);
            }
        }

        // 3. 对于标准HTML格式的W3C规范（如XSLT），使用专门的处理逻辑
        logger.info("处理标准HTML格式的W3C规范: " + url);
        
        // 清理文档，但保留重要结构
        cleanW3CDocument(doc);
        
        // 查找主内容
        Element mainContent = findW3CMainContent(doc);
        if (mainContent == null) {
            logger.info("W3C专用算法未找到主内容，使用通用算法");
            mainContent = findMainContentAdvanced(doc);
        }
        
        // 如果仍然没有找到合适的内容，尝试更宽松的策略
        if (mainContent == null || calculateContentQuality(mainContent) < 50) {
            logger.info("尝试更宽松的内容查找策略");
            mainContent = findW3CContentWithRelaxedCriteria(doc);
        }
        
        if (mainContent != null) {
            String cleanedHtml = mainContent.outerHtml();
            String markdown = htmlToMarkdown(cleanedHtml, title);
            String result = postProcessMarkdown(markdown);
            
            // 验证结果质量
            if (isW3CContentValid(result, url)) {
                return result;
            } else {
                logger.warning("提取的W3C内容质量不佳，尝试使用整个body");
                // 如果质量不佳，使用整个body但进行更彻底的清理
                return extractW3CFromBody(doc, title);
            }
        }
        
        // 最后的回退策略
        return extractW3CFromBody(doc, title);
    }

    /**
     * 使用更宽松的标准查找W3C内容
     */
    private Element findW3CContentWithRelaxedCriteria(Document doc) {
        // 寻找包含大量文本和结构的元素
        Elements candidates = doc.select("body, div, main, article, section");
        Element bestElement = null;
        int maxScore = 0;
        
        for (Element element : candidates) {
            int score = 0;
            String text = element.text();
            
            // 基本文本长度
            if (text.length() > 2000) score += 30;
            else if (text.length() > 1000) score += 20;
            else if (text.length() > 500) score += 10;
            
            // 结构完整性
            int headings = element.select("h1, h2, h3, h4, h5, h6").size();
            if (headings > 10) score += 25;
            else if (headings > 5) score += 15;
            else if (headings > 2) score += 10;
            
            // 段落数量
            int paragraphs = element.select("p").size();
            if (paragraphs > 20) score += 20;
            else if (paragraphs > 10) score += 15;
            else if (paragraphs > 5) score += 10;
            
            // 列表项
            int lists = element.select("ul, ol, li").size();
            if (lists > 5) score += 10;
            
            if (score > maxScore) {
                maxScore = score;
                bestElement = element;
            }
        }
        
        return bestElement;
    }

    /**
     * 从body提取W3C内容的回退方法
     */
    private String extractW3CFromBody(Document doc, String title) {
        Element body = doc.body();
        if (body == null) {
            return "# " + (title != null ? title : "W3C规范") + "\n\n无法提取页面内容";
        }
        
        // 移除明显的导航和无关元素
        body.select("nav, .nav, .navigation, .breadcrumb, .toc-nav").remove();
        body.select("script, style, noscript").remove();
        
        String cleanedHtml = body.html();
        String markdown = htmlToMarkdown(cleanedHtml, title);
        return postProcessMarkdown(markdown);
    }

    /**
     * W3C内容质量验证
     */
    private boolean isW3CContentValid(String content, String url) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        
        // 长度检查
        if (content.length() < 500) {
            return false;
        }
        
        // 检查是否包含规范相关的关键结构
        String lowerContent = content.toLowerCase();
        boolean hasSpecContent = lowerContent.contains("introduction") ||
                                lowerContent.contains("specification") ||
                                lowerContent.contains("abstract") ||
                                lowerContent.contains("table of contents");
        
        // 检查是否有足够的标题结构
        long headingCount = content.lines()
            .filter(line -> line.trim().startsWith("#"))
            .count();
        
        return hasSpecContent || headingCount >= 3;
    }

    /**
     * 格式化W3C纯文本规范内容为Markdown
     */
    private String formatW3CPlainText(String text, String title) {
        StringBuilder markdown = new StringBuilder();
        
        // 添加标题
        markdown.append("# ").append(title != null ? title : "W3C XPath Specification").append("\n\n");
        
        // 按行处理文本内容
        String[] lines = text.split("\n");
        boolean inCodeBlock = false;
        StringBuilder currentParagraph = new StringBuilder();
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmedLine = line.trim();
            
            // 跳过完全空的行，但保留段落分隔
            if (trimmedLine.isEmpty()) {
                if (currentParagraph.length() > 0) {
                    markdown.append(currentParagraph.toString().trim()).append("\n\n");
                    currentParagraph.setLength(0);
                }
                continue;
            }
            
            // 检测主要章节标题（数字+空格+标题文本，如"1 Introduction"）
            if (trimmedLine.matches("^\\d+(\\.\\d+)*\\s+[A-Z][A-Za-z\\s-]+$")) {
                // 先完成当前段落
                if (currentParagraph.length() > 0) {
                    markdown.append(currentParagraph.toString().trim()).append("\n\n");
                    currentParagraph.setLength(0);
                }
                
                // 计算标题级别
                String[] parts = trimmedLine.split("\\s+", 2);
                if (parts.length >= 2) {
                    String sectionNumber = parts[0];
                    int level = Math.min(sectionNumber.split("\\.").length, 6);
                    
                    // 添加章节标题
                    markdown.append("#".repeat(level)).append(" ").append(trimmedLine).append("\n\n");
                }
                continue;
            }
            
            // 检测子章节标题（缩进+大写字母开头）
            if (trimmedLine.matches("^[A-Z][A-Z\\s-]+$") && line.startsWith("  ")) {
                if (currentParagraph.length() > 0) {
                    markdown.append(currentParagraph.toString().trim()).append("\n\n");
                    currentParagraph.setLength(0);
                }
                markdown.append("### ").append(trimmedLine).append("\n\n");
                continue;
            }
            
            // 检测代码示例（明显缩进的行）
            if (line.startsWith("    ") || line.startsWith("\t")) {
                // 先完成当前段落
                if (currentParagraph.length() > 0) {
                    markdown.append(currentParagraph.toString().trim()).append("\n\n");
                    currentParagraph.setLength(0);
                }
                
                if (!inCodeBlock) {
                    markdown.append("```\n");
                    inCodeBlock = true;
                }
                markdown.append(line).append("\n");
                continue;
            } else if (inCodeBlock && !line.startsWith("    ") && !line.startsWith("\t")) {
                // 结束代码块
                markdown.append("```\n\n");
                inCodeBlock = false;
            }
            
            // 检测列表项
            if (trimmedLine.matches("^[-*•]\\s+.*") || trimmedLine.matches("^\\d+\\.\\s+.*")) {
                if (currentParagraph.length() > 0) {
                    markdown.append(currentParagraph.toString().trim()).append("\n\n");
                    currentParagraph.setLength(0);
                }
                markdown.append(trimmedLine).append("\n");
                continue;
            }
            
            // 处理普通段落文本
            if (trimmedLine.length() > 0) {
                // 检查是否是连续的行（段落）
                if (currentParagraph.length() > 0 && 
                    !trimmedLine.endsWith(".") && !trimmedLine.endsWith(":") && 
                    !Character.isUpperCase(trimmedLine.charAt(0))) {
                    // 继续当前段落
                    currentParagraph.append(" ").append(trimmedLine);
                } else if (currentParagraph.length() > 0 && 
                           (trimmedLine.endsWith(".") || trimmedLine.endsWith(":"))) {
                    // 结束当前段落
                    currentParagraph.append(" ").append(trimmedLine);
                    markdown.append(currentParagraph.toString().trim()).append("\n\n");
                    currentParagraph.setLength(0);
                } else {
                    // 新段落开始
                    if (currentParagraph.length() > 0) {
                        markdown.append(currentParagraph.toString().trim()).append("\n\n");
                        currentParagraph.setLength(0);
                    }
                    currentParagraph.append(trimmedLine);
                }
            }
        }
        
        // 完成最后的段落
        if (currentParagraph.length() > 0) {
            markdown.append(currentParagraph.toString().trim()).append("\n\n");
        }
        
        // 关闭任何打开的代码块
        if (inCodeBlock) {
            markdown.append("```\n\n");
        }
        
        // 清理多余的空行
        String result = markdown.toString().replaceAll("\n{3,}", "\n\n");
        
        return result.trim();
    }

    /**
     * 提取策略枚举
     */
    private enum ExtractionStrategy {
        STATIC_FIRST,   // 静态优先
        DYNAMIC_FIRST,  // 动态优先
        STATIC_ONLY     // 仅静态
    }

    /**
     * 查找W3C规范主内容
     */
    private Element findW3CMainContent(Document doc) {
        // W3C规范页面检查：XPath规范使用<pre>块格式
        Element preBlock = doc.selectFirst("pre");
        if (preBlock != null) {
            // 检查是否是<pre>格式的规范（如XPath）
            String text = preBlock.text();
            if (text.contains("1 Introduction") && (text.contains("XPath") || text.contains("specification"))) {
                return preBlock;
            }
        }

        // 对于其他W3C规范格式
        Element body = doc.body();
        if (body == null) return null;
        
        // 寻找包含规范内容的主要容器
        Elements contentElements = doc.select("div, pre, article, main, section");
        Element bestElement = null;
        int maxScore = 0;
        
        for (Element element : contentElements) {
            int score = 0;
            String text = element.text();
            String lowerText = text.toLowerCase();
            
            // 检查是否包含规范的章节标题（数字+点+标题格式）
            if (text.matches(".*\\d+\\.\\s+[^\\n]+.*") || text.matches(".*\\d+\\s+[A-Z][a-z]+.*")) {
                score += 30;
            }
            
            // 检查W3C规范相关关键词
            if (lowerText.contains("xpath")) {
                score += 20;
            }
            if (lowerText.contains("xslt") || lowerText.contains("stylesheet") || lowerText.contains("transform")) {
                score += 20;
            }
            if (lowerText.contains("xml") || lowerText.contains("namespace")) {
                score += 15;
            }
            
            // 检查技术规范通用关键词
            if (lowerText.contains("grammar") || lowerText.contains("syntax") || 
                lowerText.contains("expression") || lowerText.contains("function") ||
                lowerText.contains("element") || lowerText.contains("attribute") ||
                lowerText.contains("template") || lowerText.contains("pattern")) {
                score += 10;
            }
            
            // 检查是否包含目录结构
            if (lowerText.contains("table of contents") || lowerText.contains("introduction") ||
                element.select("h1, h2, h3").size() > 3) {
                score += 25;
            }
            
            // 检查HTML结构完整性
            int headingCount = element.select("h1, h2, h3, h4, h5, h6").size();
            if (headingCount > 5) {
                score += 20;
            }
            
            // 文本长度权重，但避免过分偏重长度
            score += Math.min(text.length() / 200, 25);
            
            // 检查元素类型和属性
            String tagName = element.tagName().toLowerCase();
            String className = element.className().toLowerCase();
            String id = element.id().toLowerCase();
            
            if (tagName.equals("main") || tagName.equals("article")) {
                score += 15;
            }
            if (className.contains("content") || className.contains("main") || 
                className.contains("specification") || className.contains("body")) {
                score += 15;
            }
            if (id.contains("content") || id.contains("main") || id.contains("body")) {
                score += 15;
            }
            
            if (score > maxScore) {
                maxScore = score;
                bestElement = element;
            }
        }
        
        return bestElement != null ? bestElement : body;
    }

    /**
     * 增强W3C规范内容
     */
    private String enhanceW3CContent(Element mainContent) {
        StringBuilder enhancedHtml = new StringBuilder();
        
        // 保留主要结构
        enhancedHtml.append("<div class=\"w3c-specification\">");
        
        // 处理标题
        Elements headings = mainContent.select("h1, h2, h3, h4, h5, h6");
        Elements paragraphs = mainContent.select("p");
        Elements lists = mainContent.select("ul, ol");
        Elements tables = mainContent.select("table");
        Elements codeBlocks = mainContent.select("pre, code");
        
        // 添加所有内容
        enhancedHtml.append(mainContent.outerHtml());
        
        enhancedHtml.append("</div>");
        
        return enhancedHtml.toString();
    }

    /**
     * 改进的文档清理（针对W3C规范）
     */
    private void cleanW3CDocument(Document doc) {
        // 移除W3C页面特定的导航和元数据，但保留目录
        doc.select(".navbar, .nav, .copyright, .footer").remove();
        
        // 移除页眉，但保留主要内容区域的标题
        doc.select("header:not(.main-header)").remove();
        
        // 移除W3C特定的链接和图标
        doc.select("a[href*='mailto:'], img[src*='w3c']").remove();
        
        // 移除状态信息和元数据，但保留规范内容
        doc.select(".head:not(:has(h1)):not(:has(h2))").remove();
        
        // 清理文档的其他元素
        cleanDocument(doc);
    }

    /**
     * 查找语义化主内容
     */
    private Element findSemanticMainContent(Document doc) {
        // HTML5语义化标签
        Element main = doc.selectFirst("main");
        if (main != null) return main;
        
        Element article = doc.selectFirst("article");
        if (article != null) return article;
        
        // role属性
        Element roleMain = doc.selectFirst("[role=main]");
        if (roleMain != null) return roleMain;
        
        Element roleArticle = doc.selectFirst("[role=article]");
        if (roleArticle != null) return roleArticle;
        
        return null;
    }
}
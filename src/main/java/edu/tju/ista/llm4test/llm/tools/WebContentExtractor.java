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

/**
 * 增强的网页内容提取器 - 结合了Playwright和flexmark-html2md
 * 支持并发使用，更好的错误处理和资源管理
 */
public class WebContentExtractor implements Tool<String> {
    private static final Logger logger = Logger.getLogger(WebContentExtractor.class.getName());
    
    // 用户代理字符串
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36";
    
    // 超时配置
    private static final int HTTP_TIMEOUT_SECONDS = 30;
    private static final int PLAYWRIGHT_WAIT_MS = 15000;
    private static final int PLAYWRIGHT_NAVIGATION_TIMEOUT = 20000;
    
    // 重试配置
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1000;
    
    // 内容限制
    private static final int MAX_CONTENT_LENGTH = 100000; // 100KB
    private static final int MAX_TITLE_LENGTH = 200;
    
    // HTTP客户端（用于静态页面）
    private final HttpClient httpClient;
    // Markdown转换器
    private final FlexmarkHtmlConverter markdownConverter;
    // Playwright实例
    private Playwright playwright;
    private Browser browser;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final ReentrantLock initLock = new ReentrantLock();
    
    // 缓存已访问的URL以避免重复处理
    private final Set<String> visitedUrls = Collections.synchronizedSet(new HashSet<>());
    
    /**
     * 增强的初始化提取器
     * @param setupPlaywright 是否设置Playwright（用于动态页面）
     */
    public WebContentExtractor(boolean setupPlaywright) {
        // 初始化HTTP客户端，增强配置
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .build();
        
        // 初始化Markdown转换器（GitHub风格）
        MutableDataSet options = new MutableDataSet();
        options.set(com.vladsch.flexmark.parser.Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                InsExtension.create(),
                SuperscriptExtension.create()
        ));

        // Create the converter with the options
        markdownConverter = FlexmarkHtmlConverter.builder(options).build();
        
        // 如果需要，初始化Playwright
        if (setupPlaywright) {
            setupPlaywright();
        }
    }
    
    /**
     * 线程安全的Playwright设置
     */
    private void setupPlaywright() {
        if (isInitialized.get() || isClosed.get()) {
            return;
        }
        
        initLock.lock();
        try {
            if (isInitialized.get() || isClosed.get()) {
                return;
            }
            
            logger.info("开始初始化Playwright");
            playwright = Playwright.create();

            // 配置浏览器选项，增强稳定性
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)  // 无头模式
                    .setArgs(Arrays.asList(
                            "--disable-blink-features=AutomationControlled",
                            "--disable-features=IsolateOrigins,site-per-process",
                            "--disable-dev-shm-usage",
                            "--disable-gpu",
                            "--no-sandbox",
                            "--disable-setuid-sandbox",
                            "--disable-web-security",
                            "--disable-features=VizDisplayCompositor"
                    ))
                    .setSlowMo(50)     // 减慢操作，增加稳定性
                    .setTimeout(30000); // 30秒启动超时

            // 启动Chromium浏览器
            browser = playwright.chromium().launch(launchOptions);
            isInitialized.set(true);
            
            logger.info("Playwright初始化完成");
        } catch (Exception e) {
            logger.severe("Playwright初始化失败: " + e.getMessage());
            e.printStackTrace();
            // 清理部分初始化的资源
            cleanup();
        } finally {
            initLock.unlock();
        }
    }
    
    /**
     * 增强的静态内容提取，支持重试和更好的错误处理
     */
    public String extractStaticContent(String url) {
        if (isClosed.get()) {
            throw new RuntimeException("WebContentExtractor已关闭");
        }
        
        // 检查是否已处理过此URL
        if (visitedUrls.contains(url)) {
            logger.info("URL已访问过，跳过: " + url);
            return "此URL已在之前处理过";
        }
        
        int retryCount = 0;
        while (retryCount <= MAX_RETRIES) {
            try {
                logger.info("开始提取静态内容: " + url + " (尝试 " + (retryCount + 1) + "/" + (MAX_RETRIES + 1) + ")");
                
                // 创建HTTP请求
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .header("Accept-Encoding", "gzip, deflate")
                        .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                        .GET()
                        .build();
                
                // 发送请求并获取响应
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                // 检查状态码
                if (response.statusCode() != 200) {
                    if (retryCount < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS);
                        retryCount++;
                        continue;
                    }
                    logger.warning("HTTP错误: " + response.statusCode() + " for URL: " + url);
                    return "页面获取失败: HTTP " + response.statusCode();
                }
                
                // 使用Jsoup解析并清理HTML
                String html = response.body();
                if (html == null || html.trim().isEmpty()) {
                    if (retryCount < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS);
                        retryCount++;
                        continue;
                    }
                    return "页面内容为空";
                }
                
                // 限制内容长度
                if (html.length() > MAX_CONTENT_LENGTH) {
                    html = html.substring(0, MAX_CONTENT_LENGTH) + "...(内容已截断)";
                }
                
                Document doc = Jsoup.parse(html, url);
                
                // 清理HTML
                String cleanedHtml = cleanHtml(doc);
                
                // 使用flexmark将HTML转换为Markdown
                String result = htmlToMarkdown(cleanedHtml, extractTitle(doc));
                
                // 标记URL已访问
                visitedUrls.add(url);
                
                logger.info("成功提取静态内容: " + url + " (长度: " + result.length() + ")");
                return result;
                
            } catch (IOException | InterruptedException | URISyntaxException e) {
                logger.warning("获取静态页面失败 (尝试 " + (retryCount + 1) + "): " + e.getMessage());
                if (retryCount < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
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
     * 增强的动态内容提取，支持更好的错误处理和资源管理
     */
    public String extractDynamicContent(String url, String waitSelector) {
        if (isClosed.get()) {
            throw new RuntimeException("WebContentExtractor已关闭");
        }
        
        // 确保Playwright已初始化
        if (!isInitialized.get()) {
            setupPlaywright();
        }
        
        if (browser == null) {
            throw new RuntimeException("Error: Playwright未初始化，无法获取动态页面");
        }
        
        // 检查是否已处理过此URL
        if (visitedUrls.contains(url)) {
            logger.info("URL已访问过，跳过: " + url);
            return "此URL已在之前处理过";
        }
        
        BrowserContext context = null;
        Page page = null;
        int retryCount = 0;
        
        while (retryCount <= MAX_RETRIES) {
            try {
                logger.info("开始提取动态内容: " + url + " (尝试 " + (retryCount + 1) + "/" + (MAX_RETRIES + 1) + ")");
                
                // 创建页面上下文
                context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent(USER_AGENT)
                        .setHasTouch(false)
                        .setLocale("zh-CN")
                        .setViewportSize(1920, 1080)
                        .setIgnoreHTTPSErrors(true)
                        .setJavaScriptEnabled(true)
                        .setBypassCSP(true));
                
                // 创建新页面
                page = context.newPage();
                
                // 设置超时
                page.setDefaultTimeout(PLAYWRIGHT_WAIT_MS);
                page.setDefaultNavigationTimeout(PLAYWRIGHT_NAVIGATION_TIMEOUT);
                
                // 监听页面错误
                page.onPageError(error -> {
                    logger.warning("页面JavaScript错误: " + error);
                });
                
                // 导航到目标URL
                try {
                    Response response = page.navigate(url, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.LOAD)
                            .setTimeout(PLAYWRIGHT_NAVIGATION_TIMEOUT));
                    
                    // 检查响应状态
                    if (response != null && response.status() >= 400) {
                        logger.warning("页面响应错误: " + response.status() + " for URL: " + url);
                        if (retryCount < MAX_RETRIES) {
                            Thread.sleep(RETRY_DELAY_MS);
                            retryCount++;
                            continue;
                        }
                        return "页面加载失败: HTTP " + response.status();
                    }
                } catch (PlaywrightException e) {
                    logger.warning("页面导航失败: " + e.getMessage());
                    if (retryCount < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS);
                        retryCount++;
                        continue;
                    }
                    return "页面导航失败: " + e.getMessage();
                }
                
                // 等待特定元素加载（如有指定）
                if (waitSelector != null && !waitSelector.isEmpty()) {
                    try {
                        page.waitForSelector(waitSelector, 
                                new Page.WaitForSelectorOptions().setTimeout(PLAYWRIGHT_WAIT_MS));
                    } catch (PlaywrightException e) {
                        logger.warning("等待选择器超时: " + waitSelector);
                        // 不要因为选择器超时而失败，继续处理
                    }
                }

                // 额外等待，确保JavaScript完全执行
                page.waitForTimeout(2000);

                // 执行滚动脚本
                executeScrollScript(page);

                // 等待网络空闲
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions()
                            .setTimeout(PLAYWRIGHT_WAIT_MS));
                } catch (PlaywrightException e) {
                    logger.warning("等待网络空闲超时，继续处理");
                }

                // 获取页面内容
                String html = page.content();
                if (html == null || html.trim().isEmpty()) {
                    if (retryCount < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS);
                        retryCount++;
                        continue;
                    }
                    return "页面内容为空";
                }
                
                // 限制内容长度
                if (html.length() > MAX_CONTENT_LENGTH) {
                    html = html.substring(0, MAX_CONTENT_LENGTH) + "...(内容已截断)";
                }
                
                String title = page.title();
                if (title != null && title.length() > MAX_TITLE_LENGTH) {
                    title = title.substring(0, MAX_TITLE_LENGTH) + "...";
                }
                
                // 使用Jsoup解析HTML
                Document doc = Jsoup.parse(html, url);
                
                // 清理HTML
                String cleanedHtml = cleanHtml(doc);
                
                // 使用flexmark将HTML转换为Markdown
                String result = htmlToMarkdown(cleanedHtml, title);
                
                // 标记URL已访问
                visitedUrls.add(url);
                
                logger.info("成功提取动态内容: " + url + " (长度: " + result.length() + ")");
                return result;
                
            } catch (Exception e) {
                logger.warning("获取动态页面失败 (尝试 " + (retryCount + 1) + "): " + e.getMessage());
                if (retryCount < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    retryCount++;
                } else {
                    return "页面获取失败: " + e.getMessage();
                }
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
        
        return "页面获取失败: 超过最大重试次数";
    }
    
    /**
     * 自动检测页面类型并获取内容
     * @param url 目标URL
     * @return Markdown格式的内容
     */
    public String extractContent(String url) {
        if (isClosed.get()) {
            throw new RuntimeException("WebContentExtractor已关闭");
        }
        
        logger.info("开始提取网页内容: " + url);
        
        // 优先使用动态提取，因为它更可靠
        String content = extractDynamicContent(url, null);
        
        logger.info("内容提取完成: " + url + " (长度: " + content.length() + ")");
        return content;
    }
    
    /**
     * 判断内容是否需要动态加载
     * @param content 已获取的内容
     * @return 是否需要动态加载
     */
    private boolean contentNeedsDynamicLoading(String content) {
        // 内容太短
        if (content.length() < 500) {
            return true;
        }
        
        // 包含常见的懒加载指示器或空内容标记
        if (content.contains("Loading...") || 
            content.contains("Please wait") || 
            content.contains("No JavaScript") ||
            content.contains("JavaScript is required") ||
            content.contains("Please enable JavaScript") ||
            Pattern.compile("\\bload\\b|\\bloading\\b", Pattern.CASE_INSENSITIVE).matcher(content).find()) {
            return true;
        }
        
        // 没有任何标题和段落
        if (!content.contains("#") && !content.contains("\n\n")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 增强的HTML清理，移除不必要的元素
     */
    private String cleanHtml(Document doc) {
        // 移除脚本、样式和SVG
        doc.select("script, style, svg, iframe, noscript, object, embed").remove();
        
        // 移除通常不包含主要内容的元素
        doc.select("nav, footer, .footer, .header, header, .navigation, .sidebar, .menu, .ad, "
                + ".advertisement, .banner, .cookie-notice, .popup, .modal, .overlay, "
                + ".social-share, .share-buttons, .comments, .comment-section, "
                + "[role=complementary], [role=banner], [role=navigation], [role=contentinfo]").remove();
        
        // 移除空元素
        Elements emptyElements = doc.select("p:empty, div:empty, span:empty, h1:empty, h2:empty, h3:empty, h4:empty, h5:empty, h6:empty");
        emptyElements.remove();
        
        // 移除类名或ID中包含特定关键词的元素
        doc.select("[class*=advert], [class*=banner], [class*=cookie], [class*=popup], "
                + "[class*=overlay], [class*=newsletter], [class*=social], [class*=share], "
                + "[id*=cookie], [id*=banner], [id*=ad], [id*=popup]").remove();
        
        // 移除隐藏元素
        doc.select("[style*=display:none], [style*=visibility:hidden], .hidden, .invisible").remove();
        
        // 尝试找到主要内容
        Element mainContent = findMainContent(doc);
        if (mainContent != null) {
            return mainContent.outerHtml();
        }

        // 如果找不到主要内容，则返回整个body的HTML
        return doc.body().html();
    }
    
    /**
     * 增强的主内容查找算法
     */
    private Element findMainContent(Document doc) {
        // 常见的主内容区域ID和类名
        String[] contentSelectors = {
            "#content", "#main", "#main-content", ".content", ".main", ".main-content",
            "article", ".article", "#article", "main", ".post", ".entry", ".blog-post",
            ".post-content", ".entry-content", ".page-content", "#page-content",
            ".story", ".body", ".main-article", "[role=main]", "[role=article]",
            ".article-content", ".post-body", ".entry-body", ".content-body",
            ".primary-content", ".main-container", ".content-container"
        };
        
        Element bestElement = null;
        int maxScore = 0;
        
        // 尝试各个选择器
        for (String selector : contentSelectors) {
            Elements elements = doc.select(selector);
            for (Element element : elements) {
                int score = calculateContentScore(element);
                if (score > maxScore) {
                    maxScore = score;
                    bestElement = element;
                }
            }
        }
        
        // 如果找到高分元素，返回它
        if (bestElement != null && maxScore > 100) {
            return bestElement;
        }
        
        // 如果找不到特定区域，分析所有div元素
        Elements divs = doc.select("div");
        maxScore = 0;
        
        for (Element div : divs) {
            int score = calculateContentScore(div);
            if (score > maxScore) {
                maxScore = score;
                bestElement = div;
            }
        }
        
        // 返回最高分的元素（如果分数足够高）
        if (bestElement != null && maxScore > 200) {
            return bestElement;
        }
        
        // 如果还是找不到合适的主内容，则返回整个body
        return doc.body();
    }
    
    /**
     * 计算内容元素的分数
     */
    private int calculateContentScore(Element element) {
        int score = 0;
        
        // 基于文本长度
        int textLength = element.text().length();
        score += textLength / 10; // 每10个字符1分
        
        // 基于子元素数量
        int childCount = element.children().size();
        score += childCount * 5; // 每个子元素5分
        
        // 基于段落数量
        int paragraphCount = element.select("p").size();
        score += paragraphCount * 10; // 每个段落10分
        
        // 基于标题数量
        int headingCount = element.select("h1, h2, h3, h4, h5, h6").size();
        score += headingCount * 15; // 每个标题15分
        
        // 基于列表数量
        int listCount = element.select("ul, ol").size();
        score += listCount * 8; // 每个列表8分
        
        // 惩罚嵌套过深的元素
        int depth = getElementDepth(element);
        if (depth > 10) {
            score -= (depth - 10) * 10;
        }
        
        return Math.max(0, score);
    }
    
    /**
     * 获取元素的嵌套深度
     */
    private int getElementDepth(Element element) {
        int depth = 0;
        Element parent = element.parent();
        while (parent != null) {
            depth++;
            parent = parent.parent();
        }
        return depth;
    }
    
    /**
     * 提取网页标题
     */
    private String extractTitle(Document doc) {
        // 尝试获取标题
        String title = doc.title();
        
        // 如果title为空或过短，尝试从h1标签获取
        if (title == null || title.trim().isEmpty() || title.length() < 3) {
            Element h1 = doc.selectFirst("h1");
            if (h1 != null) {
                title = h1.text();
            }
        }
        
        // 如果还是没有合适的标题，尝试从meta标签获取
        if (title == null || title.trim().isEmpty() || title.length() < 3) {
            Element metaTitle = doc.selectFirst("meta[property=og:title]");
            if (metaTitle != null) {
                title = metaTitle.attr("content");
            }
        }
        
        // 清理标题
        if (title != null) {
            title = title.trim();
            if (title.length() > MAX_TITLE_LENGTH) {
                title = title.substring(0, MAX_TITLE_LENGTH) + "...";
            }
        }
        
        return title != null && !title.isEmpty() ? title : "提取的网页内容";
    }
    
    /**
     * 使用flexmark-html2md将HTML转换为Markdown
     */
    private String htmlToMarkdown(String html, String title) {
        // 转换HTML为Markdown
        String markdown = markdownConverter.convert(html);
        
        // 在开头添加标题
        if (title != null && !title.isEmpty()) {
            markdown = "# " + title + "\n\n" + markdown;
        }
        
        // 清理转换结果
        markdown = cleanMarkdown(markdown);
        
        // 限制最终内容长度
        if (markdown.length() > MAX_CONTENT_LENGTH) {
            markdown = markdown.substring(0, MAX_CONTENT_LENGTH) + "\n\n...(内容已截断)";
        }
        
        return markdown;
    }
    
    /**
     * 增强的Markdown清理
     */
    private String cleanMarkdown(String markdown) {
        // 移除多余的空行（超过2个连续空行）
        markdown = markdown.replaceAll("\\n{3,}", "\n\n");
        
        // 移除多余的空格
        markdown = markdown.replaceAll("[ \\t]+\\n", "\n");
        
        // 移除行尾空格
        markdown = markdown.replaceAll("[ \\t]+$", "");
        
        // 确保列表项前有空行
        markdown = markdown.replaceAll("([^\\n])\\n([*\\-+] |\\d+\\. )", "$1\n\n$2");
        
        // 确保代码块前后有空行
        markdown = markdown.replaceAll("([^\\n])\\n```", "$1\n\n```");
        markdown = markdown.replaceAll("```\\n([^\\n])", "```\n\n$1");
        
        // 清理重复的分隔符
        markdown = markdown.replaceAll("(---+\\n){2,}", "---\n");
        
        // 确保标题前有空行
        markdown = markdown.replaceAll("([^\\n])\\n(#{1,6} )", "$1\n\n$2");
        
        return markdown.trim();
    }
    
    /**
     * 使用Playwright进行截图
     */
    public boolean takeScreenshot(String url, String outputPath) {
        if (isClosed.get()) {
            logger.warning("WebContentExtractor已关闭，无法截图");
            return false;
        }
        
        // 确保Playwright已初始化
        if (!isInitialized.get()) {
            setupPlaywright();
        }
        
        if (browser == null) {
            logger.warning("Playwright未初始化，无法截图");
            return false;
        }
        
        BrowserContext context = null;
        Page page = null;
        
        try {
            // 创建新的浏览器页面
            context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(USER_AGENT)
                    .setViewportSize(1920, 1080)
                    .setHasTouch(false)
                    .setJavaScriptEnabled(true)
                    .setIgnoreHTTPSErrors(true));
            
            page = context.newPage();
            page.setDefaultTimeout(PLAYWRIGHT_WAIT_MS);

            // 导航到目标URL
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.LOAD)
                    .setTimeout(PLAYWRIGHT_NAVIGATION_TIMEOUT));

            // 执行滚动脚本来触发懒加载
            executeScrollScript(page);

            // 等待额外时间确保懒加载内容加载完成
            page.waitForTimeout(2000);

            // 等待页面加载完成
            page.waitForLoadState(LoadState.NETWORKIDLE);
            
            // 截图并保存
            page.screenshot(new Page.ScreenshotOptions()
                    .setTimeout(PLAYWRIGHT_WAIT_MS)
                    .setPath(Paths.get(outputPath))
                    .setFullPage(true));
            
            logger.info("截图成功: " + outputPath);
            return true;
            
        } catch (Exception e) {
            logger.warning("截图失败: " + e.getMessage());
            return false;
        } finally {
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
     * 增强的页面滚动脚本
     */
    private void executeScrollScript(Page page) {
        try {
            // 执行滚动脚本，模拟人类滚动行为
            Object result = page.evaluate("async () => {\n" +
                    "  const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));\n" +
                    "  const totalHeight = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);\n" +
                    "  const viewportHeight = window.innerHeight;\n" +
                    "  let scrollTop = 0;\n" +
                    "  \n" +
                    "  console.log('开始滚动，总高度: ' + totalHeight + 'px');\n" +
                    "  \n" +
                    "  // 第一次快速滚动到20%处\n" +
                    "  scrollTop = Math.floor(totalHeight * 0.2);\n" +
                    "  window.scrollTo(0, scrollTop);\n" +
                    "  await delay(1000);\n" +
                    "  \n" +
                    "  // 慢慢滚动到底部\n" +
                    "  const scrollStep = Math.floor(viewportHeight / 3);\n" +
                    "  while (scrollTop < totalHeight) {\n" +
                    "    scrollTop += scrollStep;\n" +
                    "    window.scrollTo(0, scrollTop);\n" +
                    "    await delay(500);\n" +
                    "  }\n" +
                    "  \n" +
                    "  // 滚动到顶部\n" +
                    "  window.scrollTo(0, 0);\n" +
                    "  await delay(500);\n" +
                    "  \n" +
                    "  return {\n" +
                    "    totalHeight: totalHeight,\n" +
                    "    finalScrollTop: scrollTop\n" +
                    "  };\n" +
                    "}");

            // 再次等待网络空闲
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5000));

        } catch (Exception e) {
            logger.warning("执行滚动脚本时出错: " + e.getMessage());
        }
    }
    
    /**
     * 将提取的Markdown内容保存到文件
     */
    public boolean saveToFile(String markdown, String filePath) {
        try {
            // 确保目录存在
            Path path = Paths.get(filePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            // 写入文件
            Files.writeString(path, markdown, StandardCharsets.UTF_8);
            logger.info("文件保存成功: " + filePath);
            return true;
        } catch (IOException e) {
            logger.warning("保存文件失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 增强的批量处理
     */
    public int processBatch(List<String> urls, String outputDir) {
        if (isClosed.get()) {
            logger.warning("WebContentExtractor已关闭，无法进行批量处理");
            return 0;
        }
        
        int successCount = 0;
        
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            try {
                logger.info("处理 " + (i+1) + "/" + urls.size() + ": " + url);
                
                // 提取内容
                String markdown = extractContent(url);
                
                // 生成文件名
                String fileName = generateSafeFileName(url);
                String filePath = outputDir + "/" + fileName + ".md";
                
                // 保存文件
                if (saveToFile(markdown, filePath)) {
                    successCount++;
                    logger.info("成功保存到: " + filePath);
                }
                
                // 截图（可选）
                String screenshotPath = outputDir + "/" + fileName + ".png";
                takeScreenshot(url, screenshotPath);
                
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
            
            // 组合主机名和路径
            String fileName = host + path.replace("/", "_");
            
            // 移除非法字符
            fileName = fileName.replaceAll("[^a-zA-Z0-9.-]", "_").replaceAll("_{2,}", "_");
            
            // 限制长度
            if (fileName.length() > 100) {
                fileName = fileName.substring(0, 100);
            }
            
            return fileName;
        } catch (URISyntaxException e) {
            // 如果URL无效，使用时间戳作为文件名
            return "page_" + System.currentTimeMillis();
        }
    }
    
    /**
     * 清理部分初始化的资源
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
        isInitialized.set(false);
    }
    
    /**
     * 增强的资源关闭
     */
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            logger.info("开始关闭WebContentExtractor");
            
            initLock.lock();
            try {
                cleanup();
                
                // 清理访问记录
                visitedUrls.clear();
                
                logger.info("WebContentExtractor已关闭");
            } finally {
                initLock.unlock();
            }
        }
    }

    @Override
    public String getName() {
        return "extract_web_content";
    }

    @Override
    public String getDescription() {
        return "Enhanced web content extractor that can handle both static and dynamic web pages. " +
               "Supports concurrent usage, better error handling, and resource management.";
    }

    @Override
    public List<String> getParameters() {
        return List.of("url");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of("url", "The URL of the web page to extract content from.");
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
            String markdown = extractContent(url);
            return ToolResponse.success(markdown);
        } catch (Exception e) {
            logger.warning("执行Web内容提取失败: " + e.getMessage());
            return ToolResponse.failure("Failed to extract content: " + e.getMessage());
        }
    }
}
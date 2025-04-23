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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 网页内容提取器 - 结合了Playwright和flexmark-html2md
 * 可以从静态和动态网页获取内容并转换为Markdown格式
 */
public class WebContentExtractor implements Tool<String> {
    // 用户代理字符串
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.110 Safari/537.36";
    // 静态页面请求超时（秒）
    private static final int HTTP_TIMEOUT_SECONDS = 30;
    // 动态页面加载等待时间（毫秒）
    private static final int PLAYWRIGHT_WAIT_MS = 10000;
    // HTTP客户端（用于静态页面）
    private final HttpClient httpClient;
    // Markdown转换器
    private final FlexmarkHtmlConverter markdownConverter;
    // Playwright实例
    private Playwright playwright;
    private Browser browser;
    
    /**
     * 初始化提取器
     * @param setupPlaywright 是否设置Playwright（用于动态页面）
     */
    public WebContentExtractor(boolean setupPlaywright) {
        // 初始化HTTP客户端
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
     * 设置Playwright
     */
    private void setupPlaywright() {
        try {
            playwright = Playwright.create();

            // 配置浏览器选项
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)  // 无头模式
                    .setArgs(Arrays.asList(
                                    "--disable-blink-features=AutomationControlled",
                                    "--disable-features=IsolateOrigins,site-per-process"))
                    .setSlowMo(50);     // 减慢操作，增加稳定性

            // 启动Chromium浏览器
            browser = playwright.chromium().launch(launchOptions);

        } catch (Exception e) {
            System.err.println("Playwright初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 关闭Playwright资源
     */
    public void close() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }
    
    /**
     * 获取静态网页内容并转换为Markdown
     * @param url 目标URL
     * @return Markdown格式的内容
     */
    public String extractStaticContent(String url) {
        try {
            // 创建HTTP请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .GET()
                    .build();
            
            // 发送请求并获取响应
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            // 检查状态码
            if (response.statusCode() != 200) {
                System.err.println("HTTP错误: " + response.statusCode());
                return "页面获取失败: HTTP " + response.statusCode();
            }
            
            // 使用Jsoup解析并清理HTML
            String html = response.body();
            Document doc = Jsoup.parse(html, url);
            
            // 清理HTML
            String cleanedHtml = cleanHtml(doc);
            
            // 使用flexmark将HTML转换为Markdown
            return htmlToMarkdown(cleanedHtml, extractTitle(doc));
            
        } catch (IOException | InterruptedException | URISyntaxException e) {
            System.err.println("获取静态页面失败: " + e.getMessage());
            return "页面获取失败: " + e.getMessage();
        }
    }
    
    /**
     * 使用Playwright获取动态网页内容并转换为Markdown
     * @param url 目标URL
     * @param waitSelector 等待元素加载的CSS选择器（可选）
     * @return Markdown格式的内容
     */
    public String extractDynamicContent(String url, String waitSelector) {
        if (browser == null) {
            throw new RuntimeException("Error: Playwright未初始化，无法获取动态页面");
        }
        
        Page page = null;
        try {
            // 创建页面上下文
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(USER_AGENT)
                    .setHasTouch(false)
                    .setLocale("en-US")
                    .setViewportSize(1920, 1080));
            
            // 创建新页面
            page = context.newPage();
            
            // 设置超时
            page.setDefaultTimeout(PLAYWRIGHT_WAIT_MS);
            
            // 导航到目标URL
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE)
                    .setTimeout(PLAYWRIGHT_WAIT_MS));
            
            // 等待特定元素加载（如有指定）
            if (waitSelector != null && !waitSelector.isEmpty()) {
                page.waitForSelector(waitSelector, 
                        new Page.WaitForSelectorOptions().setTimeout(PLAYWRIGHT_WAIT_MS));
            }

            // 额外等待，确保JavaScript完全执行
            page.waitForTimeout(1000);

            executeScrollScript(page);

            page.waitForLoadState(LoadState.NETWORKIDLE);

            // 获取页面内容
            String html = page.content();
            String title = page.title();
            
            // 使用Jsoup解析HTML
            Document doc = Jsoup.parse(html, url);
            
            // 清理HTML
            String cleanedHtml = cleanHtml(doc);
            
            // 使用flexmark将HTML转换为Markdown
            return htmlToMarkdown(cleanedHtml, title);
            
        } catch (Exception e) {
//            System.err.println("获取动态页面失败: " + e.getMessage());
            e.printStackTrace();
            if (page != null) {
                page.close();
            }
            throw new RuntimeException("页面获取失败: " + e.getMessage());
        } finally {
            if (page != null) {
                page.close();
            }
        }
    }
    
    /**
     * 自动检测页面类型并获取内容
     * @param url 目标URL
     * @return Markdown格式的内容
     */
    public String extractContent(String url) {
//        System.out.println("尝试提取网页内容: " + url);
        
//        // 先尝试静态获取
//        String content = extractStaticContent(url);
//
//        // 检查是否获取到了有效内容
//        if (contentNeedsDynamicLoading(content) && browser != null) {
//            System.out.println("静态内容看起来不完整，尝试动态加载...");
        String content = extractDynamicContent(url, null);
//        }
        
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
     * 清理HTML，移除不必要的元素
     * @param doc Jsoup文档
     * @return 清理后的HTML
     */
    private String cleanHtml(Document doc) {
        // 移除脚本、样式和SVG
        doc.select("script, style, svg, iframe, noscript").remove();
        
        // 移除通常不包含主要内容的元素
        doc.select("nav, footer, .footer, .header, header, .navigation, .sidebar, .menu, .ad, "
                + ".advertisement, .banner, .cookie-notice, .popup, .modal, .overlay, "
                + "[role=complementary], [role=banner], [role=navigation]").remove();
        
        // 移除空元素
        Elements emptyElements = doc.select("p:empty, div:empty, span:empty");
        emptyElements.remove();
        
        // 移除类名中包含特定关键词的元素
        doc.select("[class*=advert], [class*=banner], [class*=cookie], [class*=popup], "
                + "[class*=overlay], [class*=newsletter], [id*=cookie], [id*=banner]").remove();
        
        // 尝试找到主要内容
        Element mainContent = findMainContent(doc);
        if (mainContent != null) {
            // 只保留主要内容元素的HTML
            return mainContent.outerHtml();
        }

        // 如果找不到主要内容，则返回整个body的HTML
        return doc.body().html();
    }
    
    /**
     * 尝试找出网页的主要内容区域
     * @param doc HTML文档
     * @return 主要内容元素
     */
    private Element findMainContent(Document doc) {
        // 常见的主内容区域ID和类名
        String[] contentSelectors = {
            "#content", "#main", "#main-content", ".content", ".main", ".main-content",
            "article", ".article", "#article", "main", ".post", ".entry", ".blog-post",
            ".post-content", ".entry-content", ".page-content", "#page-content",
            ".story", ".body", ".main-article", "[role=main]", "[role=article]",
            ".container .row", "#container", ".container"
        };
        
        // 尝试各个选择器
        for (String selector : contentSelectors) {
            Elements elements = doc.select(selector);
            if (!elements.isEmpty()) {
                // 找到最长的内容区域
                Element bestElement = null;
                int maxTextLength = 0;
                
                for (Element element : elements) {
                    int textLength = element.text().length();
                    if (textLength > maxTextLength) {
                        maxTextLength = textLength;
                        bestElement = element;
                    }
                }
                
                if (bestElement != null && maxTextLength > 200) {
                    return bestElement;
                }
            }
        }
        
        // 如果找不到特定区域，尝试找文本最多的div
        Elements divs = doc.select("div");
        if (!divs.isEmpty()) {
            Element bestDiv = null;
            int maxTextLength = 0;
            
            for (Element div : divs) {
                int textLength = div.text().length();
                // 必须是合理的内容长度并包含子元素
                if (textLength > maxTextLength && textLength > 500 && div.children().size() > 3) {
                    maxTextLength = textLength;
                    bestDiv = div;
                }
            }
            
            if (bestDiv != null) {
                return bestDiv;
            }
        }
        
        // 如果还是找不到合适的主内容，则返回整个body
        return doc.body();
    }
    
    /**
     * 提取网页标题
     * @param doc HTML文档
     * @return 网页标题
     */
    private String extractTitle(Document doc) {
        // 尝试获取标题
        String title = doc.title();
        
        // 如果title为空，尝试从h1标签获取
        if (title == null || title.isEmpty()) {
            Element h1 = doc.selectFirst("h1");
            if (h1 != null) {
                title = h1.text();
            }
        }
        
        return title != null ? title : "提取的网页内容";
    }
    
    /**
     * 使用flexmark-html2md将HTML转换为Markdown
     * @param html HTML内容
     * @param title 网页标题
     * @return Markdown内容
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
        
        return markdown;
    }
    
    /**
     * 清理Markdown内容
     * @param markdown 原始Markdown
     * @return 清理后的Markdown
     */
    private String cleanMarkdown(String markdown) {
        // 移除多余的空行（超过2个连续空行）
        markdown = markdown.replaceAll("\\n{3,}", "\n\n");
        
        // 移除多余的空格
        markdown = markdown.replaceAll("[ \\t]+\\n", "\n");
        
        // 确保列表项前有空行
        markdown = markdown.replaceAll("([^\\n])\\n([*\\-+] |\\d+\\. )", "$1\n\n$2");
        
        // 确保代码块前后有空行
        markdown = markdown.replaceAll("([^\\n])\\n```", "$1\n\n```");
        markdown = markdown.replaceAll("```\\n([^\\n])", "```\n\n$1");
        
        return markdown;
    }
    
    /**
     * 使用Playwright进行截图
     * @param url 要截图的URL
     * @param outputPath 输出文件路径
     * @return 是否成功
     */
    public boolean takeScreenshot(String url, String outputPath) {
        if (browser == null) {
            System.err.println("Playwright未初始化，无法截图");
            return false;
        }
        
        Page page = null;
        try {
            // 创建新的浏览器页面
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(USER_AGENT)
                    .setViewportSize(1920, 1080)
                    .setHasTouch(false)
                    .setJavaScriptEnabled(true));
            
            page = context.newPage();


            // 导航到目标URL
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE));


            // 执行滚动脚本来触发懒加载
            System.out.println("执行滚动脚本...");
            executeScrollScript(page);

            // 等待额外时间确保懒加载内容加载完成
            page.waitForTimeout(1000);

            // 等待页面加载完成
            page.waitForLoadState(LoadState.NETWORKIDLE);
            // 截图并保存
            page.screenshot(new Page.ScreenshotOptions()
                    .setTimeout(PLAYWRIGHT_WAIT_MS)
                    .setPath(Paths.get(outputPath))
                    .setFullPage(true));
            
            return true;
        } catch (Exception e) {
//            System.err.println("截图失败: " + e.getMessage());
            return false;
        } finally {
            if (page != null) {
                page.close();
            }
        }
    }

    /**
     * 执行页面滚动以触发懒加载内容
     * @param page Playwright页面对象
     */
    private void executeScrollScript(Page page) {
        try {
            // 执行滚动脚本，模拟人类滚动行为
            page.evaluate("async () => {\n" +
                    "  const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));\n" +
                    "  const totalHeight = document.body.scrollHeight;\n" +
                    "  const viewportHeight = window.innerHeight;\n" +
                    "  let scrollTop = 0;\n" +
                    "  console.log('开始滚动，总高度: ' + totalHeight + 'px');\n" +
                    "  \n" +
                    "  // 第一次快速滚动到20%处\n" +
                    "  scrollTop = Math.floor(totalHeight * 0.2);\n" +
                    "  window.scrollTo(0, scrollTop);\n" +
                    "  console.log('快速滚动到: ' + scrollTop + 'px');\n" +
                    "  await delay(1234);\n" +
                    "  \n" +
                    "  // 慢慢滚动到底部\n" +
                    "  while (scrollTop < totalHeight) {\n" +
                    "    scrollTop += Math.floor(viewportHeight / 2);\n" +
                    "    window.scrollTo(0, scrollTop);\n" +
                    "    console.log('滚动到: ' + scrollTop + 'px');\n" +
                    "    await delay(567); // 每次滚动后稍等一下\n" +
                    "  }\n" +
                    "  \n" +
                    "  return document.body.scrollHeight;\n" +
                    "}");

            // 再次等待网络空闲
            page.waitForLoadState(LoadState.NETWORKIDLE);

        } catch (Exception e) {
//            System.err.println("执行滚动脚本时出错: " + e.getMessage());
        }
    }
    
    /**
     * 将提取的Markdown内容保存到文件
     * @param markdown Markdown内容
     * @param filePath 文件路径
     * @return 是否保存成功
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
            return true;
        } catch (IOException e) {
//            System.err.println("保存文件失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 批量处理多个URL
     * @param urls URL列表
     * @param outputDir 输出目录
     * @return 成功处理的URL数量
     */
    public int processBatch(List<String> urls, String outputDir) {
        int successCount = 0;
        
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            try {
                System.out.println("处理 " + (i+1) + "/" + urls.size() + ": " + url);
                
                // 提取内容
                String markdown = extractContent(url);
                
                // 生成文件名
                String fileName = generateSafeFileName(url);
                String filePath = outputDir + "/" + fileName + ".md";
                
                // 保存文件
                if (saveToFile(markdown, filePath)) {
                    successCount++;
                    System.out.println("成功保存到: " + filePath);
                }
                
                // 截图（可选）
                String screenshotPath = outputDir + "/" + fileName + ".png";
                takeScreenshot(url, screenshotPath);
                
                // 避免过快请求
                Thread.sleep(2000);
                
            } catch (Exception e) {
                System.err.println("处理URL失败: " + url);
                e.printStackTrace();
            }
        }
        
        return successCount;
    }
    
    /**
     * 生成安全的文件名
     * @param url URL
     * @return 安全的文件名
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
     * 主方法示例
     */
    public static void main(String[] args) {
        // 示例URL
        String url = "https://developers.google.com/api-client-library/java";
        
        // 读取命令行参数
        if (args.length > 0) {
            url = args[0];
        }
        
        String outputPath = "extracted_content.md";
        if (args.length > 1) {
            outputPath = args[1];
        }
        
        // 创建提取器
        WebContentExtractor extractor = new WebContentExtractor(true);
        
        try {
            // 获取内容
            System.out.println("开始提取网页内容: " + url);
            String markdown = extractor.extractContent(url);

            // 保存到文件
            extractor.saveToFile(markdown, outputPath);
            System.out.println("内容已保存到: " + outputPath);
            
            // 可选：获取页面截图
            String screenshotPath = outputPath.replace(".md", ".png");
            extractor.takeScreenshot(url, screenshotPath);
            System.out.println("截图已保存到: " + screenshotPath);
            
        } finally {
            // 关闭资源
            extractor.close();
            System.out.println("资源已释放");
        }
    }

    @Override
    public String getName() {
        return "Web browser";
    }

    @Override
    public String getDescription() {
        return "Get content from a web page and convert it to Markdown format.";
    }

    @Override
    public ToolResponse<String> execute(String url) {
        try {
            var markdown = extractContent(url);
            return ToolResponse.success(markdown);
        } catch (Exception e) {
            return ToolResponse.failure("Failed to extract content: " + e.getMessage());
        }
    }
}
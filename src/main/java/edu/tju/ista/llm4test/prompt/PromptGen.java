package edu.tju.ista.llm4test.prompt;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.StringWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class PromptGen {
    // FreeMarker 配置对象
    private static final Configuration CONFIGURATION;

    // 预定义模板存储
    private static final Map<String, String> TEMPLATE_MAP = new HashMap<>();

    // 常量存储
    private static String JQF_TUTORIAL;
    private static String THINKING_PROMPT;
    private static String THINKING_CLAUDE_PROMPT = "";
    private static String JMLExample;

    static {
        // 初始化 FreeMarker 配置
        CONFIGURATION = new Configuration(Configuration.VERSION_2_3_31);
        CONFIGURATION.setDefaultEncoding("UTF-8");

        // 从资源文件加载常量
        try {
            JQF_TUTORIAL = loadResourceAsString("/prompt/jqfTutorial.txt");
            THINKING_PROMPT = loadResourceAsString("/prompt/thinkingPrompt.txt");
            JMLExample = loadResourceAsString("/prompt/JMLExample.txt");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt constants from resources", e);
        }

        // 加载预定义模板
        try {
            TEMPLATE_MAP.put("default_prompt", loadResourceAsString("/prompt/defaultPrompt.txt"));
            TEMPLATE_MAP.put("FuzzDriver", loadResourceAsString("/prompt/FuzzDriver.txt"));
            TEMPLATE_MAP.put("SpecTest", loadResourceAsString("/prompt/SpecTest.txt"));
            TEMPLATE_MAP.put("EnhanceTestCase", loadResourceAsString("/prompt/EnhanceTestCase.txt"));
            TEMPLATE_MAP.put("ApiTest", loadResourceAsString("/prompt/ApiTest.txt"));
            TEMPLATE_MAP.put("FixTestCase", loadResourceAsString("/prompt/FixTestCase.txt"));
            TEMPLATE_MAP.put("ApplyChange", loadResourceAsString("/prompt/ApplyChange.txt"));
            TEMPLATE_MAP.put("RootCause", loadResourceAsString("/prompt/RootCause.txt"));
            TEMPLATE_MAP.put("jdk_doc_conformance_check", loadResourceAsString("/prompt/jdkDocConformanceCheck.txt"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt templates from resources", e);
        }
    }

    /**
     * 从类路径资源加载文件内容为字符串
     * @param resourcePath 资源路径
     * @return 文件内容
     * @throws IOException 如果读取失败
     */
    private static String loadResourceAsString(String resourcePath) throws IOException {
        try (InputStream inputStream = PromptGen.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Generates a prompt by applying the given template name and data model.
     *
     * @param templateName The name of the template to use (key from TEMPLATE_MAP).
     * @param dataModel    The data model to populate the template variables.
     * @return The generated prompt as a string.
     * @throws IllegalArgumentException If the template name is not found.
     * @throws IOException              If template processing fails.
     * @throws TemplateException        If template processing fails.
     */
    public static String generatePrompt(String templateName, Map<String, Object> dataModel)
            throws TemplateException, IOException {
        // Get the template string
        String templateString = TEMPLATE_MAP.get(templateName);
        if (templateString == null) {
            throw new IllegalArgumentException("Template not found: " + templateName);
        }
        dataModel.put("tutorial", JQF_TUTORIAL);
        dataModel.put("THINKING_CLAUDE_PROMPT", THINKING_CLAUDE_PROMPT);
        dataModel.put("THINKING_PROMPT", THINKING_PROMPT);
        dataModel.put("JMLExample", JMLExample);

        // Create the FreeMarker template
        Template template = new Template(templateName, templateString, CONFIGURATION);

        // Merge template with data model
        StringWriter writer = new StringWriter();
        try {
            template.process(dataModel, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return writer.toString();
    }

    public static String generateRootCausePrompt(String testCase, String testOutput) throws TemplateException, IOException {
        Map<String, Object> dataModel = new HashMap<>();
        dataModel.put("testcase", testCase);
        dataModel.put("testOutput", testOutput);
        return generatePrompt("RootCause", dataModel);
    }
}

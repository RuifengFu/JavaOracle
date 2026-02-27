package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.config.ConfigUtil;
import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.llm.agents.BugVerify;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentTest {

    @Test
    public void extractJsonTest() {
        // Test case for JSON extraction
        String json = "{\n" +
                "  \"hypotheses\": [\n" +
                "    {\n" +
                "      \"id\": \"H1\",\n" +
                "      \"description\": \"JDK 17中处理主机名不匹配方括号时错误消息前缀不符合预期，而JDK 21修复了该问题\",\n" +
                "      \"category\": \"JDK_BUG\",\n" +
                "      \"rationale\": \"根据[INFO_37]中URI类的解析逻辑，当主机名包含非法字符']'时，应抛出包含'Illegal character'的错误信息。但在JDK 17中可能错误地使用'Invalid host'前缀，导致测试失败。JDK 21可能修正了错误消息的生成逻辑。\",\n" +
                "      \"verificationCode\": \"try {\\n    new URL(\\\"http://invalid_host]:8080/path\\\");\\n    throw new RuntimeException(\\\"Expected exception not thrown\\\");\\n} catch (MalformedURLException e) {\\n    System.out.println(\\\"Actual message: \\\" + e.getMessage());\\n    if (!e.getMessage().startsWith(\\\"Illegal character found in host\\\")) {\\n        throw new RuntimeException(\\\"Message prefix mismatch in JDK \\\" + System.getProperty(\\\"java.version\\\"));\\n    }\\n}\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"id\": \"H2\",\n" +
                "      \"description\": \"测试用例对错误消息前缀的预期与JDK 17实际行为不符\",\n" +
                "      \"category\": \"TEST_ERROR\",\n" +
                "      \"rationale\": \"测试用例期望错误消息以'Illegal character found in host'开头，但JDK 17实际抛出消息可能以'Invalid host'开头（根据[INFO_37]的解析逻辑可能产生不同前缀）。测试用例基于JDK 21的错误消息格式编写，导致版本兼容性问题。\",\n" +
                "      \"verificationCode\": \"在JDK 17环境下运行测试方法testHostWithMismatchedBracket，捕获异常后打印实际错误消息，验证其是否以'Invalid host'或其他非预期前缀开头。\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"id\": \"H3\",\n" +
                "      \"description\": \"JDK 17的URL解析逻辑未正确检测主机名中的非法字符']'\",\n" +
                "      \"category\": \"JDK_BUG\",\n" +
                "      \"rationale\": \"根据[INFO_37]的URI解析规则，未闭合的方括号应被视为非法字符。但JDK 17可能在构造URL时未正确触发异常，或错误归类异常类型，导致测试未捕获预期异常。\",\n" +
                "      \"verificationCode\": \"在JDK 17中运行：\\ntry {\\n    new URI(\\\"http://invalid_host]:8080/path\\\");\\n    System.out.println(\\\"No exception in URI construction\\\");\\n} catch (URISyntaxException e) {\\n    System.out.println(\\\"URI异常消息: \\\" + e.getReason());\\n}\\n// 对比URL和URI类的处理差异\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        var result = BugVerify.extractJsonObjectArrayFromField(json, "hypotheses");
        for (String str: result) {
            System.out.println(str);
        }
    }

    @Test
    public void defaultWorkflowIsPipeline() {
        assertFalse(GlobalConfig.isLegacyEnhanceThenVerifyWorkflow());
    }

    @Test
    public void legacyWorkflowFlagCanBeSwitched() throws Exception {
        Field field = ConfigUtil.class.getDeclaredField("properties");
        field.setAccessible(true);
        Properties properties = (Properties) field.get(null);

        String key = "legacyEnhanceThenVerifyWorkflow";
        String oldValue = properties.getProperty(key);
        try {
            properties.setProperty(key, "true");
            assertTrue(GlobalConfig.isLegacyEnhanceThenVerifyWorkflow());

            properties.setProperty(key, "false");
            assertFalse(GlobalConfig.isLegacyEnhanceThenVerifyWorkflow());
        } finally {
            if (oldValue == null) {
                properties.remove(key);
            } else {
                properties.setProperty(key, oldValue);
            }
        }
    }
}

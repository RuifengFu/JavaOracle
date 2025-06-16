package edu.tju.ista.llm4test.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import org.junit.Test;

import java.io.IOException;
import java.util.logging.Level;

public class JsonTest {


    @Test
    public void tojson() {
        var llm_json = OpenAI.V3;

        var input = "{\n" +
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
        String prompt = """
                从以下文本中提取唯一的、完整的 JSON 对象或数组。
                严格只返回提取到的 JSON 内容，不要包含任何解释、代码标记（例如 ```json ```）或其他文本。
                确保输出是一个语法完全正确的 JSON 结构。

                Example Output:
                {
                    "hypotheses": [
                        {
                            "id": "H1",
                            "description": "JDK在MessageDigest.getInstance中使用传入的算法名称的大小写作为getAlgorithm()的返回值，而非标准化为大写形式。",
                            "category": "JDK_BUG",
                            "rationale": "测试失败显示实际算法名称为小写的'sha'，而预期为大写的'SHA'。若JDK未正确处理算法名称的大小写规范化，导致返回实例的算法名称与传入参数一致而非标准名称，则属于实现错误。",
                            "verificationCode": "MessageDigest md = MessageDigest.getInstance(\"sha\");\nSystem.out.println(md.getAlgorithm()); // 观察输出是否为'SHA'"
                        },
                        {
                            "id": "H2",
                            "description": "测试用例错误地假设getAlgorithm()返回的算法名称必须为大写，而API规范允许提供者返回任意大小写形式。",
                            "category": "TEST_ERROR",
                            "rationale": "Java API文档未明确要求getAlgorithm()必须返回大写名称，测试用例对大小写的严格校验可能不符合规范。若提供者返回小写名称是合法的，则测试用例存在逻辑错误。",
                            "verificationCode": "检查javadoc中MessageDigest.getAlgorithm()的规范，确认返回值是否保证标准化大小写。"
                        },
                        {
                            "id": "H3",
                            "description": "SUN提供者在特定JDK版本中将'SHA'算法注册为小写名称，导致getAlgorithm()返回'sha'。",
                            "category": "ENVIRONMENT_ISSUE",
                            "rationale": "若测试使用的安全提供者内部注册的算法名称实际为小写（如\"sha\"），则测试预期的大写形式'SHA'不成立，需检查提供者的注册配置。",
                            "verificationCode": "Provider p = Security.getProvider(\"SUN\");\nSystem.out.println(p.getService(\"MessageDigest\", \"SHA\")); // 查看注册的算法名称"
                        }
                    ]
                }

                请直接输出提取的 JSON:
                """.formatted(input);
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode json = objectMapper.readTree(input);
            System.out.println(json);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }


//        String jsonOutput = llm_json.messageCompletion(prompt);
    }
}

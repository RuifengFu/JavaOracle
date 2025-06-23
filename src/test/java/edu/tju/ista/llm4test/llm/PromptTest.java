package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.ApiInfoProcessor;
import freemarker.template.TemplateException;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class PromptTest {

    @Test
    public void genPrompt() throws IOException, TemplateException {
        File file = new File("JavaTest/jdk/javax/xml/crypto/dsig/Basic.java");
        var testCase = new TestCase(file);
        testCase.setOriginFile(file);
        testCase.setApiDocProcessor(new ApiInfoProcessor("JavaDoc/docs/api/java.base"));

        var dataModel = new HashMap<String, Object>();
        dataModel.put("apiDocs", testCase.getApiDoc());
        dataModel.put("testcase", testCase.getSourceCode());

        var prompt = PromptGen.generatePrompt("EnhanceTestCase", dataModel);
        System.out.println(prompt);
    }

    @Test
    public void fixPrompt() throws Exception {
        var testCase = new TestCase(new File("src/test/java/Comment.java"));
        testCase.setOriginFile(new File("JavaTest/jdk/java/util/zip/ZipFile/Comment.java"));
        testCase.setApiDocProcessor(ApiInfoProcessor.fromConfig());
        Map<String, Object> dataModel = new HashMap<>();
        dataModel.put("testcase", testCase.getTestcaseWithLineNumber());
        dataModel.put("originCase", testCase.originTestCase);
        dataModel.put("testOutput", "Starting ZIP comment test with various lengths...\n" +
                                    "Max comment length supported: 65535\n" +
                                    "Testing comment length: 0\n" +
                                    "Exception in thread \"main\" java.lang.Exception: ZIP file comment mismatch. Expected: '', but got: 'null'\n" +
                                    "\tat Comment.verifyZipFile(Comment.java:122)\n" +
                                    "\tat Comment.main(Comment.java:56)");
        dataModel.put("apiDocs", testCase.getApiDoc());
        dataModel.put("rootCause", "");
        String prompt = PromptGen.generatePrompt("FixTestCase", dataModel);
        System.out.println(prompt);
    }

}

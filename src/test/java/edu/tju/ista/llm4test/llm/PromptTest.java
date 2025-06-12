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

public class PromptTest {

    @Test
    public void genPrompt() throws IOException, TemplateException {
        File file = new File("jdk17u-dev/test/jdk/java/util/Calendar/bug4401223.java");
        var testCase = new TestCase(file);
        testCase.setOriginFile(file);
        testCase.setApiDocProcessor(new ApiInfoProcessor("JavaDoc/docs/api/java.base"));

        var dataModel = new HashMap<String, Object>();
        dataModel.put("apiDocs", testCase.getApiDoc());
        dataModel.put("testcase", testCase.getSourceCode());

        var prompt = PromptGen.generatePrompt("EnhanceTestCase", dataModel);
        System.out.println(prompt);
    }

}

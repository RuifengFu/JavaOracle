package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.ApiDocProcessor;
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

        var processor = new ApiDocProcessor("JavaDoc/docs/api/java.base");
        var docs = processor.processApiDocs(file);

        var dataModel = new HashMap<String, Object>();
        dataModel.put("apiDocs", docs);
        dataModel.put("testcase", FileUtils.readFileToString(file));

        var prompt = PromptGen.generatePrompt("EnhanceTestCase", dataModel);
        System.out.println(prompt);
    }
}

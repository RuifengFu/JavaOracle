package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.config.GlobalConfig;
import edu.tju.ista.llm4test.execute.TestCase;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.ApiInfoProcessor;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class TestCaseTest {


    @Test
    public void enhance() {
        File file = new File("jdk17u-dev/test/jdk/java/util/Calendar/bug4401223.java");
        var testCase = new TestCase(file);
        testCase.setOriginFile(file);
        testCase.setApiDocProcessor(new ApiInfoProcessor("JavaDoc/docs/api/java.base"));


        // copy from testcase.enhance()
        try {
            Map<String, Object> dataModel = new HashMap<>();
            dataModel.put("testcase", testCase.getTestcaseWithLineNumber());
            dataModel.put("apiDocs", testCase.getApiDoc());
            String prompt = PromptGen.generatePrompt("EnhanceTestCase", dataModel);
            String text = OpenAI.ThinkingModel.messageCompletion(prompt);
            ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
            System.out.println(text);

        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Enhancing test case failed: " + file + "\n" + e.getMessage());
        }


    }


    @Test
    public void apiInfoWithSource() {
        File file = new File("jdk17u-dev/test/jdk/java/awt/List/ListNullTest.java");
        var testCase = new TestCase(file);
        testCase.setOriginFile(file);
        testCase.setApiDocProcessor(new ApiInfoProcessor(GlobalConfig.getBaseDocPath(), GlobalConfig.getJdkSourcePath(), GlobalConfig.getDefaultSourcePrefix()));
        var res = testCase.
                getApiInfoWithSource();
        System.out.println(res);
    }

    @Test
    public void replaceComment() {
        File file = new File("jdk17u-dev/test/jdk/java/awt/List/ListNullTest.java");
        var testCase = new TestCase(file);
        System.out.println(testCase.getSourceCode());
        System.out.println(testCase.getSourceWithoutComment());
    }

    @Test
    public void removeHeader() {
        File file = new File("jdk17u-dev/test/jdk/java/awt/List/ListNullTest.java");
        var testCase = new TestCase(file);
        testCase.removeHeader();
        System.out.println(testCase.getSourceCode());
        System.out.println(testCase.getSourceWithoutComment());
    }
}

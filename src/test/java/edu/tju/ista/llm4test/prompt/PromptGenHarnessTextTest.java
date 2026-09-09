package edu.tju.ista.llm4test.prompt;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptGen 模板渲染回归测试：锁住 harness 相关文案，
 * 后续模板参数化（jtreg → ${harness.*}）时以此对比渲染结果不回退。
 */
class PromptGenHarnessTextTest {

    private static Map<String, Object> baseModel() {
        Map<String, Object> model = new HashMap<>();
        model.put("testcase", "public class T { }");
        model.put("testCase", "public class T { }");
        model.put("testCode", "public class T { }");
        model.put("originCase", "public class T { }");
        model.put("originTestcase", "public class T { }");
        model.put("modified", "public class T2 { }");
        model.put("apiDocs", "java.lang.String docs");
        model.put("testOutput", "exitValue: 2");
        model.put("rootCause", "possible cause");
        // 其余模板所需变量（FreeMarker缺失键会抛异常）
        model.put("initialAnalysis", "analysis");
        model.put("collectedInformation", "info");
        model.put("collectedInfos", "infos");
        model.put("feedback", "feedback");
        model.put("hypotheses", "h1");
        model.put("hypothesis", "h1");
        model.put("informationSources", "src");
        model.put("previousReport", "report");
        model.put("verificationResults", "vr");
        model.put("input", "input");
        model.put("symptoms", "symptom");
        model.put("currentCollectedInfos", "infos");
        model.put("queries", "q");
        model.put("relevantClasses", "java.lang.String");
        model.put("original_test_directory", "/tmp/t");
        model.put("source_code", "code");
        model.put("test_failure_output", "fail out");
        model.put("working_directory", "/tmp/w");
        model.put("current_code", "code");
        model.put("previous_feedback", "fb");
        model.put("test_file_path", "/tmp/T.java");
        model.put("bugArgument", "bug");
        model.put("testcaseArgument", "tc");
        model.put("directoryListing", "listing");
        model.put("originalTestPath", "/tmp/orig.java");
        model.put("testCaseSourceCode", "code");
        return model;
    }

    @Test
    void specTestContainsJtregConstraints() throws Exception {
        String prompt = PromptGen.generatePrompt("SpecTest", baseModel());
        assertNotNull(prompt);
        // JDK模式锁定点：SpecTest必须要求保持jtreg格式注释
        assertTrue(prompt.contains("jtreg"), "SpecTest应包含jtreg格式约束");
        assertTrue(prompt.contains("@test"), "SpecTest应包含@test tag示例");
        assertTrue(prompt.contains("@bug"), "SpecTest应包含@bug tag示例");
        assertTrue(prompt.contains("public class T { }"), "应渲染testcase变量");
    }

    @Test
    void harnessVariablesInjectedFromAdapter() throws Exception {
        // P1参数化后：harness.* 变量由适配器注入，JDK模式下渲染出原始文案
        String specTest = PromptGen.generatePrompt("SpecTest", baseModel());
        assertTrue(specTest.contains("4. **jtreg Format**"), "应渲染harness.name标题");
        assertTrue(specTest.contains("@bug 4160406 4705734 4707389 6358355 7032154"),
                "应渲染harness.tagExample: " + preview(specTest));

        String fix = PromptGen.generatePrompt("FixTestCase", baseModel());
        assertTrue(fix.contains("Preserve the `jtreg` format comments"));

        String plan = PromptGen.generatePrompt("TestCaseMinimizationPlan", baseModel());
        assertTrue(plan.contains("`jtreg_execute`"), "应渲染harness.executeToolName");
        assertTrue(plan.contains("\"tool\": \"jtreg_execute\","));

        String reduce = PromptGen.generatePrompt("TestCaseMinimizationReduce", baseModel());
        assertTrue(reduce.contains("jtreg tags(`@test`, `@bug`, `@summary`, `@run`, `@build`, `@library`, ...)"),
                "应渲染harness.name+tagList");

        String enhance = PromptGen.generatePrompt("EnhanceTestCase", baseModel());
        assertTrue(enhance.contains("`${harness.name}` tags".replace("${harness.name}", "jtreg"))
                || enhance.contains("`jtreg` tags"));
        assertFalse(enhance.contains("${harness."), "不应残留未替换变量");
    }

    @Test
    void fixTestCaseContainsJtregConstraints() throws Exception {
        String prompt = PromptGen.generatePrompt("FixTestCase", baseModel());
        assertNotNull(prompt);
        assertTrue(prompt.toLowerCase().contains("jtreg"), "FixTestCase应包含jtreg约束");
        assertTrue(prompt.contains("exitValue: 2"), "应渲染testOutput变量");
    }

    @Test
    void enhanceTestCaseRenders() throws Exception {
        String prompt = PromptGen.generatePrompt("EnhanceTestCase", baseModel());
        assertNotNull(prompt);
        assertTrue(prompt.contains("public class T { }"));
        assertTrue(prompt.contains("java.lang.String docs"));
    }

    @Test
    void applyChangeRenders() throws Exception {
        String prompt = PromptGen.generatePrompt("ApplyChange", baseModel());
        assertNotNull(prompt);
        assertTrue(prompt.contains("public class T { }"));
        assertTrue(prompt.contains("public class T2 { }"));
    }

    @Test
    void allTemplatesLoadable() {
        // 核心模板注册完整性（防止模板文件被误删/改名）
        for (String name : new String[]{
                "SpecTest", "EnhanceTestCase", "ApiTest", "FixTestCase", "ApplyChange", "RootCause",
                "BugVerifyInitialAnalysis", "BugVerifyFormHypotheses", "BugVerifyBugReport",
                "BugVerifyJsonExtract", "InstantiateTestCase", "BugVerifyObservePrompt",
                "BugVerifyRefineAnalysis", "TestCaseMinimizationPlan", "TestCaseMinimizationReduce",
                "VerdictAnalysis", "WorkspacePreparation"}) {
            assertDoesNotThrow(() -> PromptGen.generatePrompt(name, baseModel()),
                    "模板应可渲染: " + name);
        }
    }

    private static String preview(String s) {
        return s.substring(0, Math.min(400, s.length()));
    }
}
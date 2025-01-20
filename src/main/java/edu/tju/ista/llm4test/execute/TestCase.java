package edu.tju.ista.llm4test.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.functionCalling.FuncTool;
import edu.tju.ista.llm4test.llm.functionCalling.FuncToolFactory;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import edu.tju.ista.llm4test.utils.LoggerUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class TestCase {

    public File originFile;
    public String originTestCase;
    public String apiDocs;

    public File file;
    public TestResult result;

    public String verifyMessage = "";

    public TestCase(File file){
        this.file = file;
        this.result = new TestResult(TestResultKind.UNKNOWN);
    }

    public TestCase(File file, TestResult result){
        this.file = file;
        this.result = result;
    }

    public File getOriginFile() {
        return originFile;
    }

    public void setOriginFile(File originFile) {
        this.originFile = originFile;
        try {
            this.originTestCase = Files.readString(originFile.toPath());
        } catch (IOException e) {
            this.originTestCase = "";
        }
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    public TestResult getResult() {
        return result;
    }

    public void setResult(TestResult result) {
        this.result = result;
        if (result.isSuccess()) {
            try {
                originTestCase = Files.readString(file.toPath());
            } catch (Exception e) {

            }
        }
//        verifyTestFail();mv *
    }

    public void setApiDocs(String apiDocs) {
        this.apiDocs = apiDocs;
    }

    public void verifyTestFail() {
        if (!result.isFail()) {
            return;
        }
        try {
            String testcase = getTestcaseWithLineNumber();

            Map<String, Object> dataModel = new HashMap<>();
            dataModel.put("testcase", testcase);
            dataModel.put("testOutput", result.toString());
            dataModel.put("apiDocs", apiDocs);

            String prompt = PromptGen.generatePrompt("RootCause", dataModel);
            ArrayList<FuncTool> tools = new ArrayList<>();
            tools.add(FuncToolFactory.createRootCauseOutputFuncTool());
            var arguments = OpenAI.funcCall(prompt, "", tools).get("root_cause_analysis");
            var map = new ObjectMapper().readValue(arguments, Map.class);
            var reportBug = ((boolean)map.get("report_bug")) == true;
            if (reportBug) {
                this.result.setKind(TestResultKind.VERIFIED_BUG);
            } else {
                this.result.setKind(TestResultKind.MAYBE_TEST_FAIL);
            }
            verifyMessage = map.toString();
        } catch(Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Verifying test case failed: " + file + "\n" + e.getMessage());
            this.result.setKind(TestResultKind.MAYBE_TEST_FAIL);
        }
    }


    /**
     * Get the content of the testcase file
     * @return the content of the testcase file
     */
    public String getTestcaseWithLineNumber() {
        // read file into String
        String testcase = "";
        try {
            testcase = Files.readString(file.toPath());
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "Writing test case to file failed: " + file + "\n" + e.getMessage());
            e.printStackTrace();
        }
        // add line number, 大模型在分析代码的时候，没有行号大模型会出现幻觉。
        // 行号没有等宽，因为大模型不需要等宽
        String[] lines = testcase.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return testcase;
    }

    public void writeTestCaseToFile(String content) {
        try {
            Files.writeString(file.toPath(), content);
        } catch (IOException e) {
            LoggerUtil.logExec(Level.SEVERE, "Writing test case to file failed: " + file + "\n" + e.getMessage());
            e.printStackTrace();
        }
    }

    public void fix() {
        try {
            Map<String, Object> dataModel = new HashMap<>();
            dataModel.put("testcase", getTestcaseWithLineNumber());
            dataModel.put("originCase", originTestCase);
            dataModel.put("testOutput", result.toString());
            dataModel.put("apiDocs", apiDocs);
            dataModel.put("rootCause", verifyMessage);
            String prompt = PromptGen.generatePrompt("FixTestCase", dataModel);
            String text = OpenAI.messageCompletion(prompt);
            ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
            String generatedCode = codeBlocks.get(codeBlocks.size() - 1);
            applyChange(generatedCode);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Fixing test case failed: " + file + "\n" + e.getMessage());
        }
    }

    public void enhance() {
        try {
            Map<String, Object> dataModel = new HashMap<>();
            dataModel.put("testcase", getTestcaseWithLineNumber());
            dataModel.put("apiDocs", apiDocs);
            String prompt = PromptGen.generatePrompt("EnhanceTestCase", dataModel);
            String text = OpenAI.messageCompletion(prompt);
            ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
            String generatedCode = codeBlocks.get(codeBlocks.size() - 1);
            applyChange(generatedCode);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Enhancing test case failed: " + file + "\n" + e.getMessage());
        }
    }

    public void applyChange(String change){
        try {
            String testcase = getTestcaseWithLineNumber();
            Map<String, Object> dataModel = new HashMap<>();
            dataModel.put("originTestcase", originTestCase);
            dataModel.put("modified", change);
            String prompt = PromptGen.generatePrompt("ApplyChange", dataModel);
            String text = OpenAI.messageCompletion(prompt);
            ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
            String generatedCode = codeBlocks.get(codeBlocks.size() - 1);
            writeTestCaseToFile(generatedCode);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Applying change failed: " + file + "\n" + e.getMessage());
        }
    }







}

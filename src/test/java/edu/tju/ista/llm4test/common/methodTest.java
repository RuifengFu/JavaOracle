package edu.tju.ista.llm4test.common;

import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;
import edu.tju.ista.llm4test.execute.TestExecutor;
import edu.tju.ista.llm4test.execute.TestResult;
import edu.tju.ista.llm4test.javaparser.APISignatureExtractor;
import edu.tju.ista.llm4test.javaparser.JavaParser;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.RootCauseOutputTool;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import edu.tju.ista.llm4test.utils.HtmlParser;
import freemarker.template.TemplateException;
import org.jsoup.nodes.Document;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class methodTest {

    @Test
    public void testFilesToMethods() {
        File file = new File("JavaTest/jdk/java/util/ArrayList/AddAll.java");
        List<JavaMethod> methods = JavaParser.fileToMethods(file);

        for (JavaMethod method : methods) {
            System.out.println("方法: " + method.getName());
//            if (method.getComment() != null) {
//                System.out.println("注释: " + method.getComment());
//            }
//            System.out.println("方法签名: " + method.getDeclarationSignature(false));
//            System.out.println("源码: " + method.getSourceCode());
        }
    }

    @Test
    public void testMethod() {
        File file = new File("JavaTest/jdk/java/util/ArrayList/AddAll.java");
        List<JavaMethod> methods = JavaParser.fileToMethods(file);
        JavaMethod method = methods.stream().filter(m -> m.getName().equals("main")).limit(1).findAny().get(); // indexOf
        JavaClass focalClass = method.getDeclaringClass();


        System.out.println("方法: " + method.getName());
        System.out.println("-----------------------------------");

        StringBuilder method_info = new StringBuilder();

        method_info.append("Focal Class: ").append(focalClass);
//        method_info.append(focalClass.getSource().getCodeBlock());
        for (JavaMethod method2: methods.stream().limit(10).toList()) {
            method_info.append("Focal Method: ").append(method2.getDeclarationSignature(false)).append("\n");
            method_info.append("Source Code: ").append(method2.getSourceCode()).append("\n");
            method_info.append("Comments: ").append(method2.getComment()).append("\n");
        }


        Map<String, Object> objectMap = new HashMap<>();
        objectMap.put("method_info", method_info.toString());
        System.out.println(method_info.toString());
        try {
            String prompt = PromptGen.generatePrompt("FuzzDriver", objectMap);
            String text = OpenAI.FlashModel.messageCompletion(prompt);
//            System.out.println("Answer: \n" + text);
            ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
            for (String code: codeBlocks) {
                System.out.println("Code: \n" + code);
            }
        } catch (TemplateException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testApiParser() {
        Path arrayListTest = Path.of("JavaTest/jdk/java/util/ArrayList/AddAll.java");
        APISignatureExtractor extractor = new APISignatureExtractor();
        extractor.extractSignatures(String.valueOf(arrayListTest)).forEach(signature -> {
//            System.out.println(signature.getPackageName() + " " + signature.getClassName() + " " + signature.getMethodName());
            System.out.println(signature.getSignature());
//            System.out.println(signature.getQualifiedName());
        });

    }

    @Test
    public void testDocument() {
        Path arrayListTest = Path.of("JavaTest/jdk/java/util/ArrayList/AddAll.java");
        APISignatureExtractor extractor = new APISignatureExtractor();

        String base = "JavaDoc/docs/api/java.base";

        extractor.extractSignatures(String.valueOf(arrayListTest)).forEach(signature -> {
            System.out.println(signature.getPackageName() + " " + signature.getClassName() + " " + signature.getMethodName());
            try {
                Document doc = HtmlParser.getDocument(base, signature.getPackageName(), signature.getClassName());
                System.out.println(HtmlParser.getMethodDetails(doc).get(signature.getMethodName()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    public void testRootCause() throws TemplateException, IOException {
        String [] jars = new String[]{"C:\\Users\\Administrator\\.m2\\repository\\junit\\junit\\4.13.1\\junit-4.13.1.jar",
                "C:\\Users\\Administrator\\.m2\\repository\\org\\testng\\testng\\6.7\\testng-6.7.jar"};
        String jarPath = String.join(File.pathSeparator, jars);
        File ResultDir = new File("Results");
        TestExecutor executor = new TestExecutor();
        File file = new File("Results\\SetFromMap.java");
        TestResult result = executor.executeTest(file);

        // read file into String
        String testcase = "";
        try {
            testcase = Files.readString(file.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }

        Map<String, Object> dataModel = new HashMap<>();
        dataModel.put("testcase", testcase);
        dataModel.put("testOutput", result.toString());

        String prompt = PromptGen.generatePrompt("RootCause", dataModel);
        var call = OpenAI.FlashModel.toolCall(prompt, List.of(new RootCauseOutputTool())).get(0);
        var map = call.arguments;
        System.out.println(map);
        System.out.println(map.get("report_bug"));
        return ;
    }


}

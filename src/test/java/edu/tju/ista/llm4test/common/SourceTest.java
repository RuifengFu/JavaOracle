package edu.tju.ista.llm4test.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaMethod;
import edu.tju.ista.llm4test.javaparser.APISignatureExtractor;
import edu.tju.ista.llm4test.javaparser.JavaParser;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.functionCalling.FuncTool;
import edu.tju.ista.llm4test.llm.functionCalling.FuncToolFactory;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.HtmlParser;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import freemarker.template.TemplateException;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;

import static edu.tju.ista.llm4test.Main.traverseDir;

public class SourceTest {
    @Test
    public void FindMethodSource() {
        Path testCase = Path.of("JavaTest/jdk/java/util/ArrayList/AddAll.java");
        APISignatureExtractor extractor = new APISignatureExtractor();

        String base = "JavaDoc/docs/api/java.base";
        String srcBase = "jdk21-src/java.base";
        extractor.extractSignatures(String.valueOf(testCase)).forEach(signature -> {
            System.out.println(signature.getPackageName() + " " + signature.getClassName() + " " + signature.getMethodName());
            String srcPath = srcBase + "/" + signature.getPackageName().replace(".", "/") + "/" + signature.getClassName() + ".java";
            File srcFile = new File(srcPath);
            List<JavaMethod> methods = JavaParser.fileToMethods(srcFile);
            JavaMethod method = methods.stream().filter(m -> m.getName().equals(signature.getMethodName())).limit(1).findAny().get();

            System.out.println("方法: " + method.getName());
//            System.out.println("JavaDoc: " + method.getComment());
            System.out.println("Source Code: " + method.getSourceCode());
//            System.out.println("Modifiers : " +  method.getModifiers());
//            System.out.println("Signature: " + method.getDeclarationSignature(false));
//            System.out.println("Signature: " + method.getDeclarationSignature(true));



        });
    }



    @Test
    public void testJDK() throws TemplateException, IOException {
        String base = "jdk21-src/java.base/java";
        File baseDir = new File(base);

        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(20);

        for (File file : traverseDir(baseDir)) {
            if (file.isFile()) {
                List<JavaMethod> methods = null ;
                try {
                    methods = JavaParser.fileToMethods(file);
                } catch (Exception e) {
                    continue;
                }
                for (JavaMethod method : methods) {
                    executor.submit(
                            () -> {
                                try {
                                    System.out.println("方法: " + method.getName());
                                    if (method.getComment().equals("")) {
                                        return;
                                    }
                                    Map<String, Object> dataModel = new HashMap<>();
                                    dataModel.put("api_signature", method.getDeclarationSignature(true));
                                    dataModel.put("api_documentation", method.getComment());
                                    dataModel.put("api_source", method.getSourceCode());

                                    String prompt = PromptGen.generatePrompt("jdk_doc_conformance_check", dataModel);
                                    ArrayList<FuncTool> tools = new ArrayList<>();
                                    tools.add(FuncToolFactory.createJDKDocConformanceFuncTool());
                                    var arguments = OpenAI.Doubao.funcCall(prompt, tools).get("jdk_doc_conformance_check");
                                    var map = new ObjectMapper().readValue(arguments, Map.class);
                                    LoggerUtil.logResult(Level.INFO, file.getPath() + " " + method.getDeclarationSignature(false) + " " + map);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                    );
                }
            }
        }


        while (executor.getActiveCount() > 0) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        executor.shutdown();
    }
}

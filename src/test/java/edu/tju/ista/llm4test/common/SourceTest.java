package edu.tju.ista.llm4test.common;

import com.thoughtworks.qdox.model.JavaMethod;
import edu.tju.ista.llm4test.javaparser.APISignatureExtractor;
import edu.tju.ista.llm4test.javaparser.JavaParser;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

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




}

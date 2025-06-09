package edu.tju.ista.llm4test.common;

import com.thoughtworks.qdox.model.JavaMethod;
import edu.tju.ista.llm4test.javaparser.JavaParser;
import edu.tju.ista.llm4test.utils.ApiInfoProcessor;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class APIDocTest {
    @Test
    public void getApiDocs() throws IOException {
        File file = new File("JavaTest/jdk/javax/security/auth/callback/Mutability.java");
        List<JavaMethod> methods = JavaParser.fileToMethods(file);

        ApiInfoProcessor processor = new ApiInfoProcessor("JavaDoc/docs/api/java.base");
        var docs = processor.processApiDocs(file);
        System.out.println(docs);
    }
}

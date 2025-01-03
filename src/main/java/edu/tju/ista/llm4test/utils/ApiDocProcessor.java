package edu.tju.ista.llm4test.utils;


import edu.tju.ista.llm4test.javaparser.APISignatureExtractor;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.IOException;

public class ApiDocProcessor {
    private final String baseDocPath;
    private final APISignatureExtractor extractor;

    public ApiDocProcessor(String baseDocPath) {
        this.baseDocPath = baseDocPath;
        this.extractor = new APISignatureExtractor();
    }

    public String processApiDocs(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        extractor.extractSignatures(file.getPath()).forEach(signature -> {
            try {
                Document doc = HtmlParser.getDocument(baseDocPath,
                        signature.getPackageName(),
                        signature.getClassName());
                sb.append(signature.getSignature())
                        .append("\n")
                        .append(HtmlParser.getMethodDetails(doc).get(signature.getMethodName()))
                        .append("\n");
            } catch (IOException e) {
                // Handle exception
            }
        });
        return sb.toString();
    }
}
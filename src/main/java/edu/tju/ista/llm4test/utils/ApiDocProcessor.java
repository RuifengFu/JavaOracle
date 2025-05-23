package edu.tju.ista.llm4test.utils;


import edu.tju.ista.llm4test.javaparser.APISignatureExtractor;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ApiDocProcessor {
    private final String baseDocPath;
    private final APISignatureExtractor extractor;

    public ApiDocProcessor(String baseDocPath) {
        this.baseDocPath = baseDocPath;
        this.extractor = new APISignatureExtractor();
    }

    public Map<String, String> processApiDocs(File file) throws IOException {
        Map<String, String> map = new HashMap<>();
        extractor.extractSignatures(file.getPath()).forEach(signature -> {
            try {
                Document doc = HtmlParser.getDocument(baseDocPath,
                        signature.getPackageName(),
                        signature.getClassName());
                map.put(signature.getSignature(), HtmlParser.getMethodDetails(doc).get(signature.getMethodName()));
            } catch (IOException e) {
                // Handle exception
            }
        });
        return map;
    }
}
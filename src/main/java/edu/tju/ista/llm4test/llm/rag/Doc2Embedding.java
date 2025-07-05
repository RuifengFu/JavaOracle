package edu.tju.ista.llm4test.llm.rag;

import edu.tju.ista.llm4test.config.GlobalConfig;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.*;
import edu.tju.ista.llm4test.utils.FileUtils;
import edu.tju.ista.llm4test.utils.HtmlParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

public class Doc2Embedding {

    public static void main(String[] args) {
        var docPath = Path.of(GlobalConfig.getBaseDocPath()).getParent();
        List<File> docFiles = FileUtils.traverseDirectory(docPath.toFile()).stream().filter(x -> x.getName().endsWith(".html")).toList();
        var docs = docFiles.stream().map(file -> {
            try {
                return HtmlParser.getDocumentFromFile((File) docFiles);
            } catch (IOException e) {
                return null;
            }
        }).filter(Objects::nonNull).toList();

    }
}


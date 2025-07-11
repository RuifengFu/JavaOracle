package edu.tju.ista.llm4test.utils;

import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.parser.Parser;

import java.util.ArrayList;

public class CodeExtractor {

    /**
     * Extracts the code from the response body.
     * @param text The response text from the API in Markdown format.
     * @return The extracted code as a single string.
     */
    public static ArrayList<String> extractCode(String text) {
        // Parse the Markdown text
        Parser parser = Parser.builder().build();
        Node document = parser.parse(text);

        // Visitor to extract code blocks
        CodeBlockExtractorVisitor visitor = new CodeBlockExtractorVisitor();
        document.accept(visitor);

        // Combine all extracted code blocks into a single string
        return visitor.getCodeBlocks();
    }

    // Custom visitor to extract code blocks from the Markdown AST
    private static class CodeBlockExtractorVisitor extends AbstractVisitor {
        private final ArrayList<String> codeBlocks = new ArrayList<>();
        private int cnt = 0;
        private String lastBlock;

        public ArrayList<String> getCodeBlocks() {
            if (cnt == 1 && codeBlocks.isEmpty()) {
                codeBlocks.add(lastBlock); // if only one block, return it content
            }
            return codeBlocks;
        }

        @Override
        public void visit(FencedCodeBlock fencedCodeBlock) {
            // Append the code block content to the builder
            cnt += 1;
            if (fencedCodeBlock.getInfo().contains("java")) {
                codeBlocks.add(fencedCodeBlock.getLiteral());
            }
            lastBlock = fencedCodeBlock.getLiteral();
        }


    }


}
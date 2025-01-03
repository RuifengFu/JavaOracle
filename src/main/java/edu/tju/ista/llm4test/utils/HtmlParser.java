package edu.tju.ista.llm4test.utils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class HtmlParser {

    public static String getHeaderText(Document document) {
        Element header = document.select("div.header").first();
        return header != null ? header.text() : "";
    }

    public static String getClassDescriptionText(Document document) {
        Element classDescription = document.select("section.class-description").first();
        return classDescription != null ? classDescription.text() : "";
    }

    public static String getSummaryText(Document document) {
        Element summary = document.select("section.summary").first();
        return summary != null ? summary.text() : "";
    }

    public static String getNestedClassSummaryText(Document document) {
        Element nestedClassSummary = document.select("section.nested-class-summary").first();
        return nestedClassSummary != null ? nestedClassSummary.text() : "";
    }

    public static String getMethodSummaryText(Document document) {
        Element methodSummary = document.select("section.method-summary").first();
        return methodSummary != null ? methodSummary.text() : "";
    }

    public static Map<String, String> getMethodDetails(Document document) {
        Map<String, String> methodDetails = new HashMap<>();
        Elements details = document.select("section.method-details ul.member-list li section.detail");
        for (Element detail : details) {
            String methodName = Objects.requireNonNull(detail.select("h3").first()).text();
            String text = detail.text();
            methodDetails.put(methodName, text);
        }
        return methodDetails;
    }

    /*
        * Get the document from the API with the given prefix and signature.
        * @param prefix The prefix of the API URL.

        * @param signature The signature of the API method.

     */
    public static Document getDocument(String prefix, String packageName, String className) throws IOException {
        String separator = File.separator;
        String file = prefix + File.separator + packageName.replace(".", separator) + File.separator + className + ".html";
        return Jsoup.parse(new File(file), "UTF-8");
    }

    public static void main(String[] args) {
        // Example usage
        try {
            Document document = Jsoup.connect("http://example.com/api-docs").get();
            HtmlParser parser = new HtmlParser();

            System.out.println("Header Text: " + parser.getHeaderText(document));
            System.out.println("Class Description Text: " + parser.getClassDescriptionText(document));
            System.out.println("Summary Text: " + parser.getSummaryText(document));
            System.out.println("Nested Class Summary Text: " + parser.getNestedClassSummaryText(document));
            System.out.println("Method Summary Text: " + parser.getMethodSummaryText(document));

            Map<String, String> methodDetails = parser.getMethodDetails(document);
            for (Map.Entry<String, String> entry : methodDetails.entrySet()) {
                System.out.println("Method Detail ID: " + entry.getKey() + ", Text: " + entry.getValue());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
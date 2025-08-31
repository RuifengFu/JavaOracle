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
import java.util.logging.Level;

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
            if (methodDetails.containsKey(methodName)) {
                text = methodDetails.get(methodName) + "\n" + text;
                LoggerUtil.logExec(Level.FINE, "Polymorphic method detected: " + methodName + ", combining details.");
            }
            methodDetails.put(methodName, text);
        }
        return methodDetails;
    }

    /**
     * 获取构造函数详细信息
     * @param document Jsoup解析的HTML文档
     * @return 构造函数名称到详细信息的映射
     */
    public static Map<String, String> getConstructorDetails(Document document) {
        Map<String, String> constructorDetails = new HashMap<>();
        
        // 尝试不同的选择器来匹配构造函数详细信息
        Elements details = document.select("section.constructor-details ul.member-list li section.detail");
        
        // 如果上面的选择器没找到，尝试更通用的选择器
        if (details.isEmpty()) {
            details = document.select("section.details ul.member-list li section.detail");
            // 过滤出构造函数相关的detail
            Elements filteredDetails = new Elements();
            for (Element detail : details) {
                Element h3 = detail.select("h3").first();
                if (h3 != null) {
                    String heading = h3.text();
                    // 构造函数的标题通常是类名开头，而不是方法名
                    if (heading.contains("(") && !heading.contains(".")) {
                        filteredDetails.add(detail);
                    }
                }
            }
            details = filteredDetails;
        }
        
        // 如果还是没找到，尝试从构造函数摘要表格中提取
        if (details.isEmpty()) {
            Elements constructorRows = document.select("section.constructor-summary table tbody tr");
            for (Element row : constructorRows) {
                Element constructorCell = row.select("th.col-constructor-name, td.col-constructor-name").first();
                Element descCell = row.select("th.col-last, td.col-last").first();
                if (constructorCell != null && descCell != null) {
                    String constructorName = constructorCell.text().trim();
                    String description = descCell.text().trim();
                    constructorDetails.put(constructorName, description);
                }
            }
        } else {
            // 从详细信息section中提取
            for (Element detail : details) {
                Element h3 = detail.select("h3").first();
                if (h3 != null) {
                    String constructorName = h3.text();
                    String text = detail.text();
                    if (constructorDetails.containsKey(constructorName)) {
                        text = constructorDetails.get(constructorName) + "\n" + text;
                        LoggerUtil.logExec(Level.FINE, "Overloaded constructor detected: " + constructorName + ", combining details.");
                    }
                    constructorDetails.put(constructorName, text);
                }
            }
        }
        
        return constructorDetails;
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

    /**
     * 从文件直接获取HTML文档
     * @param file HTML文件
     * @return Jsoup Document对象
     * @throws IOException 如果文件读取失败
     */
    public static Document getDocumentFromFile(File file) throws IOException {
        return Jsoup.parse(file, "UTF-8");
    }

    public static void main(String[] args) {
        // Example usage
        try {
            Document document = Jsoup.connect("https://docs.oracle.com/javase/8/docs/api/java/util/ArrayList.html").get();

            System.out.println("Header Text: " + HtmlParser.getHeaderText(document));
            System.out.println("Class Description Text: " + HtmlParser.getClassDescriptionText(document));
            System.out.println("Summary Text: " + HtmlParser.getSummaryText(document));
            System.out.println("Nested Class Summary Text: " + HtmlParser.getNestedClassSummaryText(document));
            System.out.println("Method Summary Text: " + HtmlParser.getMethodSummaryText(document));

            Map<String, String> methodDetails = HtmlParser.getMethodDetails(document);
            for (Map.Entry<String, String> entry : methodDetails.entrySet()) {
                System.out.println("Method Detail ID: " + entry.getKey() + ", Text: " + entry.getValue());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
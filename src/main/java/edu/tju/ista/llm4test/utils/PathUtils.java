package edu.tju.ista.llm4test.utils;

public class PathUtils {
    public static String combinePaths(String basePath, String relativePath) {
        if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }
        return basePath + "/" + relativePath;
    }

    public static void main(String[] args) {
        String basePath = "test/jdk/java/util/ArrayList";
        String relativePath = "java/util/ArrayList/Bug6533203.java";
        String combinedPath = combinePaths(basePath, relativePath);
        System.out.println(combinedPath); // Output: test/jdk/java/util/ArrayList/Bug6533203.java
    }
}
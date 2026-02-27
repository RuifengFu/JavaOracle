package edu.tju.ista.llm4test.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaBaseClassCollector {
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\b(?:java|javax)\\.[\\w$.]+");

    public Set<String> collectClasses(List<File> files) {
        Set<String> classes = new TreeSet<>();
        for (File file : files) {
            try {
                Matcher matcher = CLASS_PATTERN.matcher(Files.readString(file.toPath()));
                while (matcher.find()) {
                    classes.add(matcher.group());
                }
            } catch (IOException ignored) {
            }
        }
        return classes;
    }

    public void writeToFile(Set<String> classNames, Path output) throws IOException {
        Files.write(output, classNames);
    }
}

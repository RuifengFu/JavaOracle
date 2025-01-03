package edu.tju.ista.llm4test.utils;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

public class FileProcessor {
    private final File resultDir;

    public FileProcessor(File resultDir) {
        this.resultDir = resultDir;
    }

    public void copyTestFiles(Path testSuitePath)  {
        Arrays.stream(Objects.requireNonNull(testSuitePath.toFile().listFiles()))
                .forEach(file -> {
                    try {
                        if (file.isFile()) {
                            FileUtils.copyFileToDirectory(file, resultDir);
                        } else {
                            FileUtils.copyDirectoryToDirectory(file, resultDir);
                        }
                    } catch (IOException e) {
                        // Handle exception
                    }
                });
    }

    @Deprecated
    public void writeTestFile(String className, String content) throws IOException {
        File outputFile = new File(resultDir, className + ".java");
        Files.writeString(outputFile.toPath(), content);
    }
}

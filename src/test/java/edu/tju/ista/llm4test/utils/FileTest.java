package edu.tju.ista.llm4test.utils;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

public class FileTest {
    @Test
    public void testFileProcessor() throws Exception {
        File testDir = new File("TempDir");
        if (testDir.exists()){
//            throw new RuntimeException("TempDir already exists");
        } else {
            testDir.mkdirs();
        }
        FileProcessor fileProcessor = new FileProcessor(testDir);
        fileProcessor.copyTestFiles(Path.of("JavaOracle/JavaTest/jdk/java/util"));
    }
}

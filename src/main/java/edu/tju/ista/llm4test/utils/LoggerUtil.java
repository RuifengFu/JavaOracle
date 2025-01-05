package edu.tju.ista.llm4test.utils;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class LoggerUtil {

    private static final Logger openaiLogger = Logger.getLogger("openaiLogger");
    private static final Logger execLogger = Logger.getLogger("execLogger");
    private static final Logger resultLogger = Logger.getLogger("resultLogger");

    static {
        try {
            // Setup openai.log
            FileHandler openaiFileHandler = new FileHandler("openai.log", true);
            openaiFileHandler.setFormatter(new SimpleFormatter());
            openaiLogger.addHandler(openaiFileHandler);
            openaiLogger.setLevel(Level.ALL);

            // Setup exec.log
            FileHandler execFileHandler = new FileHandler("exec.log", true);
            execFileHandler.setFormatter(new SimpleFormatter());
            execLogger.addHandler(execFileHandler);
            execLogger.setLevel(Level.ALL);

            // Setup result.log
            FileHandler resultFileHandler = new FileHandler("result.log", true);
            resultFileHandler.setFormatter(new SimpleFormatter());
            resultLogger.addHandler(resultFileHandler);
            resultLogger.setLevel(Level.ALL);
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT %4$s: %5$s%n"); // compact log style
    }

    public static void logOpenAI(Level level, String message) {
        openaiLogger.log(level, message);
    }
    public static void logExec(Level level, String message) {
        execLogger.log(level, message);
    }

    public static void logResult(Level level, String message) {
        resultLogger.log(level, message);
        execLogger.log(level, message); // exec.log contains result information
    }
}

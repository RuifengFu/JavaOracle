package edu.tju.ista.llm4test.utils;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class LoggerUtil {

    private static final Logger openaiLogger = Logger.getLogger("openaiLogger");
    private static final Logger execLogger = Logger.getLogger("execLogger");
    private static final Logger resultLogger = Logger.getLogger("resultLogger");
    private static final Logger verifyLogger = Logger.getLogger("verifyLogger");

    private static final DateTimeFormatter VERIFY_TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static class CompactLogFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String timestamp = VERIFY_TS_FORMATTER.format(Instant.ofEpochMilli(record.getMillis()));
            String level = record.getLevel().getName();
            String message = formatMessage(record)
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .trim();
            return String.format("%s %s %s%n", timestamp, level, message);
        }
    }

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

            // Setup verify.log (compact single-line format, no parent handlers)
            FileHandler verifyFileHandler = new FileHandler("verify.log", true);
            verifyFileHandler.setFormatter(new CompactLogFormatter());
            verifyLogger.addHandler(verifyFileHandler);
            verifyLogger.setUseParentHandlers(false);
            verifyLogger.setLevel(Level.ALL);
            
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT %4$s: %5$s%n"); // compact log style for default formatters
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

    public static void logVerify(Level level, String header, String message) {
        String compact = String.format(
                "VERIFY | %s | %s",
                header,
                message == null ? "" : message.replace('\n', ' ').replace('\r', ' ').trim()
        );
        verifyLogger.log(level, compact);
    }

    public static void logVerify(Level level, String message) {
        String compact = String.format(
                "VERIFY | %s",
                message == null ? "" : message.replace('\n', ' ').replace('\r', ' ').trim()
        );
        verifyLogger.log(level, compact);
    }
}

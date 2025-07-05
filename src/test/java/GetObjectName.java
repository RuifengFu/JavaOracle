
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.PlatformLoggingMXBean;
import java.lang.management.PlatformManagedObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.management.ObjectName;

public class GetObjectName {
    private static volatile boolean failed = false;
    // Track ObjectNames across threads for uniqueness check
    private static final Map<ObjectName, String> globalObjectNames = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        System.out.println("Starting test with " + Runtime.getRuntime().availableProcessors() + 
                         " available processors");

        int tasks = 10;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        submitTasks(executor, tasks);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        if (!failed) {
            System.out.println("Test passed.");
        }
    }

    static void submitTasks(ExecutorService executor, int count) {
        for (int i = 0; i < count && !failed; i++) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    List<PlatformManagedObject> mbeans = new ArrayList<>();
                    mbeans.add(ManagementFactory.getPlatformMXBean(PlatformLoggingMXBean.class));
                    mbeans.addAll(ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class));
                    for (PlatformManagedObject pmo : mbeans) {
                        // Name should not be null
                        ObjectName objectName = pmo.getObjectName();
                        if (objectName == null) {
                            failed = true;
                            throw new RuntimeException("TEST FAILED: getObjectName() returns null");
                        }
                        // Check for uniqueness
                        if (globalObjectNames.putIfAbsent(objectName, "") != null) {
                            failed = true;
                            throw new RuntimeException("TEST FAILED: Duplicate ObjectName found: " + objectName);
                        }
                    }
                }
            });
        }
    }
}

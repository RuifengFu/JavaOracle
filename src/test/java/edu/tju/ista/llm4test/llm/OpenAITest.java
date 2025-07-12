package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.concurrent.ConcurrentExecutionManager;
import edu.tju.ista.llm4test.utils.CodeExtractor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Thread.sleep;


public class OpenAITest {

    @Test
    public void testCall() {
        String res = OpenAI.R1.messageCompletion("write a snake game");
        System.out.println("Answer: " + res);
    }


    @Test
    public void testExtractCode() {
        String text = OpenAI.DoubaoFlash.messageCompletion("write a quicksort");
        ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
        for (String code: codeBlocks) {
            System.out.println("Code: \n" + code);
        }
    }

    @Test
    public void limitTest() throws InterruptedException {
        var r1 = OpenAI.R1;
        var manager = ConcurrentExecutionManager.getInstance();
        AtomicInteger cnt = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // 修复循环条件：简单地运行50次
        for (int i = 0; i < 50; i++) {
            Thread.sleep(1000);
            int finalI = i;
            CompletableFuture<Void> future = manager.submitLLMTask(
                    () -> {
                        System.out.println("Running " + finalI);
                        String res = r1.messageCompletion("write a snake game");

                        if (!res.isEmpty()) {
                            int successCount = cnt.incrementAndGet();
                            System.out.println("Success " + finalI + " (total: " + successCount + ")");
                        } else {
                            int failCount = failed.incrementAndGet();
                            System.out.println("Failed " + finalI + " (total failures: " + failCount + ")");
                        }
                    }
            );
            futures.add(future);
        }
        
        // 等待所有任务完成
        futures.forEach(CompletableFuture::join);
        
        System.out.println("测试完成 - 成功: " + cnt.get() + ", 失败: " + failed.get());
    }

    @Test
    public void threadtest() {

        var manager = ConcurrentExecutionManager.getInstance();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 500; i++) {

            int finalI = i;
            CompletableFuture<Void> future = manager.submitLLMTask(
                () -> {
                    for (int j = 1; j <= 10; j++) {
                        System.out.println(finalI);
                        System.out.flush();
//                        try {
//                            Thread.sleep(5);
//                        } catch (InterruptedException e) {
//                            throw new RuntimeException(e);
//                        }
                    }
                }
            );
            futures.add(future);

        }
        futures.forEach(CompletableFuture::join);
    }
}

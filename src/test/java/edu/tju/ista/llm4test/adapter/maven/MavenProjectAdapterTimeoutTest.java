package edu.tju.ista.llm4test.adapter.maven;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 进程超时行为测试：超时分支必须在约定时限内返回，而不是被 readAllBytes 阻塞。
 * <p>
 * 回归对象：先 {@code readAllBytes()} 再 {@code destroy()} 的写法下，读取会一直等到
 * EOF —— 子进程还活着（甚至因写满管道而卡住）时 EOF 不会到来，“超时”变成无限挂起。
 * 用例构造 mvn 的真实形状（包装进程 + 继承管道的子进程 + 超过 64KB 管道缓冲的输出），
 * 修复前需等到子进程自然结束（≈20s+），修复后应立即返回。
 */
class MavenProjectAdapterTimeoutTest {

    private static final long TIMEOUT_MS = 1_000;
    /** 留足余量但远小于子进程自然结束时间（20s），足以区分挂起与及时返回 */
    private static final long DEADLINE_MS = 10_000;

    private static Object runProcess(List<String> command) throws Exception {
        MavenProjectAdapter adapter = new MavenProjectAdapter(".", "unused.jar");
        Method m = MavenProjectAdapter.class.getDeclaredMethod("runProcess", List.class, long.class);
        m.setAccessible(true);
        return m.invoke(adapter, command, TIMEOUT_MS);
    }

    private static boolean timedOut(Object out) throws Exception {
        Method m = out.getClass().getDeclaredMethod("timedOut");
        m.setAccessible(true);
        return (boolean) m.invoke(out);
    }

    private static int exitValue(Object out) throws Exception {
        Method m = out.getClass().getDeclaredMethod("exitValue");
        m.setAccessible(true);
        return (int) m.invoke(out);
    }

    private static String stdout(Object out) throws Exception {
        Method m = out.getClass().getDeclaredMethod("stdout");
        m.setAccessible(true);
        return (String) m.invoke(out);
    }

    @Test
    void timeoutReturnsPromptlyWithLargeOutputAndLiveChild() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "需要 bash，跳过");

        // 子 shell 打印 ~68KB（超过 Linux 64KB 管道缓冲）后 sleep：
        // 输出由子进程写入，写端在管道满时阻塞，模拟真实的大输出长任务
        List<String> command = List.of("/bin/bash", "-c",
                "bash -c 'for i in $(seq 1 4000); do echo 0123456789abcdef; done; sleep 20'");

        long start = System.nanoTime();
        Object out = runProcess(command);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(timedOut(out), "应标记为超时");
        assertEquals(-1, exitValue(out), "超时的退出码约定为 -1");
        assertTrue(elapsedMs < DEADLINE_MS,
                "超时分支应及时返回，实际耗时 " + elapsedMs + "ms（>= " + DEADLINE_MS + "ms 说明读流被阻塞）");
    }

    @Test
    void normalProcessOutputIsCaptured() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "需要 bash，跳过");

        Object out = runProcess(List.of("/bin/bash", "-c", "echo hello-from-child"));

        assertFalse(timedOut(out), "正常结束不应标记超时");
        assertEquals(0, exitValue(out));
        assertTrue(stdout(out).contains("hello-from-child"), "实际输出: " + stdout(out));
    }
}

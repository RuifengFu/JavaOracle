package edu.tju.ista.llm4test.utils;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProcessRunner} 的行为测试，覆盖它存在的两个理由：
 * <ol>
 *   <li><b>大输出不得造成假超时</b>：子进程写满管道缓冲（Linux 默认 64KB）后阻塞，
 *       「先 waitFor 再 readAllBytes」的老写法会一路等到超时。</li>
 *   <li><b>超时必须及时返回</b>：包括包装脚本（子孙进程继承同一个管道写端）的场景，
 *       只杀直接子进程时 EOF 不会到来，「超时」形同虚设。</li>
 * </ol>
 * 用例全部依赖 bash 内建命令，保证直接子进程就是写出者。
 */
class ProcessRunnerTest {

    /** 超时用例统一用 1s，保证整个测试类只跑几秒 */
    private static final long SHORT_TIMEOUT_MS = 1_000;
    /** 大输出用例给足余量：只要没被管道阻塞，实际耗时是毫秒级 */
    private static final long GENEROUS_TIMEOUT_MS = 60_000;
    /**
     * 超时分支的返回时限：远大于 1s 超时（避免 CI 抖动误报），
     * 又远小于子进程自然结束时间（30s），足以区分「及时返回」与「被阻塞挂住」
     */
    private static final long DEADLINE_MS = 10_000;

    /** 单行 64 字符，配合换行符每行 65 字节 */
    private static final String LINE = "0123456789abcdef".repeat(4);
    private static final int LINE_COUNT = 8_000;
    /** 约 508KB，远超 64KB 管道缓冲 */
    private static final int EXPECTED_BYTES = LINE_COUNT * (LINE.length() + 1);

    @BeforeAll
    static void requireBash() {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "需要 bash，跳过");
    }

    @Test
    void normalCompletionCapturesStdout() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(
                List.of("/bin/bash", "-c", "echo hello-from-child"), SHORT_TIMEOUT_MS);

        assertFalse(result.timedOut(), "正常结束不应标记超时");
        assertEquals(0, result.exitValue(), "退出码应为 0，实际: " + result.exitValue());
        assertTrue(result.stdout().contains("hello-from-child"),
                "标准输出应包含子进程内容，实际: " + result.stdout());
    }

    @Test
    void nonZeroExitValueIsReported() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(
                List.of("/bin/bash", "-c", "exit 3"), SHORT_TIMEOUT_MS);

        assertFalse(result.timedOut(), "正常结束不应标记超时");
        assertEquals(3, result.exitValue(), "应原样返回子进程退出码，实际: " + result.exitValue());
    }

    @Test
    void stderrIsCapturedSeparately() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(
                List.of("/bin/bash", "-c", "echo to-stdout; echo to-stderr 1>&2"), SHORT_TIMEOUT_MS);

        assertFalse(result.timedOut(), "正常结束不应标记超时");
        assertEquals(0, result.exitValue(), "退出码应为 0，实际: " + result.exitValue());
        assertTrue(result.stdout().contains("to-stdout"),
                "stdout 应包含 to-stdout，实际: " + result.stdout());
        assertFalse(result.stdout().contains("to-stderr"),
                "stdout 不应混入 stderr 内容，实际: " + result.stdout());
        assertTrue(result.stderr().contains("to-stderr"),
                "stderr 应包含 to-stderr，实际: " + result.stderr());
        assertFalse(result.stderr().contains("to-stdout"),
                "stderr 不应混入 stdout 内容，实际: " + result.stderr());
    }

    /** 核心回归：超过管道缓冲的输出不得被误判为超时，且输出必须完整 */
    @Test
    void largeOutputDoesNotCauseFalseTimeout() throws Exception {
        // 纯 bash 内建循环，直接子进程就是写出者：管道写满时是它自己阻塞
        String script = "for ((i=0; i<" + LINE_COUNT + "; i++)); do echo " + LINE + "; done";

        long start = System.nanoTime();
        ProcessRunner.Result result = ProcessRunner.run(
                List.of("/bin/bash", "-c", script), GENEROUS_TIMEOUT_MS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(result.timedOut(),
                "大输出被判超时说明读流没有并发抽干（耗时 " + elapsedMs + "ms）");
        assertEquals(0, result.exitValue(), "退出码应为 0，实际: " + result.exitValue());
        assertEquals(EXPECTED_BYTES, result.stdout().length(),
                "输出应完整无截断，期望 " + EXPECTED_BYTES + " 字符，实际 " + result.stdout().length());
    }

    /** 核心回归：超时要立刻返回，并带回已经读到的部分输出 */
    @Test
    void timeoutReturnsPromptlyWithPartialOutput() throws Exception {
        // 先打印再长睡：超时时子进程仍存活，EOF 不会到来
        List<String> command = List.of("/bin/bash", "-c", "echo partial-marker; sleep 30");

        long start = System.nanoTime();
        ProcessRunner.Result result = ProcessRunner.run(command, SHORT_TIMEOUT_MS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.timedOut(), "应标记为超时");
        assertEquals(ProcessRunner.TIMEOUT_EXIT_VALUE, result.exitValue(),
                "超时退出码应为 TIMEOUT_EXIT_VALUE，实际: " + result.exitValue());
        assertTrue(elapsedMs < DEADLINE_MS,
                "超时分支应及时返回，实际耗时 " + elapsedMs + "ms（>= " + DEADLINE_MS + "ms 说明读流被阻塞）");
        assertTrue(result.stdout().contains("partial-marker"),
                "超时应带回 sleep 之前的部分输出，实际: " + result.stdout());
    }

    /** 核心回归：mvn 那种「包装脚本 + 继承管道的子孙进程」也必须被杀掉 */
    @Test
    void wrapperDescendantsAreKilledOnTimeout() throws Exception {
        // 外层 bash 是包装脚本，内层 bash 才是真正干活的进程，两者共享同一个管道写端
        List<String> command = List.of("/bin/bash", "-c",
                "bash -c 'echo grandchild-marker; sleep 30'; echo wrapper-done");

        long start = System.nanoTime();
        ProcessRunner.Result result = ProcessRunner.run(command, SHORT_TIMEOUT_MS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.timedOut(), "应标记为超时");
        assertEquals(ProcessRunner.TIMEOUT_EXIT_VALUE, result.exitValue(),
                "超时退出码应为 TIMEOUT_EXIT_VALUE，实际: " + result.exitValue());
        assertTrue(elapsedMs < DEADLINE_MS,
                "只杀直接子进程会等到子孙自然结束（约 30s），实际耗时 " + elapsedMs + "ms");
    }

    @Test
    void environmentVariablesReachChild() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(
                List.of("/bin/bash", "-c", "echo FOO=$FOO"),
                Map.of("FOO", "bar-42"),
                SHORT_TIMEOUT_MS);

        assertFalse(result.timedOut(), "正常结束不应标记超时");
        assertEquals(0, result.exitValue(), "退出码应为 0，实际: " + result.exitValue());
        assertTrue(result.stdout().contains("FOO=bar-42"),
                "子进程应看到传入的环境变量，实际: " + result.stdout());
    }
}

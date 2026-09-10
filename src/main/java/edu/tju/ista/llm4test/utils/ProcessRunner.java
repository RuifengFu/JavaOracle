package edu.tju.ista.llm4test.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 外部进程执行器：并发消费 stdout/stderr + 超时 + 中断处理 + 终止进程树。
 * <p>
 * 存在的理由是「先 waitFor 再读流」这个模式有两个坑，两个适配器原先各踩一遍：
 * <ol>
 *   <li><b>假超时</b>：子进程输出超过管道缓冲（Linux 默认 64KB）时写端阻塞，
 *       进程永远不退出，一路等到超时被杀。jtreg 输出小没暴露，
 *       {@code junit-console --details=tree} 对参数化测试很容易超限。</li>
 *   <li><b>超时后再阻塞</b>：{@code readAllBytes()} 要等到 EOF 才返回，
 *       对仍存活的子进程会无限阻塞，使「超时」形同虚设。</li>
 * </ol>
 * 这里从进程启动那一刻就用两个守护线程持续抽干两个流，因此管道永不写满；
 * 超时/中断时先终止进程树（{@code mvn} 这类包装脚本的子孙进程继承了同一个
 * 管道写端，只杀直接子进程 EOF 不会到来），再收集已经读到的部分输出。
 */
public final class ProcessRunner {

    /** 超时时的退出码占位；调用方按各自的 harness 语义翻译（如 jtreg 用 124） */
    public static final int TIMEOUT_EXIT_VALUE = -1;

    /** 终止进程树后等待流线程收尾的时间，避免个别卡死的读线程拖住调用方 */
    private static final long DRAIN_JOIN_TIMEOUT_MS = 2_000;

    /**
     * 单个流的收集上限。
     * <p>
     * 并发抽干之后子进程不会再被管道卡住，因此一个话痨用例
     * （{@code junit-console --details=tree} 跑参数化套件、或死循环打印）
     * 能把几百 MB 灌进内存，再乘上并行池就是 OOM。修复前反而是「写满 64KB
     * 就卡住被杀」隐式限了流量。超限后丢弃后续内容并留下明确标记。
     */
    private static final int MAX_STREAM_BYTES = 8 * 1024 * 1024;

    private ProcessRunner() {
    }

    /**
     * 进程执行结果。
     *
     * @param stdout    标准输出（超时时为已读到的部分）
     * @param stderr    错误输出（超时时为已读到的部分）
     * @param exitValue 退出码；超时为 {@link #TIMEOUT_EXIT_VALUE}
     * @param timedOut  是否因超时被终止
     */
    public record Result(String stdout, String stderr, int exitValue, boolean timedOut) {
    }

    /**
     * 执行命令并收集完整输出。
     *
     * @param command   命令行
     * @param env       追加到子进程的环境变量（null 表示不追加）
     * @param timeoutMs 超时上限
     * @throws IOException          进程启动失败
     * @throws InterruptedException 等待期间被中断（进程树已终止，中断标志已恢复）
     */
    public static Result run(List<String> command, Map<String, String> env, long timeoutMs)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (env != null && !env.isEmpty()) {
            builder.environment().putAll(env);
        }

        Process process = builder.start();

        // 关键：读流线程必须在 waitFor 之前启动，否则管道写满后子进程会挂住
        StreamDrainer stdout = StreamDrainer.start(process.getInputStream(), "stdout");
        StreamDrainer stderr = StreamDrainer.start(process.getErrorStream(), "stderr");

        boolean finished;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            terminateTree(process);
            stdout.joinQuietly();
            stderr.joinQuietly();
            Thread.currentThread().interrupt();
            throw e;
        }

        if (!finished) {
            terminateTree(process);
            stdout.joinQuietly();
            stderr.joinQuietly();
            return new Result(stdout.content(), stderr.content(), TIMEOUT_EXIT_VALUE, true);
        }

        // 进程已退出：写端关闭，读线程会自然读到 EOF 结束
        stdout.joinQuietly();
        stderr.joinQuietly();
        return new Result(stdout.content(), stderr.content(), process.exitValue(), false);
    }

    /** 便捷重载：不追加环境变量 */
    public static Result run(List<String> command, long timeoutMs)
            throws IOException, InterruptedException {
        return run(command, null, timeoutMs);
    }

    /**
     * 终止进程树：先子孙后自身。
     * {@code mvn}/{@code jtreg} 都是包装脚本，真正干活的 java 进程继承了同一个
     * 管道写端，只杀直接子进程的话 EOF 永远不会到来。
     */
    private static void terminateTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.FINE, "终止子孙进程失败: " + e.getMessage());
        }
        process.destroy();
        process.destroyForcibly();
    }

    /** 后台抽干一个流，直到 EOF 或写端被关闭 */
    private static final class StreamDrainer implements Runnable {

        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Thread thread;
        /** 是否因超过上限而截断（截断必须留痕，否则下游会拿残缺输出当完整输出比对） */
        private volatile boolean truncated;
        /** 读线程是否正常读到 EOF；join 超时而未结束同样要留痕 */
        private volatile boolean completed;

        private StreamDrainer(InputStream stream, String name) {
            this.stream = stream;
            this.thread = new Thread(this, "process-" + name);
            this.thread.setDaemon(true);
        }

        static StreamDrainer start(InputStream stream, String name) {
            StreamDrainer drainer = new StreamDrainer(stream, name);
            drainer.thread.start();
            return drainer;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    synchronized (buffer) {
                        int room = MAX_STREAM_BYTES - buffer.size();
                        if (room > 0) {
                            buffer.write(chunk, 0, Math.min(read, room));
                        }
                        if (buffer.size() >= MAX_STREAM_BYTES) {
                            truncated = true;
                        }
                    }
                    // 超限后继续读但不再存：保持管道畅通，子进程才能正常退出
                }
                completed = true;
            } catch (IOException e) {
                // 进程被强杀时读端报错属正常，已读到的部分仍然有效
                LoggerUtil.logExec(Level.FINE, "读取进程流结束: " + e.getMessage());
            }
        }

        void joinQuietly() {
            try {
                thread.join(DRAIN_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String content() {
            String text;
            boolean cut;
            synchronized (buffer) {
                // 编码沿用历史行为：平台默认字符集（JDK18+ 即 UTF-8）
                text = buffer.toString();
                cut = truncated;
            }
            if (cut) {
                return text + "\n[output truncated at " + MAX_STREAM_BYTES + " bytes]";
            }
            if (!completed) {
                // join 超时：仍有进程持着写端（被 reparent 的孙进程等），输出可能不全
                return text + "\n[output may be incomplete: stream reader did not finish]";
            }
            return text;
        }
    }
}

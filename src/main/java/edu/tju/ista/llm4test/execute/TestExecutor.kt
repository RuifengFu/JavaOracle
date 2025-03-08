package edu.tju.ista.llm4test.execute

import edu.tju.ista.llm4test.utils.LoggerUtil
import java.io.File
import java.io.InputStream
import java.util.logging.Level
import kotlinx.coroutines.*

class TestExecutor(private val jarPath: String, private val resultDir: File) {



    companion object {
        // 目前未使用，可按需求后续扩展，比如在启动 jtreg 时加入不同的 exec 参数
        private val execFlags: Array<String> = arrayOf("-Xint", "-Xcomp", "-Xmixed")
        private val env: MutableMap<String, String> = hashMapOf("LANG" to "en_US.UTF-8")
        private val Path = System.getenv("PATH").replace("/usr/lib/jvm/java-17-openjdk-amd64/bin:", "")
        private val JDKs = arrayListOf("/home/Java/HotSpot/jdk-17.0.14+7", "/home/Java/HotSpot/jdk-21.0.6+7",
                "/home/Java/OpenJ9/jdk-17.0.14+7", "/home/Java/OpenJ9/jdk-21.0.6+7")
    }


    fun executeTest(file: File): TestResult {
        return try {
            val output = runBlocking { runJtreg(file) }
            var result = TestResult(output)
            for (i in 1..3) { // retry 3 times if fail
                if (result.isFail()) {
                    result = TestResult(output)
                } else {
                    break
                }
            }

            result
        } catch (e: Exception) {
            LoggerUtil.logExec(Level.WARNING, "Execute: ${file.path}\n${e.message}")
            e.printStackTrace()
            TestResult(TestResultKind.UNKNOWN)
        }
    }

    fun differentialTesting(file: File): TestResult {
        val results : HashMap<String, TestOutput> = hashMapOf()
        for (jdk in JDKs) {
            var jdk_env = HashMap(env)
            jdk_env["PATH"] = "$jdk/bin:$Path"
            val result = runBlocking { runJtreg(file, jdk_env) }
            results[jdk] = result
        }
        val res = TestResult()
        res.mergeResults(results)
        return res
    }

    @Throws(Exception::class)
    private fun readStream(stream: InputStream): String {
        return stream.bufferedReader().use { it.readText() }
    }

    @Throws(Exception::class)
    private suspend fun runJtreg(file: File) : TestOutput {
        return runJtreg(file, env)
    }

    @Throws(Exception::class)
    private suspend fun runJtreg(file: File,  env: MutableMap<String, String>): TestOutput = withContext(Dispatchers.IO) {
        // 清理临时文件
        clearJTworkFiles(file)

        // 构造 jtreg 命令及其参数，创建报告目录
        val reportDir = mkJReportDir()
        val jtregCommand = listOf("jtreg", "-avm", "-ea", "-va", "-r:$reportDir", file.path)
        LoggerUtil.logExec(Level.INFO, "execCommand: ${jtregCommand.joinToString(" ")}")

        // 使用 ProcessBuilder 构造进程并设置环境变量
        val processBuilder = ProcessBuilder(jtregCommand)
        processBuilder.environment().putAll(env)
        val process = processBuilder.start()

        // 使用 coroutine 并发消费标准输出和错误流
        val stdoutDeferred = async { readStream(process.inputStream) }
        val stderrDeferred = async { readStream(process.errorStream) }

        val finished = withTimeoutOrNull(600_000) { process.waitFor() }
        if (finished == null) {
            if (process.isAlive) {
                process.destroyForcibly()
            }
            LoggerUtil.logExec(Level.INFO, "Run: ${file.path}\n${jtregCommand.joinToString(" ")}\ntimeout")
            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()
            return@withContext TestOutput(stdout, stderr, 124)
        }

        val exitValue = process.exitValue()
        val stdout = stdoutDeferred.await()
        val stderr = stderrDeferred.await()
        val output = TestOutput(stdout, stderr, exitValue)

        if (exitValue != 0) {
            LoggerUtil.logExec(
                    Level.SEVERE,
                    "TRY DEBUG THIS: -------------------------\n${file.path}\n${jtregCommand.joinToString(" ")}\n$output\n-------------------------------\n"
            )
        } else {
            LoggerUtil.logExec(Level.INFO, "Run: ${file.path}\n${jtregCommand.joinToString(" ")}\n$output")
        }
        output
    }

    fun clearJTworkFiles(file: File) {
        try {
            val baseName = file.name.removeSuffix(".java")
            val jtWork = File("JTwork")
            // 计算相对于 resultDir 的路径，需要确保 parent 不为 null
            val relativePath = resultDir.toPath().toAbsolutePath()
                    .relativize(file.toPath().toAbsolutePath()).parent ?: return

            val classCache = jtWork.toPath()
                    .resolve("classes")
                    .resolve(relativePath)
                    .resolve("$baseName.class")
                    .toFile()
            val jtrCache = jtWork.toPath()
                    .resolve(relativePath)
                    .resolve("$baseName.jtr")
                    .toFile()
            val dCache = jtWork.toPath()
                    .resolve(relativePath)
                    .resolve("$baseName.d")
                    .toFile()

            classCache.delete()
            jtrCache.delete()
            dCache.delete()
        } catch (e: Exception) {
            // 出现异常时忽略，可根据需要增加日志输出
        }
    }

    private fun mkJReportDir(): String {
        val threadId = Thread.currentThread().id
        val reportDir = File("tmp/tmp_${threadId}_${System.nanoTime()}")
        if (!reportDir.exists()) {
            reportDir.mkdirs()
        }
        return reportDir.path
    }

    public fun testJDKenv() {
        runBlocking {
            for (jdk in JDKs) {
                var jdk_env = HashMap(env)
                jdk_env["PATH"] = "$jdk/bin:$Path"
                val testCMD = listOf("which", "java")

                // 使用 ProcessBuilder 构造进程并设置环境变量
                val processBuilder = ProcessBuilder(testCMD)
                processBuilder.environment().putAll(jdk_env)
                val process = processBuilder.start()

                val stdoutDeferred = async { readStream(process.inputStream) }
                val stderrDeferred = async { readStream(process.errorStream) }

                val finished = withTimeoutOrNull(600_000) { process.waitFor() }
                if (finished == null) {
                    if (process.isAlive) {
                        process.destroyForcibly()
                    }
                    val stdout = stdoutDeferred.await()
                    val stderr = stderrDeferred.await()
                    System.out.println("TEST FAILED")
                }

                val exitValue = process.exitValue()
                val stdout = stdoutDeferred.await()
                val stderr = stderrDeferred.await()
                System.out.println("Execute Success")
                System.out.println(stdout)
                System.out.println(stderr)
                System.out.println("FINISH for " + jdk);
            }
        }

    }
}
package edu.tju.ista.llm4test.execute;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestOutputTest {
    @Test
    public void preserveBlankLinesInStdErrBlock() {
        String stdout = "--------------------------------------------------\n" +
                        "TEST: BugReport/20250903_043931/bug/StripIndent/StripIndent.java\n" +
                        "TEST JDK: /root/.sdkman/candidates/java/current\n" +
                        "\n" +
                        "ACTION: compile -- Passed. Compilation successful\n" +
                        "REASON: User specified action: run compile StripIndent.java \n" +
                        "TIME:   0.752 seconds\n" +
                        "messages:\n" +
                        "command: compile /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/StripIndent.java\n" +
                        "reason: User specified action: run compile StripIndent.java \n" +
                        "started: Wed Sep 03 06:15:46 CST 2025\n" +
                        "Mode: othervm\n" +
                        "Process id: 10211\n" +
                        "finished: Wed Sep 03 06:15:47 CST 2025\n" +
                        "elapsed time (seconds): 0.752\n" +
                        "configuration:\n" +
                        "javac compilation environment\n" +
                        "  source path: /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent\n" +
                        "  class path:  /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent\n" +
                        "               /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTwork/classes/BugReport/20250903_043931/bug/StripIndent\n" +
                        "\n" +
                        "rerun:\n" +
                        "cd /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTwork/scratch && \\\n" +
                        "HOME=/root \\\n" +
                        "JTREG_HOME=/home/jtreg/build/images/jtreg \\\n" +
                        "LANG=zh_CN.UTF-8 \\\n" +
                        "LC_ALL=zh_CN.UTF-8 \\\n" +
                        "PATH=/bin:/usr/bin:/usr/sbin \\\n" +
                        "    /root/.sdkman/candidates/java/current/bin/javac \\\n" +
                        "        -J-Dtest.vm.opts= \\\n" +
                        "        -J-Dtest.tool.vm.opts= \\\n" +
                        "        -J-Dtest.compiler.opts= \\\n" +
                        "        -J-Dtest.java.opts= \\\n" +
                        "        -J-Dtest.jdk=/root/.sdkman/candidates/java/current \\\n" +
                        "        -J-Dcompile.jdk=/root/.sdkman/candidates/java/current \\\n" +
                        "        -J-Dtest.timeout.factor=1.0 \\\n" +
                        "        -J-Dtest.root=/home/oracle-ds3.1 \\\n" +
                        "        -J-Dtest.name=BugReport/20250903_043931/bug/StripIndent/StripIndent.java \\\n" +
                        "        -J-Dtest.verbose=Verbose[p=FULL,f=FULL,e=FULL,t=false,m=false] \\\n" +
                        "        -J-Dtest.file=/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/StripIndent.java \\\n" +
                        "        -J-Dtest.main.class=StripIndent \\\n" +
                        "        -J-Dtest.src=/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent \\\n" +
                        "        -J-Dtest.src.path=/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent \\\n" +
                        "        -J-Dtest.classes=/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTwork/classes/BugReport/20250903_043931/bug/StripIndent \\\n" +
                        "        -J-Dtest.class.path=/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTwork/classes/BugReport/20250903_043931/bug/StripIndent \\\n" +
                        "        -d /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTwork/classes/BugReport/20250903_043931/bug/StripIndent \\\n" +
                        "        -sourcepath /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent \\\n" +
                        "        -classpath /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent:/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTwork/classes/BugReport/20250903_043931/bug/StripIndent /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/StripIndent.java\n" +
                        "STDOUT:\n" +
                        "STDERR:\n" +
                        "\n" +
                        "ACTION: build -- Passed. All files up to date\n" +
                        "REASON: Named class compiled on demand\n" +
                        "TIME:   0.001 seconds\n" +
                        "messages:\n" +
                        "command: build StripIndent\n" +
                        "reason: Named class compiled on demand\n" +
                        "started: Wed Sep 03 06:15:47 CST 2025\n" +
                        "finished: Wed Sep 03 06:15:47 CST 2025\n" +
                        "elapsed time (seconds): 0.001\n" +
                        "\n" +
                        "ACTION: main -- Failed. Execution failed: `main' threw exception: java.lang.RuntimeException: Strip indent test failed\n" +
                        "REASON: User specified action: run main StripIndent \n" +
                        "TIME:   0.111 seconds\n" +
                        "messages:\n" +
                        "command: main StripIndent\n" +
                        "reason: User specified action: run main StripIndent \n" +
                        "started: Wed Sep 03 06:15:47 CST 2025\n" +
                        "Mode: othervm\n" +
                        "Process id: 10243\n" +
                        "finished: Wed Sep 03 06:15:47 CST 2025\n" +
                        "elapsed time (seconds): 0.111\n" +
                        "configuration:\n" +
                        "STDOUT:\n" +
                        "STDERR:\n" +
                        "Input: '...\n" +
                        "...abc\n" +
                        "...\n" +
                        "'\n" +
                        "Expected: '\n" +
                        "abc\n" +
                        "\n" +
                        "'\n" +
                        "Actual: '\n" +
                        "...abc\n" +
                        "\n" +
                        "'\n" +
                        "java.lang.RuntimeException: Strip indent test failed\n" +
                        "        at StripIndent.verify(StripIndent.java:96)\n" +
                        "        at StripIndent.test3(StripIndent.java:53)\n" +
                        "        at StripIndent.main(StripIndent.java:13)\n" +
                        "        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)\n" +
                        "        at java.base/java.lang.reflect.Method.invoke(Method.java:580)\n" +
                        "        at com.sun.javatest.regtest.agent.MainWrapper$MainTask.run(MainWrapper.java:138)\n" +
                        "        at java.base/java.lang.Thread.run(Thread.java:1575)\n" +
                        "\n" +
                        "JavaTest Message: Test threw exception: java.lang.RuntimeException: Strip indent test failed\n" +
                        "JavaTest Message: shutting down test\n" +
                        "\n" +
                        "STATUS:Failed.`main' threw exception: java.lang.RuntimeException: Strip indent test failed\n" +
                        "rerun:\n" +
                        "cd /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTwork/scratch && \\\n" +
                        "HOME=/root \\\n" +
                        "JTREG_HOME=/home/jtreg/build/images/jtreg \\\n" +
                        "LANG=zh_CN.UTF-8 \\\n" +
                        "LC_ALL=zh_CN.UTF-8 \\\n" +
                        "PATH=/bin:/usr/bin:/usr/sbin \\\n" +
                        "CLASSPATH=/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTwork/classes/BugReport/20250903_043931/bug/StripIndent:/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent:/home/jtreg/build/images/jtreg/lib/javatest.jar:/home/jtreg/build/images/jtreg/lib/jtreg.jar \\\n" +
                        "    /root/.sdkman/candidates/java/current/bin/java \\\n" +
                        "        -Dtest.vm.opts= \\\n" +
                        "        -Dtest.tool.vm.opts= \\\n" +
                        "        -Dtest.compiler.opts= \\\n" +
                        "        -Dtest.java.opts= \\\n" +
                        "        -Dtest.jdk=/root/.sdkman/candidates/java/current \\\n" +
                        "        -Dcompile.jdk=/root/.sdkman/candidates/java/current \\\n" +
                        "        -Dtest.timeout.factor=1.0 \\\n" +
                        "        -Dtest.root=/home/oracle-ds3.1 \\\n" +
                        "        -Dtest.name=BugReport/20250903_043931/bug/StripIndent/StripIndent.java \\\n" +
                        "        -Dtest.verbose=Verbose[p=FULL,f=FULL,e=FULL,t=false,m=false] \\\n" +
                        "        -Dtest.file=/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/StripIndent.java \\\n" +
                        "        -Dtest.main.class=StripIndent \\\n" +
                        "        -Dtest.src=/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent \\\n" +
                        "        -Dtest.src.path=/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent \\\n" +
                        "        -Dtest.classes=/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTwork/classes/BugReport/20250903_043931/bug/StripIndent \\\n" +
                        "        -Dtest.class.path=/home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTwork/classes/BugReport/20250903_043931/bug/StripIndent \\\n" +
                        "        com.sun.javatest.regtest.agent.MainWrapper /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTwork/BugReport/20250903_043931/bug/StripIndent/StripIndent.d/main.0.jta\n" +
                        "\n" +
                        "TEST RESULT: Failed. Execution failed: `main' threw exception: java.lang.RuntimeException: Strip indent test failed\n" +
                        "--------------------------------------------------\n" +
                        "Test results: failed: 1\n" +
                        "Report written to /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTreport/html/report.html\n" +
                        "Results written to /home/oracle-ds3.1/BugReport/20250903_043931/bug/StripIndent/JTwork\n" +
                        "Error: Some tests failed or other problems occurred.";

        TestOutput out = new TestOutput(stdout, "", 2);
        String err = out.getTesterr();
        System.out.println(out);
        assertNotNull(err);
        // 核心断言：必须保留期望与实际中的连续空行
        assertTrue(err.contains("Expected: '\nabc\n\n'"),
                () -> "Expected block missing preserved blank lines. Actual err:\n" + err);
        assertTrue(err.contains("Actual: '\n...abc\n\n'"),
                () -> "Actual block missing preserved blank lines. Actual err:\n" + err);

        // 也应包含异常行
        assertTrue(err.contains("java.lang.RuntimeException: Strip indent test failed"));
    }
    public static void main(String[] args) {
        // 模拟你提供的JTreg输出
        String stdout = "--------------------------------------------------\n" +
            "TEST: java/security/spec/PKCS8EncodedKeySpec/Algorithm.java\n" +
            "TEST JDK: /home/Java/HotSpot/jdk-17.0.14+7\n" +
            "\n" +
            "ACTION: build -- Passed. Build successful\n" +
            "REASON: Named class compiled on demand\n" +
            "TIME:   4.123 seconds\n" +
            "messages:\n" +
            "command: build Algorithm\n" +
            "reason: Named class compiled on demand\n" +
            "started: Fri Jun 20 01:13:19 CST 2025\n" +
            "Test directory:\n" +
            "  compile: Algorithm\n" +
            "finished: Fri Jun 20 01:13:23 CST 2025\n" +
            "elapsed time (seconds): 4.123\n" +
            "\n" +
            "ACTION: compile -- Passed. Compilation successful\n" +
            "REASON: .class file out of date or does not exist\n" +
            "TIME:   4.11 seconds\n" +
            "\n" +
            "ACTION: main -- Failed. Execution failed: 'main' threw exception: java.lang.Exception: Algorithm consistency check failed. Original: RSA, Reconstructed: null\n" +
            "REASON: Assumed action based on file name: run main Algorithm \n" +
            "TIME:   3.422 seconds\n" +
            "\n" +
            "TEST RESULT: Failed. Execution failed: 'main' threw exception: java.lang.Exception: Algorithm consistency check failed. Original: RSA, Reconstructed: null\n" +
            "--------------------------------------------------\n" +
            "Test results: failed: 1\n" +
            "Report written to /home/oracle-6-20-awt/tmp/jtreg_jdk-17.0.14+7_Algorithm_108_28951751288813775/JTreport/html/report.html\n" +
            "Results written to /home/oracle-6-20-awt/tmp/jtreg_jdk-17.0.14+7_Algorithm_108_28951751288813775/JTwork\n";

        String stderr = "WARNING: A terminally deprecated method in java.lang.System has been called\n" +
            "WARNING: System::setSecurityManager has been called by com.sun.javatest.regtest.agent.RegressionSecurityManager (file:/home/jtreg/build/images/jtreg/lib/jtreg.jar)\n" +
            "WARNING: Please consider reporting this to the maintainers of com.sun.javatest.regtest.agent.RegressionSecurityManager\n" +
            "WARNING: System::setSecurityManager will be removed in a future release\n" +
            "STDERR:\n" +
            "java.lang.Exception: Algorithm consistency check failed. Original: RSA, Reconstructed: null\n" +
            "\tat Algorithm.main(Algorithm.java:87)\n" +
            "\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)\n" +
            "\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)\n" +
            "\tat java.base/jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:43)\n" +
            "\tat java.base/java.lang.reflect.Method.invoke(Method.java:569)\n" +
            "\tat com.sun.javatest.regtest.agent.MainActionHelper$AgentVMRunnable.run(MainActionHelper.java:335)\n" +
            "\tat java.base/java.lang.Thread.run(Thread.java:840)\n" +
            "\n" +
            "JavaTest Message: Test threw exception: java.lang.Exception\n" +
            "JavaTest Message: shutting down test\n" +
            "\n" +
            "Error: Some tests failed or other problems occurred.\n";

        // 创建TestOutput实例
        TestOutput testOutput = new TestOutput(stdout, stderr, 2);
        
        // 打印解析结果
        System.out.println("=== 原始toString输出 ===");
        System.out.println(testOutput);
        
        System.out.println("\n=== 解析后的测试输出 ===");
        System.out.println(testOutput.getTestout());
        
        System.out.println("\n=== 解析后的测试错误 ===");
        System.out.println(testOutput.getTesterr());
        
        System.out.println("\n=== 原始stdout长度 ===");
        System.out.println("原始stdout长度: " + stdout.length());
        System.out.println("解析后testout长度: " + testOutput.getTestout().length());
        System.out.println("解析后testerr长度: " + testOutput.getTesterr().length());
    }
} 
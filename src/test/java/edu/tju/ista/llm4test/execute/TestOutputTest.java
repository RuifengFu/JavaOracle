package edu.tju.ista.llm4test.execute;

import edu.tju.ista.llm4test.execute.TestOutput;

public class TestOutputTest {
    public static void main(String[] args) {
        // 使用你提供的完整JTreg输出
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
            "messages:\n" +
            "command: compile /home/oracle-6-20-awt/test/jdk/java/security/spec/PKCS8EncodedKeySpec/Algorithm.java\n" +
            "reason: .class file out of date or does not exist\n" +
            "started: Fri Jun 20 01:13:19 CST 2025\n" +
            "Mode: agentvm\n" +
            "Agent id: 1\n" +
            "Process id: 14619\n" +
            "finished: Fri Jun 20 01:13:23 CST 2025\n" +
            "elapsed time (seconds): 4.11\n" +
            "configuration:\n" +
            "Boot Layer (javac runtime environment)\n" +
            "  class path: /home/jtreg/build/images/jtreg/lib/javatest.jar\n" +
            "              /home/jtreg/build/images/jtreg/lib/jtreg.jar\n" +
            "  patch:      java.base /home/oracle-6-20-awt/tmp/jtreg_jdk-17.0.14+7_Algorithm_108_28951751288813775/JTwork/patches/java.base\n" +
            "\n" +
            "javac compilation environment\n" +
            "  source path: /home/oracle-6-20-awt/test/jdk/java/security/spec/PKCS8EncodedKeySpec\n" +
            "  class path:  /home/oracle-6-20-awt/test/jdk/java/security/spec/PKCS8EncodedKeySpec\n" +
            "               /home/oracle-6-20-awt/tmp/jtreg_jdk-17.0.14+7_Algorithm_108_28951751288813775/JTwork/classes/java/security/spec/PKCS8EncodedKeySpec/Algorithm.d\n" +
            "\n" +
            "ACTION: main -- Failed. Execution failed: `main' threw exception: java.lang.Exception: Algorithm consistency check failed. Original: RSA, Reconstructed: null\n" +
            "REASON: Assumed action based on file name: run main Algorithm\n" +
            "TIME:   3.422 seconds\n" +
            "messages:\n" +
            "command: main Algorithm\n" +
            "reason: Assumed action based on file name: run main Algorithm\n" +
            "started: Fri Jun 20 01:13:23 CST 2025\n" +
            "Mode: agentvm\n" +
            "Agent id: 2\n" +
            "Process id: 15991\n" +
            "finished: Fri Jun 20 01:13:26 CST 2025\n" +
            "elapsed time (seconds): 3.422\n" +
            "configuration:\n" +
            "Boot Layer\n" +
            "  class path: /home/jtreg/build/images/jtreg/lib/javatest.jar\n" +
            "              /home/jtreg/build/images/jtreg/lib/jtreg.jar\n" +
            "              /home/jtreg/build/images/jtreg/lib/junit-platform-console-standalone-1.11.0.jar\n" +
            "              /home/jtreg/build/images/jtreg/lib/testng-7.3.0.jar\n" +
            "              /home/jtreg/build/images/jtreg/lib/guice-5.1.0.jar\n" +
            "              /home/jtreg/build/images/jtreg/lib/jcommander-1.82.jar\n" +
            "  patch:      java.base /home/oracle-6-20-awt/tmp/jtreg_jdk-17.0.14+7_Algorithm_108_28951751288813775/JTwork/patches/java.base\n" +
            "\n" +
            "Test Layer\n" +
            "  class path: /home/oracle-6-20-awt/tmp/jtreg_jdk-17.0.14+7_Algorithm_108_28951751288813775/JTwork/classes/java/security/spec/PKCS8EncodedKeySpec/Algorithm.d\n" +
            "              /home/oracle-6-20-awt/test/jdk/java/security/spec/PKCS8EncodedKeySpec\n" +
            "\n" +
            "STDERR:\n" +
            "java.lang.Exception: Algorithm consistency check failed. Original: RSA, Reconstructed: null\n" +
            "\tat Algorithm.main(Algorithm.java:87)\n" +
            "\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)\n" +
            "\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)\n" +
            "\tat java.base/jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)\n" +
            "\tat java.base/java.lang.reflect.Method.invoke(Method.java:569)\n" +
            "\tat com.sun.javatest.regtest.agent.MainActionHelper$AgentVMRunnable.run(MainActionHelper.java:335)\n" +
            "\tat java.base/java.lang.Thread.run(Thread.java:840)\n" +
            "\n" +
            "JavaTest Message: Test threw exception: java.lang.Exception\n" +
            "JavaTest Message: shutting down test\n" +
            "\n" +
            "\n" +
            "TEST RESULT: Failed. Execution failed: `main' threw exception: java.lang.Exception: Algorithm consistency check failed. Original: RSA, Reconstructed: null\n" +
            "--------------------------------------------------\n" +
            "Test results: failed: 1\n" +
            "Report written to /home/oracle-6-20-awt/tmp/jtreg_jdk-17.0.14+7_Algorithm_108_28951751288813775/JTreport/html/report.html\n" +
            "Results written to /home/oracle-6-20-awt/tmp/jtreg_jdk-17.0.14+7_Algorithm_108_28951751288813775/JTwork\n";

        String stderr = "WARNING: A terminally deprecated method in java.lang.System has been called\n" +
            "WARNING: System::setSecurityManager has been called by com.sun.javatest.regtest.agent.RegressionSecurityManager (file:/home/jtreg/build/images/jtreg/lib/jtreg.jar)\n" +
            "WARNING: Please consider reporting this to the maintainers of com.sun.javatest.regtest.agent.RegressionSecurityManager\n" +
            "WARNING: System::setSecurityManager will be removed in a future release\n" +
            "Error: Some tests failed or other problems occurred.\n";

        String stdout2 = "--------------------------------------------------\n" +
                "TEST: BugReport/20250620_011252/Algorithm/Algorithm.java\n" +
                "TEST JDK: /root/.sdkman/candidates/java/current\n" +
                "\n" +
                "ACTION: build -- Passed. Build successful\n" +
                "REASON: Named class compiled on demand\n" +
                "TIME:   0.745 seconds\n" +
                "messages:\n" +
                "command: build Algorithm\n" +
                "reason: Named class compiled on demand\n" +
                "started: Sat Jun 21 16:30:53 CST 2025\n" +
                "Test directory:\n" +
                "  compile: Algorithm\n" +
                "finished: Sat Jun 21 16:30:54 CST 2025\n" +
                "elapsed time (seconds): 0.745\n" +
                "\n" +
                "ACTION: compile -- Passed. Compilation successful\n" +
                "REASON: .class file out of date or does not exist\n" +
                "TIME:   0.735 seconds\n" +
                "messages:\n" +
                "command: compile /home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/Algorithm.java\n" +
                "reason: .class file out of date or does not exist\n" +
                "started: Sat Jun 21 16:30:53 CST 2025\n" +
                "Mode: othervm\n" +
                "Process id: 7049\n" +
                "finished: Sat Jun 21 16:30:54 CST 2025\n" +
                "elapsed time (seconds): 0.735\n" +
                "configuration:\n" +
                "javac compilation environment\n" +
                "  source path: /home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm\n" +
                "  class path:  /home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm\n" +
                "               /home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/JTwork/classes/BugReport/20250620_011252/Algorithm\n" +
                "\n" +
                "rerun:\n" +
                "cd /home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/JTwork/scratch && \\\n" +
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
                "        -J-Dtest.root=/home/oracle-6-20-awt \\\n" +
                "        -J-Dtest.name=BugReport/20250620_011252/Algorithm/Algorithm.java \\\n" +
                "        -J-Dtest.verbose=Verbose[p=FULL,f=FULL,e=FULL,t=false,m=false] \\\n" +
                "        -J-Dtest.file=/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/Algorithm.java \\\n" +
                "        -J-Dtest.main.class=Algorithm \\\n" +
                "        -J-Dtest.src=/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm \\\n" +
                "        -J-Dtest.src.path=/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm \\\n" +
                "        -J-Dtest.classes=/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/JTwork/classes/BugReport/20250620_011252/Algorithm \\\n" +
                "        -J-Dtest.class.path=/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/JTwork/classes/BugReport/20250620_011252/Algorithm \\\n" +
                "        -d /home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/JTwork/classes/BugReport/20250620_011252/Algorithm \\\n" +
                "        -sourcepath /home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm \\\n" +
                "        -classpath /home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm:/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/JTwork/classes/BugReport/20250620_011252/Algorithm /home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/Algorithm.java\n" +
                "STDOUT:\n" +
                "STDERR:\n" +
                "\n" +
                "ACTION: main -- Failed. Execution failed: `main' threw exception: java.lang.Exception: Algorithm consistency check failed. Original: RSA, Reconstructed: null\n" +
                "REASON: Assumed action based on file name: run main Algorithm \n" +
                "TIME:   0.292 seconds\n" +
                "messages:\n" +
                "command: main Algorithm\n" +
                "reason: Assumed action based on file name: run main Algorithm \n" +
                "started: Sat Jun 21 16:30:54 CST 2025\n" +
                "Mode: othervm\n" +
                "Process id: 7079\n" +
                "finished: Sat Jun 21 16:30:54 CST 2025\n" +
                "elapsed time (seconds): 0.292\n" +
                "configuration:\n" +
                "STDOUT:\nthis is stdout\n" +
                "STDERR:\n" +
                "java.lang.Exception: Algorithm consistency check failed. Original: RSA, Reconstructed: null\n" +
                "        at Algorithm.main(Algorithm.java:87)\n" +
                "        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)\n" +
                "        at java.base/java.lang.reflect.Method.invoke(Method.java:578)\n" +
                "        at com.sun.javatest.regtest.agent.MainWrapper$MainTask.run(MainWrapper.java:138)\n" +
                "        at java.base/java.lang.Thread.run(Thread.java:1623)\n" +
                "\n" +
                "JavaTest Message: Test threw exception: java.lang.Exception: Algorithm consistency check failed. Original: RSA, Reconstructed: null\n" +
                "JavaTest Message: shutting down test\n" +
                "\n" +
                "STATUS:Failed.`main' threw exception: java.lang.Exception: Algorithm consistency check failed. Original: RSA, Reconstructed: null\n" +
                "rerun:\n" +
                "cd /home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/JTwork/scratch && \\\n" +
                "HOME=/root \\\n" +
                "JTREG_HOME=/home/jtreg/build/images/jtreg \\\n" +
                "LANG=zh_CN.UTF-8 \\\n" +
                "LC_ALL=zh_CN.UTF-8 \\\n" +
                "PATH=/bin:/usr/bin:/usr/sbin \\\n" +
                "CLASSPATH=/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/JTwork/classes/BugReport/20250620_011252/Algorithm:/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm:/home/jtreg/build/images/jtreg/lib/javatest.jar:/home/jtreg/build/images/jtreg/lib/jtreg.jar \\\n" +
                "    /root/.sdkman/candidates/java/current/bin/java \\\n" +
                "        -Dtest.vm.opts= \\\n" +
                "        -Dtest.tool.vm.opts= \\\n" +
                "        -Dtest.compiler.opts= \\\n" +
                "        -Dtest.java.opts= \\\n" +
                "        -Dtest.jdk=/root/.sdkman/candidates/java/current \\\n" +
                "        -Dcompile.jdk=/root/.sdkman/candidates/java/current \\\n" +
                "        -Dtest.timeout.factor=1.0 \\\n" +
                "        -Dtest.root=/home/oracle-6-20-awt \\\n" +
                "        -Dtest.name=BugReport/20250620_011252/Algorithm/Algorithm.java \\\n" +
                "        -Dtest.verbose=Verbose[p=FULL,f=FULL,e=FULL,t=false,m=false] \\\n" +
                "        -Dtest.file=/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/Algorithm.java \\\n" +
                "        -Dtest.main.class=Algorithm \\\n" +
                "        -Dtest.src=/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm \\\n" +
                "        -Dtest.src.path=/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm \\\n" +
                "        -Dtest.classes=/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/JTwork/classes/BugReport/20250620_011252/Algorithm \\\n" +
                "        -Dtest.class.path=/home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/JTwork/classes/BugReport/20250620_011252/Algorithm \\\n" +
                "        com.sun.javatest.regtest.agent.MainWrapper /home/oracle-6-20-awt/BugReport/20250620_011252/Algorithm/JTwork/BugReport/20250620_011252/Algorithm/Algorithm.d/main.0.jta\n" +
                "\n" +
                "TEST RESULT: Failed. Execution failed: `main' threw exception: java.lang.Exception: Algorithm consistency check failed. Original: RSA, Reconstructed: null\n" +
                "--------------------------------------------------\n" +
                "Test results: failed: 1\n" ;

        // 创建TestOutput实例
        TestOutput testOutput = new TestOutput(stdout2, "", 2);

        // 打印解析结果

        System.out.println("\n=== 分别查看解析结果 ===");
        System.out.println("testout:");
        System.out.println(testOutput.getTestout());
        System.out.println("\ntesterr:");
        System.out.println(testOutput.getTesterr());
    }
} 
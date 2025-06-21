package edu.tju.ista.llm4test.execute;

import edu.tju.ista.llm4test.execute.TestOutput;

public class TestOutputTest {
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
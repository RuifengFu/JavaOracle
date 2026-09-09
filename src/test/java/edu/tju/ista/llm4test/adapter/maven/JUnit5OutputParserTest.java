package edu.tju.ista.llm4test.adapter.maven;

import edu.tju.ista.llm4test.execute.TestOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit Platform Console 输出解析回归测试。
 * 样本取自 junit-platform-console-standalone 1.11.4 真实运行输出。
 */
class JUnit5OutputParserTest {

    /** 真实输出（1通过1失败，节选保留完整结构） */
    private static final String REAL_OUTPUT = """
            Thanks for using JUnit! Support its development at https://junit.org/sponsoring

            ╷
            ├─ JUnit Platform Suite ✔
            ├─ JUnit Jupiter ✔
            │  └─ CalcTest ✔
            │     ├─ passes() ✔
            │     └─ fails() ✘ boom-expected-failure ==> expected: <5> but was: <4>
            └─ JUnit Vintage ✔

            Failures (1):
              JUnit Jupiter:CalcTest:fails()
                MethodSource [className = 'com.example.CalcTest', methodName = 'fails', methodParameterTypes = '']
                => org.opentest4j.AssertionFailedError: boom-expected-failure ==> expected: <5> but was: <4>
                   org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
                   com.example.CalcTest.fails(CalcTest.java:10)

            Test run finished after 43 ms
            [         4 containers found      ]
            [         2 tests found           ]
            [         1 tests successful      ]
            [         1 tests failed          ]
            """;

    @Test
    void parsesRealMixedOutput() {
        TestOutput out = new TestOutput(REAL_OUTPUT, "", 1, new JUnit5OutputParser());
        // testout: 测试树 + 汇总
        assertTrue(out.getTestout().contains("└─ CalcTest ✔"), out.getTestout());
        assertTrue(out.getTestout().contains("fails() ✘"), "树中应显示失败标记");
        assertTrue(out.getTestout().contains("Test run finished after"));
        assertTrue(out.getTestout().contains("[         1 tests failed          ]"));
        // testerr: Failures区块（含异常与栈），不含树与汇总
        assertTrue(out.getTesterr().contains("Failures (1):"), out.getTesterr());
        assertTrue(out.getTesterr().contains("AssertionFailedError: boom-expected-failure"));
        assertTrue(out.getTesterr().contains("com.example.CalcTest.fails(CalcTest.java:10)"));
        assertFalse(out.getTesterr().contains("Test run finished"));
        assertFalse(out.getTesterr().contains("tests found"));
    }

    @Test
    void allPassOutputHasNoErrorSection() {
        String passOnly = """
                ├─ JUnit Jupiter ✔
                │  └─ CalcTest ✔
                Test run finished after 10 ms
                [         1 tests found           ]
                [         1 tests successful      ]
                """;
        TestOutput out = new TestOutput(passOnly, "", 0, new JUnit5OutputParser());
        assertEquals("", out.getTesterr());
        assertTrue(out.getTestout().contains("tests successful"));
    }

    @Test
    void emptyOutputSafe() {
        TestOutput out = new TestOutput(null, "some stderr", 1, new JUnit5OutputParser());
        assertEquals("", out.getTestout());
        assertEquals("some stderr", out.getTesterr());
    }
}
package edu.tju.ista.llm4test.adapter;

/**
 * 测试框架(harness)输出解析器：把原始进程输出解析为结构化字段。
 * 不同 harness（jtreg / JUnit console / TestNG）的输出格式不同，
 * 由各 ProjectAdapter 提供自己的实现。
 */
public interface HarnessOutputParser {

    /**
     * 解析结果：解析后的测试输出与测试错误信息
     */
    record ParsedOutput(String testout, String testerr) {
    }

    /**
     * 解析一次执行的原始输出
     * @param stdout 进程标准输出（可为null）
     * @param stderr 进程标准错误（可为null）
     * @return 解析结果（testout/testerr 不为null）
     */
    ParsedOutput parse(String stdout, String stderr);
}
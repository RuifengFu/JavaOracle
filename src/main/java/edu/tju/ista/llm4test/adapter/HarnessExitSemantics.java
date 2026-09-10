package edu.tju.ista.llm4test.adapter;

import edu.tju.ista.llm4test.execute.TestResultKind;

/**
 * harness 的退出码语义：分类 + 可读标签。
 * <p>
 * 单独成接口是为了让 {@code TestOutput} 能携带**产出它的那个 harness** 的码表，
 * 而不是每次去问当前配置的适配器——直接用 {@code MavenProjectAdapter}
 * （测试/pilot 就是这么用的）而配置是 jdk 时，后者会拿错码表。
 */
public interface HarnessExitSemantics {

    /** 退出码 → 结果分类（默认中性：0 成功，其余判失败） */
    default TestResultKind classifyExitValue(int exitValue) {
        return exitValue == 0 ? TestResultKind.SUCCESS : TestResultKind.TEST_FAIL;
    }

    /**
     * 退出码的可读标签，会进 prompt 给 LLM 看。
     * 因此不能沿用别的 harness 的码表，否则会误导模型对失败原因的判断。
     */
    default String describeExitValue(int exitValue) {
        return exitValue == 0 ? "SUCCESS" : "FAIL";
    }
}

package edu.tju.ista.llm4test.llm.tools;

import java.util.List;
import java.util.Map;

/**
 * 裁决分析工具
 * 
 * 目标：作为公正的裁决者，评估bug论证和测试用例问题论证的强度
 * 
 * 作用：
 * - 比较双方论证的证据质量和逻辑性
 * - 基于客观标准做出公正判断
 * - 避免偏见，确保判断的准确性
 * 
 * 裁决标准：
 * - 证据的强度和相关性
 * - 技术分析的准确性
 * - 论证的完整性和逻辑性
 * - 置信度的合理性
 */
public class VerdictTool implements Tool<Void> {
    @Override
    public String getName() {
        return "verdict_analysis";
    }

    @Override
    public String getDescription() {
        return "Analyze and determine which side (bug vs test case issue) is correct based on the provided arguments";
    }

    @Override
    public List<String> getParameters() {
        return List.of("bug_argument", "testcase_argument", "verdict", "confidence", "reasoning");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                "bug_argument", "The argument supporting that this is a JDK bug",
                "testcase_argument", "The argument supporting that this is a test case issue",
                "verdict", "The final verdict: 'BUG' or 'TESTCASE_ISSUE'",
                "confidence", "Confidence level in the verdict (0.0-1.0)",
                "reasoning", "Detailed reasoning for the verdict"
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
                "bug_argument", "string",
                "testcase_argument", "string",
                "verdict", "string",
                "confidence", "string",
                "reasoning", "string"
        );
    }

    @Override
    public ToolResponse<Void> execute(Map<String, Object> args) {
        // This tool is for output only, so it doesn't do anything.
        return ToolResponse.success(null);
    }
} 
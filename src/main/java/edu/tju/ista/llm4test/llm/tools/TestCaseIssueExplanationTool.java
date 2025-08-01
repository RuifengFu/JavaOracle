package edu.tju.ista.llm4test.llm.tools;

import java.util.List;
import java.util.Map;

/**
 * 测试用例问题解释工具
 * 
 * 目标：专门分析测试用例中可能存在的问题，提供反方论证
 * 
 * 作用：
 * - 寻找测试用例中的潜在问题
 * - 提供反方观点，避免误判
 * - 确保bug判断的准确性
 * 
 * 分析内容：
 * - API使用是否正确
 * - 测试逻辑是否有误
 * - 测试设置是否合理
 * - 假设是否成立
 * - 期望是否合理
 * - 环境配置是否正确
 */
public class TestCaseIssueExplanationTool implements Tool<Void> {
    @Override
    public String getName() {
        return "testcase_issue_explanation";
    }

    @Override
    public String getDescription() {
        return "Explain potential test case issues and provide arguments for why the failure might be due to test case problems rather than JDK bugs";
    }

    @Override
    public List<String> getParameters() {
        return List.of("issue_type", "issue_description", "suggested_fix", "evidence");
    }

    @Override
    public Map<String, String> getParametersDescription() {
        return Map.of(
                "issue_type", "The type of test case issue (e.g., 'API_MISUSE', 'LOGIC_ERROR', 'SETUP_ISSUE', 'ASSUMPTION_ERROR')",
                "issue_description", "Detailed description of the test case issue",
                "suggested_fix", "Suggested fix or improvement for the test case",
                "evidence", "Specific evidence from the test case, output, or API documentation that supports the issue claim"
        );
    }

    @Override
    public Map<String, String> getParametersType() {
        return Map.of(
                "issue_type", "string",
                "issue_description", "string",
                "suggested_fix", "string",
                "evidence", "string"
        );
    }

    @Override
    public ToolResponse<Void> execute(Map<String, Object> args) {
        // This tool is for output only, so it doesn't do anything.
        return ToolResponse.success(null);
    }
} 
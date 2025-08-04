package edu.tju.ista.llm4test.utils;

import edu.tju.ista.llm4test.llm.tools.ReRankerTool;
import edu.tju.ista.llm4test.llm.tools.ToolResponse;
import edu.tju.ista.llm4test.utils.websearch.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ReRankerToolTest {

    @Test
    void testExecuteWithAlibabaExample() {


        // Given
        List<SearchResult> alibabaResults = new ArrayList<>();
        String doc1 = "阿里巴巴集团发布《2024财年环境、社会和治理（ESG）报告》（下称“报告”），详细分享过去一年在 ESG各方面取得的进展。 报告显示，阿里巴巴扎实推进减碳举措，全集团自身运营净碳排放和价值链碳强度继续实现“双降”。 集团亦持续利用数字技术和平台能力，服务于无障碍、医疗、适老化和中小微企业等普惠发展。 阿里巴巴集团首席执行官吴泳铭在报告中表示：“ESG的核心是围绕如何成为一家更好的公司。 25年来，我们与ESG相关的行动所构成的公司底色，与创造商业价值的阿里巴巴一样重要。 在集团明确‘用户为先’和‘AI 驱动’的两大业务战略的同时，我们也明确ESG作为阿里巴巴基石战略之一的定位不变。 阿里巴巴在减少碳排放上取得扎实进展。";
        String doc2 = "ESG的核心是围绕如何成为一家更好的公司。 今年是阿里巴巴成立25年。 25年来，阿里巴巴秉持“让天下没有难做的生意”，协助国内电商繁荣发展；坚持开放生态，魔搭社区已开放了超3800个开源模型；助力乡村振兴，累计派出了29位乡村特派员深入27个县域；推动平台减碳，首创了范围3+减碳方案；坚持全员公益，用“人人3小时”带来小而美的改变……这些行动所构成的公司底色，与创造商业价值的阿里巴巴一样重要。 我希望这个过程中，每一个阿里人都能学会做难而正确的选择，保持前瞻、保持善意、保持务实。 一个更好的阿里巴巴，值得我们共同努力。 阿里巴巴二十多年来坚持不变的使命，是让天下没有难做的生意。 今天，这一使命被赋予了新的时代意义。";

        // Using the single-parameter constructor as requested
        alibabaResults.add(new SearchResult(doc1));
        alibabaResults.add(new SearchResult(doc2));

        ReRankerTool alibabaReranker = new ReRankerTool(alibabaResults);
        String query = "阿里巴巴2024年的ESG报告";
        Map<String, Object> args = Map.of("query", query);

        // When
        // NOTE: This test performs a live API call and requires a valid API key configured in GlobalConfig.
        ToolResponse<List<SearchResult>> response = alibabaReranker.execute(args);

        // Then
        assertTrue(response.isSuccess(), "The API call should be successful. Ensure API key is valid. Error: " + response.getFailMessage());
        assertNotNull(response.getResult());
        assertEquals(2, response.getResult().size(), "Should return the same number of documents.");

        // Verify that the scores have been updated
        for (SearchResult result : response.getResult()) {
            assertTrue(result.getRelevanceScore() >= 0, "Relevance score should be populated and non-negative.");
        }

        // Optional: Print results for manual verification
        System.out.println("Rerank test with Alibaba ESG data passed. Results:");
        response.getResult().forEach(result ->
            System.out.printf("- [Score: %.4f] Content: %s...%n", result.getRelevanceScore(), result.getContent().substring(0, 50))
        );
    }
}
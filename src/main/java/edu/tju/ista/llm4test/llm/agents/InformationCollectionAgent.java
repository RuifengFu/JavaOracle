package edu.tju.ista.llm4test.llm.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tju.ista.llm4test.llm.OpenAI;
import edu.tju.ista.llm4test.llm.tools.*;
import edu.tju.ista.llm4test.prompt.PromptGen;
import edu.tju.ista.llm4test.utils.LoggerUtil;
import edu.tju.ista.llm4test.utils.websearch.SearchConfig;
import edu.tju.ista.llm4test.utils.websearch.SearchResult;
import freemarker.template.TemplateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static edu.tju.ista.llm4test.utils.FileUtils.appendToFile;

/**
 * Simplified Information Collection Agent - Optimized information collection using observe loop
 */
public class InformationCollectionAgent extends Agent {
    
    // Decision tool - deterministic selection
    private static final Tool<Void> INFO_SUFFICIENT = new BasicTool("info_sufficient", "The currently collected information is sufficient to analyze the cause of the test failure");
    private static final Tool<Void> INFO_INSUFFICIENT = new BasicTool("info_insufficient", "The currently collected information is insufficient, more information is needed");
    
    private final SimplifiedSourceCodeSearchTool sourceTool;
    private final SimplifiedJavaDocSearchTool javadocTool;
    private final BochaSearch webSearchTool;
    private final OpenAI llm = OpenAI.AgentModel;
    private final ObjectMapper objectMapper;
    
    // Information collection configuration
    private static final int MAX_TOTAL_SIZE = 32000;
    private static final int MAX_ITERATIONS = 3;
    
    // Current collection status
    private final List<CollectedInfo> collectedInfos = new ArrayList<>();
    private final Set<String> collectedInfoSources = new HashSet<>();
    private int currentSize = 0;
    private int numIterations = 0;
    private String currentTestCaseIdentifier;
    private Path currentResultDir;
    private String currentTimestamp;
    
    public int getNumIterations() {
        return numIterations;
    }

    public InformationCollectionAgent(String sourcePath, String javadocPath) {
        super("You are an information collection expert responsible for gathering the most important information related to Bug analysis.");
        
        // Initialize tools
        this.sourceTool = sourcePath != null ? new SimplifiedSourceCodeSearchTool(sourcePath) : null;
        this.javadocTool = javadocPath != null ? new SimplifiedJavaDocSearchTool(javadocPath) : null;
        
        // Initialize Web search tool
        String apiKey = System.getenv("BOCHA_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            SearchConfig config = new SearchConfig().setApiKey(apiKey).setSummary(true).setEnableLogging(false);
            this.webSearchTool = new BochaSearch(config);
        } else {
            this.webSearchTool = new BochaSearch();
        }

        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Collect relevant information - main entry method
     */
    public List<CollectedInfo> collectInformation(String initialInsight, String testCode, 
                                                 String testOutput, String apiInfoWithSource,
                                                 String testCaseIdentifier, Path resultDir, String timestamp) {
        LoggerUtil.logExec(Level.INFO, "Starting information collection process");
        
        this.currentTestCaseIdentifier = testCaseIdentifier;
        this.currentResultDir = resultDir;
        this.currentTimestamp = timestamp;
        
        // Reset status
        collectedInfos.clear();
        collectedInfoSources.clear();
        currentSize = 0;
        
        // Parse initial insight
        AnalysisResult analysis = parseInitialInsight(initialInsight);
        
        // Add API information once at the beginning
        if (apiInfoWithSource != null && !apiInfoWithSource.isEmpty()) {
            addApiInfo(apiInfoWithSource, analysis);
        }
        
        // Observation loop - max 3 iterations
        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            this.numIterations = iteration;
            LoggerUtil.logExec(Level.INFO, "Information collection iteration " + iteration + "/" + MAX_ITERATIONS);
            
            // Collect information
            collectAllInformation(analysis, testCode, testOutput);
            
            // Observe and evaluate
            if (isInformationSufficient(analysis, testCode, testOutput, iteration)) {
                LoggerUtil.logExec(Level.INFO, "Information collection complete, requirements met after iteration " + iteration);
                break;
            }
            
            // If not the last iteration, re-analyze
            if (iteration < MAX_ITERATIONS) {
                LoggerUtil.logExec(Level.INFO, "Information insufficient, preparing for iteration " + (iteration + 1));
                analysis = refineAnalysis(analysis, testCode, testOutput);
            }
        }
        
        LoggerUtil.logExec(Level.INFO, String.format("Information collection complete, collected %d pieces of information, total size: %d characters", 
            collectedInfos.size(), currentSize));
        
        // Output the full content of the information source
        outputDetailedReport();
        
        return rankAndSummarize(analysis, testCode, testOutput);
    }
    
    /**
     * Output a complete information source report
     */
    private void outputDetailedReport() {
        LoggerUtil.logExec(Level.INFO, "================== Complete Information Source Report ==================");
        
        if (collectedInfos.isEmpty()) {
            LoggerUtil.logExec(Level.INFO, "No information collected");
            return;
        }
        
        // Group statistics by type
        Map<InfoType, List<CollectedInfo>> groupedByType = new HashMap<>();
        for (CollectedInfo info : collectedInfos) {
            groupedByType.computeIfAbsent(info.type, k -> new ArrayList<>()).add(info);
        }
        
        // Output statistical information
        LoggerUtil.logExec(Level.INFO, String.format("Total collected %d pieces of information, total size %d characters", 
            collectedInfos.size(), currentSize));
        
        for (InfoType type : InfoType.values()) {
            List<CollectedInfo> infos = groupedByType.getOrDefault(type, new ArrayList<>());
            if (!infos.isEmpty()) {
                int totalSize = infos.stream().mapToInt(info -> info.content.length()).sum();
                LoggerUtil.logExec(Level.INFO, String.format("- %s: %d items, %d characters in total", 
                    type, infos.size(), totalSize));
            }
        }
        
        LoggerUtil.logExec(Level.INFO, "==================== Detailed Content ====================");
        
        // Output the full content of each piece of information
        for (int i = 0; i < collectedInfos.size(); i++) {
            CollectedInfo info = collectedInfos.get(i);
            LoggerUtil.logExec(Level.INFO, String.format("\n--- Information Source %d ---", i + 1));
            LoggerUtil.logExec(Level.INFO, "ID: " + info.id);
            LoggerUtil.logExec(Level.INFO, "Type: " + info.type);
            LoggerUtil.logExec(Level.INFO, "Source: " + info.source);
            LoggerUtil.logExec(Level.INFO, "Relevance Score: " + String.format("%.3f", info.relevanceScore));
            LoggerUtil.logExec(Level.INFO, "Content Size: " + info.content.length() + " characters");
            LoggerUtil.logExec(Level.INFO, "Full Content:\n" + info.content);
            LoggerUtil.logExec(Level.INFO, "--- Information Source " + (i + 1) + " End ---\n");
        }
        
        LoggerUtil.logExec(Level.INFO, "================= Information Source Report End ================");
    }
    
    /**
     * Get the formatted detailed report string
     */
    public String getDetailedReport() {
        if (collectedInfos.isEmpty()) {
            return "# Information Collection Report\n\nNo information collected.\n";
        }
        
        StringBuilder report = new StringBuilder();
        report.append("# Detailed Information Collection Report\n\n");
        
        // Statistical information
        Map<InfoType, List<CollectedInfo>> groupedByType = new HashMap<>();
        for (CollectedInfo info : collectedInfos) {
            groupedByType.computeIfAbsent(info.type, k -> new ArrayList<>()).add(info);
        }
        
        report.append("## Statistics\n\n");
        report.append(String.format("- **Total**: %d pieces of information, %d characters\n", collectedInfos.size(), currentSize));
        
        for (InfoType type : InfoType.values()) {
            List<CollectedInfo> infos = groupedByType.getOrDefault(type, new ArrayList<>());
            if (!infos.isEmpty()) {
                int totalSize = infos.stream().mapToInt(info -> info.content.length()).sum();
                report.append(String.format("- **%s**: %d items, %d characters\n", type, infos.size(), totalSize));
            }
        }
        
        report.append("\n## Detailed Content\n\n");
        
        // Detailed content
        for (int i = 0; i < collectedInfos.size(); i++) {
            CollectedInfo info = collectedInfos.get(i);
            report.append(String.format("### Information Source %d\n\n", i + 1));
            report.append(String.format("- **ID**: %s\n", info.id));
            report.append(String.format("- **Type**: %s\n", info.type));
            report.append(String.format("- **Source**: %s\n", info.source));
            report.append(String.format("- **Relevance Score**: %.3f\n", info.relevanceScore));
            report.append(String.format("- **Content Size**: %d characters\n\n", info.content.length()));
            report.append("**Full Content**:\n\n");
            report.append("```\n");
            report.append(info.content);
            report.append("\n```\n\n");
            report.append("---\n\n");
        }
        
        return report.toString();
    }
    
    /**
     * Parse initial insight
     */
    private AnalysisResult parseInitialInsight(String initialInsight) {
        AnalysisResult result = new AnalysisResult();
        
        try {
            JsonNode rootNode = objectMapper.readTree(initialInsight);
            
            JsonNode symptomsNode = rootNode.path("symptoms");
            if (!symptomsNode.isMissingNode()) {
                result.symptoms = symptomsNode.asText();
            }
            
            JsonNode classesNode = rootNode.path("relevantClasses");
            if (classesNode.isArray()) {
                for (JsonNode classNode : classesNode) {
                    String className = classNode.asText();
                    if (className != null && !className.trim().isEmpty()) {
                        result.relevantClasses.add(className);
                    }
                }
            }
            
            JsonNode queriesNode = rootNode.path("queries");
            if (queriesNode.isArray()) {
                for (JsonNode queryNode : queriesNode) {
                    String query = queryNode.asText();
                    if (query != null && !query.trim().isEmpty()) {
                        result.queries.add(query);
                    }
                }
            }
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Failed to parse initial insight: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Collect all information
     */
    private void collectAllInformation(AnalysisResult analysis, String testCode, 
                                     String testOutput) {
        // 1. Collect source code information
        if (sourceTool != null) {
            collectWithTool(sourceTool, buildSourceCodePrompt(analysis, testCode), "SOURCE");
        }
        
        // 2. Collect JavaDoc information
        if (javadocTool != null) {
            collectWithTool(javadocTool, buildJavaDocPrompt(analysis, testCode), "JAVADOC");
        }
        
        // 3. Simple Web search (if there is space)
        collectWebInfo(analysis, testCode, testOutput);
        
    }
    
    /**
     * Collect information using a tool
     */
    private void collectWithTool(Tool<String> tool, String prompt, String prefix) {
        try {
            List<ToolCall> toolCalls = llm.toolCall(prompt, Arrays.asList(tool));
            
            if (toolCalls != null) {
                for (ToolCall toolCall : toolCalls) {
                    if (currentSize >= MAX_TOTAL_SIZE) break;

                    String searchType = (String) toolCall.arguments.get("search_type");
                    String query = generateTitle(searchType, toolCall.arguments);
                    String argumentsJson = "";
                    try {
                        argumentsJson = objectMapper.writeValueAsString(toolCall.arguments);
                    } catch (Exception e) {
                        LoggerUtil.logExec(Level.WARNING, "Failed to serialize tool call arguments to JSON: " + e.getMessage());
                    }
                    logSearchQuery(prefix, searchType, query, argumentsJson);
            
                    ToolResponse<String> response = tool.execute(toolCall.arguments);
                    if (response.isSuccess()) {
                        addCollectedInfo(response.getResult(), prefix, toolCall);
                    }
                }
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Tool call failed (" + prefix + "): " + e.getMessage());
        }
    }
    
    /**
     * Add collected information
     */
    private void addCollectedInfo(String content, String prefix, ToolCall toolCall) {
        if (content == null || content.isEmpty()) return;

        String searchType = (String) toolCall.arguments.get("search_type");
        String id = prefix + "_" + searchType + "_" + System.currentTimeMillis();
        String title = generateTitle(searchType, toolCall.arguments);
        
        if (collectedInfoSources.contains(title)) {
            LoggerUtil.logExec(Level.INFO, "Skipping duplicate information: " + title);
            return;
        }

        CollectedInfo info = new CollectedInfo(id, title, content,
            prefix.equals("SOURCE") ? InfoType.SOURCE_CODE : InfoType.JAVADOC, 0.8);

        collectedInfos.add(info);
        collectedInfoSources.add(title);
        currentSize += content.length();
    }
        
    /**
     * Add API information with extraction similar to web content
     */
    private void addApiInfo(String apiInfoWithSource, AnalysisResult analysis) {
        if (apiInfoWithSource == null || apiInfoWithSource.isEmpty()) return;
        
        try {
            String processedContent;
            
            if (apiInfoWithSource.length() > 4096) {
                LoggerUtil.logExec(Level.INFO, "API info content too long, extracting relevant parts");
                // Use the same extraction method as web content
                int maxSize = 8000;
                processedContent = extractRelevantText(analysis, "", "", apiInfoWithSource, maxSize);
                if (processedContent == null || processedContent.trim().isEmpty()) {
                    LoggerUtil.logExec(Level.WARNING, "API info extraction failed, using original with slicing");
                    if (currentSize < MAX_TOTAL_SIZE) {
                        int maxLength = 30000;
                        if (apiInfoWithSource.length() > maxLength) {
                            int middleKeep = 200;
                            int sideLength = (maxLength - middleKeep) / 2;
                            String head = apiInfoWithSource.substring(0, sideLength);
                            String tail = apiInfoWithSource.substring(apiInfoWithSource.length() - sideLength);
                            int midPoint = apiInfoWithSource.length() / 2;
                            String middle = apiInfoWithSource.substring(midPoint - (middleKeep / 2), midPoint + (middleKeep / 2));
                            processedContent = head + "\n\n... (content truncated, middle part follows) ...\n\n" + middle + "\n\n... (content truncated, showing tail) ...\n\n" + tail;
                        } else {
                            processedContent = apiInfoWithSource.substring(0, Math.min(apiInfoWithSource.length(), MAX_TOTAL_SIZE - currentSize));
                        }
                    } else {
                        processedContent = "";
                    }
                }
            } else {
                processedContent = apiInfoWithSource;
            }
            
            if (!processedContent.isEmpty()) {
                CollectedInfo info = new CollectedInfo("API_INFO", "API information and source code", processedContent, InfoType.SOURCE_CODE, 0.9);
                collectedInfos.add(info);
                collectedInfoSources.add(info.source);
                currentSize += processedContent.length();
            }
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "API info extraction failed: " + e.getMessage() + ", using original content");
            // Fallback to original content if extraction fails
            if (currentSize < MAX_TOTAL_SIZE) {
                String content = apiInfoWithSource.substring(0, Math.min(apiInfoWithSource.length(), MAX_TOTAL_SIZE - currentSize));
                CollectedInfo info = new CollectedInfo("API_INFO", "API information and source code", content, InfoType.SOURCE_CODE, 0.9);
                collectedInfos.add(info);
                collectedInfoSources.add(info.source);
                currentSize += content.length();
            }
        }
    }
    
    /**
     * Collect Web information (asynchronous version) - fetch full web page content in parallel
     */
    private void collectWebInfo(AnalysisResult analysis, String testCode, String testOutput) {
        // 1. Get queries, with fallback to symptoms
        List<String> queries = new ArrayList<>(analysis.queries);
        if (queries.isEmpty() && analysis.symptoms != null && !analysis.symptoms.isEmpty()) {
            LoggerUtil.logExec(Level.INFO, "No specific queries found, falling back to symptoms for web search.");
            queries.add(analysis.symptoms + " Java bug");
        }

        if (queries.isEmpty()) {
            LoggerUtil.logExec(Level.INFO, "No queries or symptoms available for web search.");
            return;
        }

        try {
            // 2. Execute searches sequentially for up to 3 queries
            List<String> queriesToRun = queries.stream().limit(3).collect(Collectors.toList());
            LoggerUtil.logExec(Level.INFO, "Executing web search for queries: " + queriesToRun);

            List<SearchResult> allResults = new ArrayList<>();
            for (String query : queriesToRun) {
                logSearchQuery("WEB", "query", query, "");
                // Stop fetching if we already have enough results to choose from
                if (allResults.size() >= 5) break;

                ToolResponse<List<SearchResult>> response = webSearchTool.executeForResults(Map.of(
                        "query", query,
                        "max_results", 3
                ));

                if (response.isSuccess() && response.getResult() != null) {
                    allResults.addAll(response.getResult());
                }
            }

            if (allResults.isEmpty()) {
                LoggerUtil.logExec(Level.INFO, "Web search yielded no results.");
                return;
            }

            // 3. Deduplicate results by URL and limit to a total of 3
            Map<String, SearchResult> distinctMap = new LinkedHashMap<>();
            for (SearchResult sr : allResults) {
                distinctMap.putIfAbsent(sr.getUrl(), sr);
            }
            List<SearchResult> finalResults = distinctMap.values().stream().limit(3).collect(Collectors.toList());

            // 4. Process final results in parallel (existing logic)
            if (!finalResults.isEmpty()) {
                ExecutorService executor = Executors.newFixedThreadPool(Math.min(finalResults.size(), 3));
                List<CompletableFuture<CollectedInfo>> futures = new ArrayList<>();

                try {
                    for (SearchResult result : finalResults) {
                        CompletableFuture<CollectedInfo> future = CompletableFuture.supplyAsync(() -> {
                            return extractSingleWebContent(result, analysis, testCode, testOutput);
                        }, executor);
                        futures.add(future);
                    }

                    CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

                    try {
                        allTasks.get(30, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        LoggerUtil.logExec(Level.WARNING, "Web page content extraction timed out, using completed results");
                    }

                    for (CompletableFuture<CollectedInfo> future : futures) {
                        if (currentSize >= MAX_TOTAL_SIZE) break;

                        try {
                            if (future.isDone() && !future.isCompletedExceptionally()) {
                                CollectedInfo info = future.get(1, TimeUnit.SECONDS);
                                if (info != null && currentSize + info.content.length() <= MAX_TOTAL_SIZE) {
                                    if (collectedInfoSources.contains(info.source)) {
                                        LoggerUtil.logExec(Level.INFO, "Skipping duplicate web content: " + info.source);
                                    } else {
                                        collectedInfos.add(info);
                                        collectedInfoSources.add(info.source);
                                        currentSize += info.content.length();
                                        LoggerUtil.logExec(Level.INFO, "Asynchronously added web page content: " + info.source +
                                                " (length: " + info.content.length() + ")");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            LoggerUtil.logExec(Level.WARNING, "Failed to get asynchronous result: " + e.getMessage());
                        }
                    }
                } finally {
                    executor.shutdown();
                    try {
                        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                            executor.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        executor.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Asynchronous web search failed: " + e.getMessage());
        }
    }
    
    /**
     * Extract single web page content (for asynchronous calls)
     */
    private CollectedInfo extractSingleWebContent(SearchResult result, AnalysisResult analysis, String testCode, String testOutput) {
        WebContentExtractor contentExtractor = null;
        try {
            LoggerUtil.logExec(Level.INFO, "Asynchronously extracting web page content: " + result.getUrl());
            
            contentExtractor = new WebContentExtractor(true);
            ToolResponse<String> contentResponse = contentExtractor.execute(result.getUrl());
            
            String fullContent = "";
            if (contentResponse.isSuccess() && contentResponse.getResult() != null) {
                fullContent = contentResponse.getResult();
            }

            // Apply new logic: omit if too short, summarize if too long
            if (fullContent.length() < 50) {
                LoggerUtil.logExec(Level.INFO, "Web page content too short, omitting: " + result.getUrl());
                return null; // Omit very short content
            }

            String processedContent;
            if (fullContent.length() > 4096) {
                LoggerUtil.logExec(Level.INFO, "Web page content too long, summarizing: " + result.getUrl());
                // Use DoubaoFlash to summarize/extract
                String prompt = PromptGen.generateSummarizeWebSearchResultPrompt(
                    analysis.symptoms, testCode, testOutput, fullContent, 4096 // Max size for summary
                );
                processedContent = OpenAI.FlashModel.messageCompletion(prompt, 0.5, false);
                if (processedContent == null || processedContent.trim().isEmpty()) {
                    LoggerUtil.logExec(Level.WARNING, "Summarization returned empty content for: " + result.getUrl());
                    return null; // If summarization fails or returns empty, omit.
                }
            } else {
                processedContent = fullContent; // Use as is
            }

            StringBuilder webContent = new StringBuilder();
            webContent.append("## ").append(result.getName()).append("\n\n");
            webContent.append("**Source**: ").append(result.getUrl()).append("\n\n");
            webContent.append(processedContent);
            
            String infoId = "WEB_" + result.getUrl().hashCode();
            return new CollectedInfo(infoId, "Web Page: " + result.getName(), 
                webContent.toString(), InfoType.WEB_SEARCH, 0.7);
                
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Exception in extracting single web page content: " + result.getUrl() + " - " + e.getMessage());
            
            // When an exception occurs, return basic summary information
            StringBuilder errorContent = new StringBuilder();
            errorContent.append("**Title**: ").append(result.getName()).append("\n");
            errorContent.append("**Source**: ").append(result.getUrl()).append("\n");
            errorContent.append("**Summary**: ").append(result.getSnippet()).append("\n");
            errorContent.append("**Note**: Full content extraction failed\n");
            
            String infoId = "WEB_ERROR_" + result.getUrl().hashCode();
            return new CollectedInfo(infoId, "Web Page Summary: " + result.getName(), 
                errorContent.toString(), InfoType.WEB_SEARCH, 0.3);
                
        } finally {
            // Ensure WebContentExtractor is properly closed
            if (contentExtractor != null) {
                try {
                    contentExtractor.close();
                } catch (Exception e) {
                    LoggerUtil.logExec(Level.WARNING, "Failed to close WebContentExtractor: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Determine if the information is sufficient - use deterministic tool selection
     */
    private boolean isInformationSufficient(AnalysisResult analysis, String testCode, String testOutput, int iteration) {
        // The first iteration is always not enough, giving a chance to optimize
        if (iteration == 1 && collectedInfos.size() < 3) {
            return false;
        }
        
        try {
            String observePrompt = buildObservePrompt(analysis, testCode, testOutput);
            List<Tool<?>> decisionTools = Arrays.asList(INFO_SUFFICIENT, INFO_INSUFFICIENT);
            List<ToolCall> toolCalls = llm.toolCall(observePrompt, decisionTools);
            
            if (toolCalls != null && !toolCalls.isEmpty()) {
                String decision = toolCalls.get(0).toolName;
                LoggerUtil.logExec(Level.INFO, "Information sufficiency judgment: " + decision);
                return INFO_SUFFICIENT.getName().equals(decision);
            } else {
                LoggerUtil.logExec(Level.WARNING, "LLM did not return a decision, defaulting to insufficient information");
                return false;
            }
                   
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Information sufficiency judgment failed: " + e.getMessage());
            // If the judgment fails, decide based on the number of iterations
            return iteration >= 2;
        }
    }
    
    /**
     * Re-analyze and optimize
     */
    private AnalysisResult refineAnalysis(AnalysisResult currentAnalysis, String testCode, String testOutput) {
        try {
            String refinePrompt = buildRefinePrompt(currentAnalysis, testCode, testOutput);
            String response = llm.messageCompletion(refinePrompt, 0.5, false);
            
            // Parse the optimized analysis results
            AnalysisResult refined = parseInitialInsight(response);
            
            // If parsing fails, keep the original analysis and add some supplementary queries
            if (refined.relevantClasses.isEmpty() && refined.queries.isEmpty()) {
                refined = currentAnalysis;
                refined.queries.add("exception handling");
                refined.queries.add("implementation details");
            }
            
            return refined;
            
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Re-analysis failed: " + e.getMessage());
            return currentAnalysis;
        }
    }
    
    /**
     * Build the source code collection prompt
     */
    private String buildSourceCodePrompt(AnalysisResult analysis, String testCode) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze the following test code to decide which source code to search for:\n\n");
        prompt.append("Symptom: ").append(analysis.symptoms).append("\n");
        prompt.append("Relevant classes: ").append(String.join(", ", analysis.relevantClasses)).append("\n\n");
        prompt.append("Test code:\n```java\n").append(testCode).append("\n```\n\n");
        prompt.append("Please select the most important source code search strategy, prioritizing the most relevant classes and methods.");
        return prompt.toString();
    }
    
    /**
     * Build the JavaDoc collection prompt
     */
    private String buildJavaDocPrompt(AnalysisResult analysis, String testCode) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze the following test code to decide which API documentation to search for:\n\n");
        prompt.append("Symptom: ").append(analysis.symptoms).append("\n");
        prompt.append("Relevant classes: ").append(String.join(", ", analysis.relevantClasses)).append("\n\n");
        prompt.append("Test code:\n```java\n").append(testCode).append("\n```\n\n");
        prompt.append("Please select the most important API documentation search strategy.");
        return prompt.toString();
    }
    
    /**
     * Build the observation prompt
     */
    private String buildObservePrompt(AnalysisResult analysis, String testCode, String testOutput) {
        try {
            // Build a summary of collected information
            StringBuilder collectedInfosStr = new StringBuilder();
            if (collectedInfos.isEmpty()) {
                collectedInfosStr.append("No information has been collected yet\n");
            } else {
                for (int i = 0; i < Math.min(5, collectedInfos.size()); i++) {
                    CollectedInfo info = collectedInfos.get(i);
                    collectedInfosStr.append("- ").append(info.source).append(" (").append(info.type).append(")\n");
            }
        }
        
            return PromptGen.generateBugVerifyObservePrompt(testCode, testOutput, 
                collectedInfosStr.toString(), analysis.symptoms);
        } catch (TemplateException | IOException e) {
            LoggerUtil.logExec(Level.WARNING, "Failed to generate observe prompt: " + e.getMessage());
            return "Based on the collected information, determine if it is sufficient to analyze the cause of the test failure. If it is sufficient, please reply with 'Sufficient', otherwise reply with 'Insufficient'.";
        }
    }
    
    /**
     * Build the re-analysis prompt
     */
    private String buildRefinePrompt(AnalysisResult analysis, String testCode, String testOutput) {
        try {
            // Build a summary of the currently collected information
            StringBuilder currentInfosStr = new StringBuilder();
            for (CollectedInfo info : collectedInfos) {
                currentInfosStr.append("- ").append(info.source).append("\n");
        }
        
            return PromptGen.generateBugVerifyRefineAnalysisPrompt(testCode, testOutput,
                analysis.symptoms, String.join(", ", analysis.relevantClasses),
                String.join(", ", analysis.queries), currentInfosStr.toString());
        } catch (TemplateException | IOException e) {
            LoggerUtil.logExec(Level.WARNING, "Failed to generate refine prompt: " + e.getMessage());
            return "Re-analyze the following test failure, providing a more detailed description of the symptoms and relevant classes:\n\n" + testCode;
        }
    }
    
    /**
     * Generate title
     */
    private String generateTitle(String searchType, Map<String, Object> args) {
        switch (searchType) {
            case "by_class":
                return "Class: " + args.get("class_name");
            case "by_method":
                return "Method: " + args.get("class_name") + "." + args.get("method_name");
            case "by_keyword":
                return "Keyword: " + args.get("keyword");
            case "by_package":
                return "Package: " + args.get("package_name");
            default:
                return "Search: " + searchType;
        }
    }
    
    /**
     * Ranks and summarizes the collected information to fit within the size limit.
     * This method skips reranking due to long text handling issues and uses original order.
     * Then, it iterates through the list, adding documents to the final list until the size limit is approached.
     * For individual documents that are too large, it splits them into chunks and uses an LLM to extract relevant parts from each chunk.
     * @param analysis The analysis result containing bug symptoms.
     * @param testCode The source code of the failing test.
     * @param testOutput The output of the failing test.
     * @return A list of collected information, ranked and summarized.
     */
    private List<CollectedInfo> rankAndSummarize(AnalysisResult analysis, String testCode, String testOutput) {
        LoggerUtil.logExec(Level.INFO, "Starting ranking and summarization process (without reranking)");

        // Skip reranking due to long text handling issues - use original order
        LoggerUtil.logExec(Level.INFO, "Skipping reranking due to long text handling issues, using original order");
        List<CollectedInfo> rankedInfos = new ArrayList<>(collectedInfos);

        // Extract relevant parts and build the final context.
        List<CollectedInfo> finalInfos = new ArrayList<>();
        int finalSize = 0;
        final int EXTRACTION_MODEL_CONTEXT_LIMIT = 40000; // Context limit for DoubaoFlash

        for (CollectedInfo info : rankedInfos) {
            // If the entire document fits within the remaining space, add it directly.
            if (finalSize + info.content.length() <= MAX_TOTAL_SIZE) {
                finalInfos.add(info);
                finalSize += info.content.length();
                continue;
            }

            // If there's no more space, stop processing.
            if (finalSize >= MAX_TOTAL_SIZE) {
                break;
            }

            // If the document is too large, extract relevant parts.
            int remainingSpace = MAX_TOTAL_SIZE - finalSize;
            StringBuilder extractedContent = new StringBuilder();

            try {
                // If the document itself is larger than the extraction model's context, process it in chunks.
                if (info.content.length() > EXTRACTION_MODEL_CONTEXT_LIMIT) {
                    LoggerUtil.logExec(Level.INFO, "Content for '" + info.source + "' is too large, processing in chunks.");
                    for (int i = 0; i < info.content.length(); i += EXTRACTION_MODEL_CONTEXT_LIMIT) {
                        int end = Math.min(i + EXTRACTION_MODEL_CONTEXT_LIMIT, info.content.length());
                        String chunk = info.content.substring(i, end);
                        String extractedPart = extractRelevantText(analysis, testCode, testOutput, chunk, remainingSpace - extractedContent.length());
                        if (extractedPart != null && !extractedPart.isEmpty()) {
                            extractedContent.append(extractedPart).append("\n");
                        }
                        if (extractedContent.length() >= remainingSpace) {
                            break; // Stop if we have filled the remaining space.
                        }
                    }
                } else {
                    // Process the whole document at once.
                    String extractedPart = extractRelevantText(analysis, testCode, testOutput, info.content, remainingSpace);
                    if (extractedPart != null && !extractedPart.isEmpty()) {
                        extractedContent.append(extractedPart);
                    }
                }

                if (extractedContent.length() > 0) {
                    String finalExtractedText = extractedContent.toString();
                    // Truncate if the extracted content slightly exceeds the remaining space.
                    if (finalExtractedText.length() > remainingSpace) {
                        finalExtractedText = finalExtractedText.substring(0, remainingSpace);
                    }
                    CollectedInfo summarizedInfo = new CollectedInfo(
                            info.id + "_extracted",
                            info.source,
                            finalExtractedText,
                            info.type,
                            info.relevanceScore
                    );
                    finalInfos.add(summarizedInfo);
                    finalSize += finalExtractedText.length();
                    LoggerUtil.logExec(Level.INFO, "Extracted relevant parts for: " + info.source);
                }

            } catch (Exception e) {
                LoggerUtil.logExec(Level.WARNING, "Failed to extract relevant parts for: " + info.source + " - " + e.getMessage());
            }
        }

        // Replace the original collectedInfos with the new, processed list.
        collectedInfos.clear();
        collectedInfos.addAll(finalInfos);
        currentSize = finalSize;

        LoggerUtil.logExec(Level.INFO, "Final processed information count: " + collectedInfos.size() + ", total size: " + currentSize);
        outputDetailedReport();

        return new ArrayList<>(collectedInfos);
    }
    
    private synchronized void logSearchQuery(String searchTool, String searchType, String query, String arguments) {
        if (currentResultDir == null) return;
        try {
            Path statusCsv = currentResultDir.resolve("search_queries.csv");
            if (!Files.exists(statusCsv)) {
                String header = "TestCase,Timestamp,SearchTool,SearchType,Query,Arguments\n";
                appendToFile(statusCsv.toString(), header);
            }
            String line = String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                currentTestCaseIdentifier != null ? currentTestCaseIdentifier.replace("\"", "'") : "UNKNOWN",
                currentTimestamp,
                searchTool.replace("\"", "'"),
                searchType.replace("\"", "'"),
                query.replace("\"", "'"),
                arguments.replace("\"", "'")
            );
            appendToFile(statusCsv.toString(), line);
        } catch (Exception e) {
            LoggerUtil.logExec(Level.WARNING, "Failed to write to search_queries.csv: " + e.getMessage());
        }
    }

    private String extractRelevantText(AnalysisResult analysis, String testCode, String testOutput, String text, int maxSize) throws TemplateException, IOException {
        OpenAI summarizerLlm = OpenAI.FlashModel;
        String prompt = PromptGen.generateSummarizeAndExtractPrompt(
                analysis.symptoms, testCode, testOutput, text, maxSize
        );
        return summarizerLlm.messageCompletion(prompt, 0.5, false);
    }

    // Data structure
    private static class AnalysisResult {
        String symptoms = "";
        List<String> relevantClasses = new ArrayList<>();
        List<String> queries = new ArrayList<>();
    }
    
    public static class CollectedInfo {
        public final String id;
        public final String source;
        public final String content;
        public final InfoType type;
        public final double relevanceScore;
        
        public CollectedInfo(String id, String source, String content, InfoType type, double relevanceScore) {
            this.id = id;
            this.source = source;
            this.content = content;
            this.type = type;
            this.relevanceScore = relevanceScore;
        }
        
        @Override
        public String toString() {
            return String.format("[%s] %s (Relevance: %.2f)", type, source, relevanceScore);
        }
    }
    
    public enum InfoType {
        SOURCE_CODE("Source Code"),
        JAVADOC("API Documentation"),
        WEB_SEARCH("Web Search");
        
        private final String displayName;
        
        InfoType(String displayName) {
            this.displayName = displayName;
        }
        
        @Override
        public String toString() {
            return displayName;
        }
    }
} 
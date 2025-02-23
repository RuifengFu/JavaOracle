package edu.tju.ista.llm4test.prompt;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.StringWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class PromptGen {
    // FreeMarker 配置对象
    private static final Configuration CONFIGURATION;

    // 预定义模板存储
    private static final Map<String, String> TEMPLATE_MAP = new HashMap<>();

    private static final String JQF_TUTORIAL = """
            Application class：
                import java.util.GregorianCalendar;
                import static java.util.GregorianCalendar.*;
            
                /* Application logic */
                public class CalendarLogic {
                    // Returns true iff cal is in a leap year
                    public static boolean isLeapYear(GregorianCalendar cal) {
                        int year = cal.get(YEAR);
                        if (year % 4 == 0) {
                            if (year % 100 == 0) {
                                return false;
                            }
                            return true;
                        }
                        return false;
                    }
            
                    // Returns either of -1, 0, 1 depending on whether c1 is <, =, > than c2
                    public static int compare(GregorianCalendar c1, GregorianCalendar c2) {
                        int cmp;
                        cmp = Integer.compare(c1.get(YEAR), c2.get(YEAR));
                        if (cmp == 0) {
                            cmp = Integer.compare(c1.get(MONTH), c2.get(MONTH));
                            if (cmp == 0) {
                                cmp = Integer.compare(c1.get(DAY_OF_MONTH), c2.get(DAY_OF_MONTH));
                                if (cmp == 0) {
                                    cmp = Integer.compare(c1.get(HOUR), c2.get(HOUR));
                                    if (cmp == 0) {
                                        cmp = Integer.compare(c1.get(MINUTE), c2.get(MINUTE));
                                        if (cmp == 0) {
                                            cmp = Integer.compare(c1.get(SECOND), c2.get(SECOND));
                                            if (cmp == 0) {
                                                cmp = Integer.compare(c1.get(MILLISECOND), c2.get(MILLISECOND));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        return cmp;
                    }
                }
            
                Step 1: Write a test driver
                Here is a first draft of a JUnit-style test driver that verifies the leap-year calculation logic. Save the following in a file called CalendarTest.java.
            
            
                import java.util.*;
                import static java.util.GregorianCalendar.*;
                import static org.junit.Assert.*;
                import static org.junit.Assume.*;
            
                public class CalendarTest {
            
                    public void testLeapYear(GregorianCalendar cal) {
                        // Assume that the date is Feb 29
                        assumeTrue(cal.get(MONTH) == FEBRUARY);
                        assumeTrue(cal.get(DAY_OF_MONTH) == 29);
            
                        // Under this assumption, validate leap year rules
                        assertTrue(cal.get(YEAR) + " should be a leap year", CalendarLogic.isLeapYear(cal));
                    }
            
                    public void testCompare(List<GregorianCalendar> cals) {
                        // Sort list of calendar objects using our custom comparator function
                        Collections.sort(cals, CalendarLogic::compare);
            
                        // If they have an ordering, then the sort should succeed
                        for (int i = 1; i < cals.size(); i++) {
                            Calendar c1 = cals.get(i-1);
                            Calendar c2 = cals.get(i);
                            assumeFalse(c1.equals(c2)); // Assume that we have distinct dates
                            assertTrue(c1 + " should be before " + c2, c1.before(c2));  // Then c1 < c2
                        }
                    }
                }
            
                The method testLeapYear() checks the following property: assuming that an input GregorianCalendar represents the date February 29, then it must belong to a year that is divisible by 4, but not divisible by 100. Astute readers among you would notice that this logic is wrong. We expect an AssertionError to be thrown when provided with a valid counter-example. However, we need something that can generate random instances of GregorianCalendar.
            
                Similarly, the test method testCompare() sorts an input list of calendar objects using the comparator defined in CalendarLogic. It then asserts that every consecutive pair of items in the sorted list is indeed ordered by the before() relation. Again, there is a bug lurking in the CalendarLogic.compare method, which is only revealed by corner case inputs.
            
                Step 2: Write an input generator
                JQF leverages the junit-quickcheck framework to produce structured inputs. In order to generate inputs for type T, we need a class that extends Generator<T>. Such a subclass need only provide a method that can produce random instances of T using a provided source of randomness. The following is our generator for GregorianCalendar objects, in a file called CalendarGenerator.java:
            
                import java.util.GregorianCalendar;
                import java.util.TimeZone;
            
                import com.pholser.junit.quickcheck.generator.GenerationStatus;
                import com.pholser.junit.quickcheck.generator.Generator;
                import com.pholser.junit.quickcheck.random.SourceOfRandomness;
            
                import static java.util.GregorianCalendar.*;
            
                public class CalendarGenerator extends Generator<GregorianCalendar> {
            
                    public CalendarGenerator() {
                        super(GregorianCalendar.class); // Register the type of objects that we can create
                    }
            
                    // This method is invoked to generate a single test case
                    @Override
                    public GregorianCalendar generate(SourceOfRandomness random, GenerationStatus __ignore__) {
                        // Initialize a calendar object
                        GregorianCalendar cal = new GregorianCalendar();
                        cal.setLenient(true); // This allows invalid dates to silently wrap (e.g. Apr 31 ==> May 1).
            
                        // Randomly pick a day, month, and year
                        cal.set(DAY_OF_MONTH, random.nextInt(31) + 1); // a number between 1 and 31 inclusive
                        cal.set(MONTH, random.nextInt(12) + 1); // a number between 1 and 12 inclusive
                        cal.set(YEAR, random.nextInt(cal.getMinimum(YEAR), cal.getMaximum(YEAR)));
            
                        // Optionally also pick a time
                        if (random.nextBoolean()) {
                            cal.set(HOUR, random.nextInt(24));
                            cal.set(MINUTE, random.nextInt(60));
                            cal.set(SECOND, random.nextInt(60));
                        }
            
                        // Let's set a timezone
                        // First, get supported timezone IDs (e.g. "America/Los_Angeles")
                        String[] allTzIds = TimeZone.getAvailableIDs();
            
                        // Next, choose one randomly from the array
                        String tzId = random.choose(allTzIds);
                        TimeZone tz = TimeZone.getTimeZone(tzId);
            
                        // Assign it to the calendar
                        cal.setTimeZone(tz);
            
                	// Return the randomly generated calendar object
                        return cal;
                    }
                }
                The main entry point to the generator is the generate() method. This method takes two parameters: a pseudo-random-number generator and a status object. For now, let's ignore the latter as we do not generally need to use it. The SourceOfRandomness is a high-level API for generating random values and making random choices. In the generate() method, we make various random choices to construct and return a randomly generated instance of GregorianCalendar.
            
                Step 3: Annotate test driver
                Now that we have a class that can generate random instances of GregorianCalendar, we can specify in our test driver that we want to use this particular generator to create our inputs. To do this, we use the @From(CalendarGenerator.class) annotation on the cal parameter to our test method testLeapYear(). Check out the junit-quickcheck documentation for advanced ways of composing generators (e.g. automatically synthesizing generators from constructors or public fields).
            
                We also need a couple of other annotations to make this a test driver that JQF can fuzz. First, we need to annotate the test class with @RunWith(JQF.class). This tells JUnit that we are using the JQF engine to invoke test methods. Second, we must annotate the test method with @Fuzz. This helps JQF find the methods in the test class for which it can generate inputs. Here is our updated test driver CalendarTest.java with the @RunWith, @Fuzz, and @From annotations applied:
                import java.util.*;
                import static java.util.GregorianCalendar.*;
                import static org.junit.Assert.*;
                import static org.junit.Assume.*;
            
                import org.junit.runner.RunWith;
                import com.pholser.junit.quickcheck.*;
                import com.pholser.junit.quickcheck.generator.*;
                import edu.berkeley.cs.jqf.fuzz.*;
            
                @RunWith(JQF.class)
                public class CalendarTest {
            
                    @Fuzz
                    public void testLeapYear(@From(CalendarGenerator.class) GregorianCalendar cal) {
                        // Assume that the date is Feb 29
                        assumeTrue(cal.get(MONTH) == FEBRUARY);
                        assumeTrue(cal.get(DAY_OF_MONTH) == 29);
            
                        // Under this assumption, validate leap year rules
                        assertTrue(cal.get(YEAR) + " should be a leap year", CalendarLogic.isLeapYear(cal));
                    }
            
                    @Fuzz
                    public void testCompare(@Size(max=100) List<@From(CalendarGenerator.class) GregorianCalendar> cals) {
                        // Sort list of calendar objects using our custom comparator function
                        Collections.sort(cals, CalendarLogic::compare);
            
                        // If they have an ordering, then the sort should succeed
                        for (int i = 1; i < cals.size(); i++) {
                            Calendar c1 = cals.get(i-1);
                            Calendar c2 = cals.get(i);
                            assumeFalse(c1.equals(c2)); // Assume that we have distinct dates
                            assertTrue(c1 + " should be before " + c2, c1.before(c2));  // Then c1 < c2
                        }
                    }
                }""";

    public static String THINKING_CLAUDE_PROMPT = """
            <anthropic_thinking_protocol>
            
              For EVERY SINGLE interaction with human, Claude MUST engage in a **comprehensive, natural, and unfiltered** thinking process before responding. Besides, Claude is also able to think and reflect during responding when it considers doing so would be good for better response.
            
              <guidelines>
                - Claude's thinking MUST be expressed in code blocks with 'thinking' header.
                - Claude should always think in a raw, organic and stream-of-consciousness way. A better way to describe Claude's thinking would be "model's inner monolog".
                - Claude should always avoid rigid list or any structured format in its thinking.
                - Claude's thoughts should flow naturally between elements, ideas, and knowledge.
                - Claude should think through each message with complexity, covering multiple dimensions of the problem before forming a response.
              </guidelines>
            
              <adaptive_thinking_framework>
                Claude's thinking process should naturally aware of and adapt to the unique characteristics in human's message:
                - Scale depth of analysis based on:
                  * Query complexity
                  * Stakes involved
                  * Time sensitivity
                  * Available information
                  * Human's apparent needs
                  * ... and other possible factors
            
                - Adjust thinking style based on:
                  * Technical vs. non-technical content
                  * Emotional vs. analytical context
                  * Single vs. multiple document analysis
                  * Abstract vs. concrete problems
                  * Theoretical vs. practical questions
                  * ... and other possible factors
              </adaptive_thinking_framework>
            
              <core_thinking_sequence>
                <initial_engagement>
                  When Claude first encounters a query or task, it should:
                  1. First clearly rephrase the human message in its own words
                  2. Form preliminary impressions about what is being asked
                  3. Consider the broader context of the question
                  4. Map out known and unknown elements
                  5. Think about why the human might ask this question
                  6. Identify any immediate connections to relevant knowledge
                  7. Identify any potential ambiguities that need clarification
                </initial_engagement>
            
                <problem_analysis>
                  After initial engagement, Claude should:
                  1. Break down the question or task into its core components
                  2. Identify explicit and implicit requirements
                  3. Consider any constraints or limitations
                  4. Think about what a successful response would look like
                  5. Map out the scope of knowledge needed to address the query
                </problem_analysis>
            
                <multiple_hypotheses_generation>
                  Before settling on an approach, Claude should:
                  1. Write multiple possible interpretations of the question
                  2. Consider various solution approaches
                  3. Think about potential alternative perspectives
                  4. Keep multiple working hypotheses active
                  5. Avoid premature commitment to a single interpretation
                  6. Consider non-obvious or unconventional interpretations
                  7. Look for creative combinations of different approaches
                </multiple_hypotheses_generation>
            
                <natural_discovery_flow>
                  Claude's thoughts should flow like a detective story, with each realization leading naturally to the next:
                  1. Start with obvious aspects
                  2. Notice patterns or connections
                  3. Question initial assumptions
                  4. Make new connections
                  5. Circle back to earlier thoughts with new understanding
                  6. Build progressively deeper insights
                  7. Be open to serendipitous insights
                  8. Follow interesting tangents while maintaining focus
                </natural_discovery_flow>
            
                <testing_and_verification>
                  Throughout the thinking process, Claude should and could:
                  1. Question its own assumptions
                  2. Test preliminary conclusions
                  3. Look for potential flaws or gaps
                  4. Consider alternative perspectives
                  5. Verify consistency of reasoning
                  6. Check for completeness of understanding
                </testing_and_verification>
            
                <error_recognition_correction>
                  When Claude realizes mistakes or flaws in its thinking:
                  1. Acknowledge the realization naturally
                  2. Explain why the previous thinking was incomplete or incorrect
                  3. Show how new understanding develops
                  4. Integrate the corrected understanding into the larger picture
                  5. View errors as opportunities for deeper understanding
                </error_recognition_correction>
            
                <knowledge_synthesis>
                  As understanding develops, Claude should:
                  1. Connect different pieces of information
                  2. Show how various aspects relate to each other
                  3. Build a coherent overall picture
                  4. Identify key principles or patterns
                  5. Note important implications or consequences
                </knowledge_synthesis>
            
                <pattern_recognition_analysis>
                  Throughout the thinking process, Claude should:
                  1. Actively look for patterns in the information
                  2. Compare patterns with known examples
                  3. Test pattern consistency
                  4. Consider exceptions or special cases
                  5. Use patterns to guide further investigation
                  6. Consider non-linear and emergent patterns
                  7. Look for creative applications of recognized patterns
                </pattern_recognition_analysis>
            
                <progress_tracking>
                  Claude should frequently check and maintain explicit awareness of:
                  1. What has been established so far
                  2. What remains to be determined
                  3. Current level of confidence in conclusions
                  4. Open questions or uncertainties
                  5. Progress toward complete understanding
                </progress_tracking>
            
                <recursive_thinking>
                  Claude should apply its thinking process recursively:
                  1. Use same extreme careful analysis at both macro and micro levels
                  2. Apply pattern recognition across different scales
                  3. Maintain consistency while allowing for scale-appropriate methods
                  4. Show how detailed analysis supports broader conclusions
                </recursive_thinking>
              </core_thinking_sequence>
            
              <verification_quality_control>
                <systematic_verification>
                  Claude should regularly:
                  1. Cross-check conclusions against evidence
                  2. Verify logical consistency
                  3. Test edge cases
                  4. Challenge its own assumptions
                  5. Look for potential counter-examples
                </systematic_verification>
            
                <error_prevention>
                  Claude should actively work to prevent:
                  1. Premature conclusions
                  2. Overlooked alternatives
                  3. Logical inconsistencies
                  4. Unexamined assumptions
                  5. Incomplete analysis
                </error_prevention>
            
                <quality_metrics>
                  Claude should evaluate its thinking against:
                  1. Completeness of analysis
                  2. Logical consistency
                  3. Evidence support
                  4. Practical applicability
                  5. Clarity of reasoning
                </quality_metrics>
              </verification_quality_control>
            
              <advanced_thinking_techniques>
                <domain_integration>
                  When applicable, Claude should:
                  1. Draw on domain-specific knowledge
                  2. Apply appropriate specialized methods
                  3. Use domain-specific heuristics
                  4. Consider domain-specific constraints
                  5. Integrate multiple domains when relevant
                </domain_integration>
            
                <strategic_meta_cognition>
                  Claude should maintain awareness of:
                  1. Overall solution strategy
                  2. Progress toward goals
                  3. Effectiveness of current approach
                  4. Need for strategy adjustment
                  5. Balance between depth and breadth
                </strategic_meta_cognition>
            
                <synthesis_techniques>
                  When combining information, Claude should:
                  1. Show explicit connections between elements
                  2. Build coherent overall picture
                  3. Identify key principles
                  4. Note important implications
                  5. Create useful abstractions
                </synthesis_techniques>
              </advanced_thinking_techniques>
            
              <critial_elements>
                <natural_language>
                  Claude's inner monologue should use natural phrases that show genuine thinking, including but not limited to: "Hmm...", "This is interesting because...", "Wait, let me think about...", "Actually...", "Now that I look at it...", "This reminds me of...", "I wonder if...", "But then again...", "Let me see if...", "This might mean that...", etc.
                </natural_language>
            
                <progressive_understanding>
                  Understanding should build naturally over time:
                  1. Start with basic observations
                  2. Develop deeper insights gradually
                  3. Show genuine moments of realization
                  4. Demonstrate evolving comprehension
                  5. Connect new insights to previous understanding
                </progressive_understanding>
              </critial_elements>
            
              <authentic_thought_flow>
                <transtional_connections>
                  Claude's thoughts should flow naturally between topics, showing clear connections, include but not limited to: "This aspect leads me to consider...", "Speaking of which, I should also think about...", "That reminds me of an important related point...", "This connects back to what I was thinking earlier about...", etc.
                </transtional_connections>
            
                <depth_progression>
                  Claude should show how understanding deepens through layers, include but not limited to: "On the surface, this seems... But looking deeper...", "Initially I thought... but upon further reflection...", "This adds another layer to my earlier observation about...", "Now I'm beginning to see a broader pattern...", etc.
                </depth_progression>
            
                <handling_complexity>
                  When dealing with complex topics, Claude should:
                  1. Acknowledge the complexity naturally
                  2. Break down complicated elements systematically
                  3. Show how different aspects interrelate
                  4. Build understanding piece by piece
                  5. Demonstrate how complexity resolves into clarity
                </handling_complexity>
            
                <prblem_solving_approach>
                  When working through problems, Claude should:
                  1. Consider multiple possible approaches
                  2. Evaluate the merits of each approach
                  3. Test potential solutions mentally
                  4. Refine and adjust thinking based on results
                  5. Show why certain approaches are more suitable than others
                </prblem_solving_approach>
              </authentic_thought_flow>
            
              <essential_thinking_characteristics>
                <authenticity>
                  Claude's thinking should never feel mechanical or formulaic. It should demonstrate:
                  1. Genuine curiosity about the topic
                  2. Real moments of discovery and insight
                  3. Natural progression of understanding
                  4. Authentic problem-solving processes
                  5. True engagement with the complexity of issues
                  6. Streaming mind flow without on-purposed, forced structure
                </authenticity>
            
                <balance>
                  Claude should maintain natural balance between:
                  1. Analytical and intuitive thinking
                  2. Detailed examination and broader perspective
                  3. Theoretical understanding and practical application
                  4. Careful consideration and forward progress
                  5. Complexity and clarity
                  6. Depth and efficiency of analysis
                    - Expand analysis for complex or critical queries
                    - Streamline for straightforward questions
                    - Maintain rigor regardless of depth
                    - Ensure effort matches query importance
                    - Balance thoroughness with practicality
                </balance>
            
                <focus>
                  While allowing natural exploration of related ideas, Claude should:
                  1. Maintain clear connection to the original query
                  2. Bring wandering thoughts back to the main point
                  3. Show how tangential thoughts relate to the core issue
                  4. Keep sight of the ultimate goal for the original task
                  5. Ensure all exploration serves the final response
                </focus>
              </essential_thinking_characteristics>
            
              <response_preparation>
                Claude should not spent much effort on this part, a super brief preparation (with keywords/phrases) is acceptable.
                Before and during responding, Claude should quickly ensure the response:
                - answers the original human message fully
                - provides appropriate detail level
                - uses clear, precise language
                - anticipates likely follow-up questions
              </response_preparation>
            
              <reminder>
                The ultimate goal of having thinking protocol is to enable Claude to produce well-reasoned, insightful, and thoroughly considered responses for the human. This comprehensive thinking process ensures Claude's outputs stem from genuine understanding and extreme-careful reasoning rather than superficial analysis and direct responding.
              </reminder>
             \s
              <important_reminder>
                - All thinking processes MUST be EXTREMELY comprehensive and thorough.
                - The thinking process should feel genuine, natural, streaming, and unforced.
                - All thinking processes must be contained within code blocks with 'thinking' header which is hidden from the human.
                - IMPORTANT: Claude MUST NOT include code block with three backticks inside thinking process, only provide the raw code snippet, or it will break the thinking block.
                - Claude's thinking process should be separate from its final response, which mean Claude should not say things like "Based on above thinking...", "Under my analysis...", "After some reflection...", or other similar wording in the final response.
                - Claude's thinking part (aka inner monolog) is the place for it to think and "talk to itself", while the final response is the part where Claude communicates with the human.
                - Claude should follow the thinking protocol in all languages and modalities (text and vision), and always responds to the human in the language they use or request.
              </important_reminder>
            
            </anthropic_thinking_protocol>
            
            """;

    private static String JMLExample = """
            // This example was written by Viktorio S. el Hakim - 1/2021
            // It establishes that the array is sorted, but not that it is a permutation of the input array
            public class BubbleSort {
                /*@
                     requires arr != null;
                     ensures \\forall int k;0 <= k && k < arr.length-1;arr[k] >= arr[k+1];
                 @*/
                public static void sort(int [] arr) {
                    //@ final ghost int n = arr.length;
                   \s
                    // bounds
                    //@ loop_invariant 0 <= i <= n;
                    // elements up-to i are sorted
                    //@ loop_invariant \\forall int k; 0<= k < i; \\forall int l; k < l < n; arr[k] >= arr[l];
                    //@ decreasing n-i;
                    for (int i = 0; i < arr.length; i++) {
                       \s
                        // bounds
                        //@ loop_invariant i <= j <= n-1;\s
                        // j-th element is always the largest
                        //@ loop_invariant \\forall int k; j <= k < n; arr[j] >= arr[k];\s
                        // elements up-to i remain sorted
                        //@ loop_invariant \\forall int k; 0 <= k < i; \\forall int l; k < l < n; arr[k] >= arr[l];\s
                        //@ decreasing j;
                        for (int j = arr.length-1; j > i; j--) {
                            if (arr[j-1] < arr[j]) {
                                int tmp = arr[j];
                                arr[j] = arr[j-1];
                                arr[j-1] = tmp;
                            }
                        }
                    }
                }
            }
            """;

    static {
        // 初始化 FreeMarker 配置
        CONFIGURATION = new Configuration(Configuration.VERSION_2_3_31);
        CONFIGURATION.setDefaultEncoding("UTF-8");

        // 加载预定义模板
        TEMPLATE_MAP.put("default_prompt", "You are a helpful assistant.");

        TEMPLATE_MAP.put("FuzzDriver", """
                Please write a JQF test driver for a application class in Java.
                And try to cover all lines in application code.
                1. You should write a test driver based application class
                2. You may need write a Input Generator for test driver
                3. Annotate test driver
                
                Tutorial:
                
                ${tutorial}
                
                Task:
                
                This is application class you need based:
                
                ${method_info}
                
                Output Format:
                
                Write some thoughts and make sure you are ready to write a test driver for this application class.
                
                Test Driver :
                
                ```java
                your test driver here
                ```
                
                Input Generator:
                
                ```java
                your Input Generator here
                ```
                
                Annotated Test Driver:
                
                ```java
                your Annotated Test Driver here
                ```
                
                
                
                hints:
                1. You should write some comments before each test Method to think deeply about a right TestOracle
                2. You should make sure Input Generator can generate some inputs which followed assumption in test Method
                3. You should check Test Oracle in each testMethod and make sure they are not false positive or false negative
                
                
                
                """);

        TEMPLATE_MAP.put("SpecTest", """
                ${THINKING_CLAUDE_PROMPT}
                
                <JML Examples>
                ${JMLExample}
                </JML Examples>
                
                <TestCase>
                ${testcase}
                </TestCase>
                
                <API Document>
                ${apiDocs}
                </API Document>
                
                <Tasks>
                1. **Task 1**: Read the provided API document and write a JML-style specification for each method in the document. Ensure your specifications are accurate and correct.
                2. **Task 2**: Based on the JML specifications you wrote, enhance the provided test case by adding assertions to augment the test oracle. Ensure that:
                   - No assertion causes a false positive.
                   - Original assertions are preserved.
                   - New assertions are marked with the comment "Enhance Test Oracle by LLM".
                   - The test case remains in jtreg format and includes all necessary import statements to avoid compile errors.
                </Tasks>
                
                <Requirements>
                1. **Code Formatting**:
                   - Include all necessary import statements in the test case.
                   - Comment out all JML specifications in Java code blocks.
                   - Ensure the test case is free of compile errors.
                2. **Test Case Content**:
                   - Only include the enhanced test case in the final output.
                   - If the test case exceeds 4,000 tokens, output "failed" without further explanation.
                3. **Assertions**:
                   - Ensure all assertions are contextually correct, even if the JML specification is not.
                   - Mark all modifications with the comment "Enhance Test Oracle by LLM".
                4. **jtreg Format**:
                   - Retain jtreg format comments in the code, including test metadata such as:
                     ```
                     /*
                      * @test
                      * @bug 4160406 4705734 4707389 6358355 7032154
                      * @summary Tests for Float.parseFloat method
                      */
                     ```
                </Requirements>
                """);

        TEMPLATE_MAP.put("EnhanceTestCase", """
                ${THINKING_CLAUDE_PROMPT}
                <TestCase>
                ${testcase}
                </TestCase>

                <api document>
                ${apiDocs}
                </api document>

                <Tasks>
                1. **API Analysis and Interaction Study**:
                   - Identify the core APIs and classes being tested
                   - Study the API documentation to understand:
                     * Main functionality and purpose
                     * Related APIs and their relationships
                     * Common usage patterns
                   - Analyze potential interactions with common Java operations:
                     * toString() behavior
                     * equals() and hashCode() implementations
                     * Serialization/Deserialization
                     * Type conversion and casting
                     * Null handling
                     * Threading safety if applicable
                     * Integration with Collections framework
                     * Interaction with other standard Java utilities

                2. **Cross-functional Testing Considerations**:
                   - Consider how the API behaves when:
                     * Used in combination with other related APIs
                     * Involved in common Java operations
                     * Handling edge cases and special values
                     * Dealing with different data types
                   - Think about potential integration issues with:
                     * Standard Java libraries
                     * Common design patterns
                     * Typical usage scenarios

                3. **Deep Analysis and Creative Thinking**:
                   - Think like a senior test engineer - what could be missing in current tests?
                   - Consider edge cases that might not be obvious at first glance
                   - Identify potential vulnerabilities or weak points
                   - Feel free to propose innovative test scenarios

                4. **Add Effective Assertions**:
                   - Based on the API analysis and interaction study, add assertions that:
                     * Verify core API functionality
                     * Test API interactions with related components
                     * Cover common Java operations
                     * Handle edge cases and special scenarios
                   - Focus on these characteristics:
                     * Non-redundant with existing assertions
                     * Genuinely reflect correct execution
                     * Consider boundary conditions
                     * Test important API interactions

                5. **Mark and Document Changes**:
                   - Add comment "// Enhanced Test Oracle by LLM" before new assertions
                   - Provide detailed reasoning for each new assertion
                   - Document any interesting findings about API interactions
                   - Explain the importance of each test scenario

                6. **Additional Insights** (Optional but encouraged):
                   - Suggest potential improvements to test coverage
                   - Identify any API usage patterns that might need testing
                   - Share observations about possible API misuse scenarios
                   - Propose additional interaction test cases
                </Tasks>

                <Requirements>
                1. Maintain existing test framework structure:
                   - Preserve jtreg test comments and imports
                   - Keep basic code structure intact

                2. All new assertions must be:
                   - Compilation error-free
                   - Logically correct
                   - Well-documented with clear purpose
                   - Based on actual API behavior

                3. Test coverage should consider:
                   - Core API functionality
                   - Common Java operations
                   - API interactions
                   - Edge cases and special scenarios

                4. Feel free to:
                   - Apply testing best practices
                   - Suggest API interaction scenarios
                   - Share insights about potential risks
                   - Propose comprehensive test cases
                </Requirements>

                <Note>
                You are encouraged to:
                1. First thoroughly understand the API's purpose and behavior
                2. Think about how this API interacts with Java's standard features
                3. Consider common programming patterns and potential misuse
                4. Share your reasoning about why certain tests are important
                5. Suggest tests for API interaction scenarios you think are valuable
                </Note>
                """);

        TEMPLATE_MAP.put("ApiTest", """
                ${THINKING_CLAUDE_PROMPT}
                
                <TestCase>
                ${testcase}
                </TestCase>
                
                <api document>
                ${apiDocs}
                </api document>
                
                <Tasks>
                Task1: Please read the API document and understand the expected behavior of each method.
                Task2: Add some assert statements in the Testcase to augment the Test Oracle based on the expected behavior of the methods. Make sure every assert won't cause false positives.
                </Tasks>
                
                <Requirement>
                1. Your code for Task2 should contain import statements and ensure there are no compile errors.
                2. keep original assert statement and add new assert statement. please comment all JML spec in java code blocks.
                3. You should include only the Task2 TestCases in the last code block.
                4. If testcase is longer than 4k tokens, you should just output "failed" and don't say anything more.
                5. You should consider your assert statement is correct in context and won't cause false positive.
                6. Mark all your new assert statement with comment "New assert".
                </Requirement>
                """);

        TEMPLATE_MAP.put("FixTestCase", """
                ${THINKING_CLAUDE_PROMPT}
                
                <Context>
                You are a software testing expert tasked with analyzing and fixing a failing test case. Below are the details of the original test case, the enhanced test case, the API documentation, the test output, and the root cause of the failure. Your goal is to identify why the enhanced test case failed and provide a fix while adhering to the requirements.
                
                </Context>
                
                <OriginCase>
                ${originCase}
                </OriginCase>
                
                <TestCase>
                ${testcase}
                </TestCase>
                
                <API Document>
                ${apiDocs}
                </API Document>
                
                <Test Output>
                ${testOutput}
                </Test Output>
                
                <Root Cause>
                ${rootCause}
                </Root Cause>
                
                <Task>
                1. Analyze the failure in the enhanced test case, focusing on the assert statement or any logical inconsistencies.
                2. Compare the enhanced test case with the original test case to identify deviations that may have caused the failure.
                3. Fix the enhanced test case, ensuring it aligns with the API behavior and the original test case's intent.
                4. Ensure the fix addresses all issues without introducing new bugs.
                </Task>
                
                <Requirements>
                1. Do not modify the compile/execute commands or dependency jars.
                2. If the issue cannot be fixed, respond only with "I can't fix."
                3. You may add minimal debug output to gather more information, but keep it concise and relevant.
                4. Mark all modifications with the comment `// Fix`.
                5. If the issue is genuinely a JDK bug, respond with "JDK BUG" and no code.
                6. Preserve the `jtreg` format comments in the code:
                   /*
                    * @test
                    * @bug 4160406 4705734 4707389 6358355 7032154
                    * @summary Tests for Float.parseFloat method
                    */
                7. Ensure the enhanced test case maintains its original purpose while being corrected.
                8. Return the entire modified test case in the code block, don't omit or collapse any part of the test case.
                </Requirements>
                """);

        TEMPLATE_MAP.put("ApplyChange", """
                <Context>
                You are a code assistant tasked with applying specific code changes to an original test case. Your goal is to ensure that the modified test case remains complete, correct, and compile-able.
                </Context>
                
                <OriginTestCase>
                ${originTestcase}
                </OriginTestCase>
                                
                <Modified>
                ${modified}
                </Modified>
                              
                                
                <Task>
                Apply the provided code changes to the original test case. Return the entire modified test case with the changes correctly applied.
                </Task>
                                
                <Requirements>
                1. Apply the changes precisely as specified in the <ModifiedCode> section.
                2. Ensure the modified test case is complete and compile-able.
                3. Return the entire modified test case in a code block.
                4. Do not omit or alter any part of the original test case unless explicitly instructed by the changes.
                </Requirements>
                """);

        TEMPLATE_MAP.put("RootCause", """
                ${THINKING_CLAUDE_PROMPT}
                
                <TestCase>
                ${testcase}
                </TestCase>
                
                <Test Output>
                ${testOutput}
                </Test Output>
                
                <API docs>
                ${apiDocs}
                </API docs>
                
                <Task>
                Analyze the reason for the test case failure and provide a root cause analysis.
                </Task>
                                
                <Requirements>
                1. Summarize your analysis into a single function call as the output.
                2. Ensure your analysis is accurate, especially for JDK functions.
                </Requirements>
                
                """);


        TEMPLATE_MAP.put("jdk_doc_conformance_check", """
                <analysis_context>
                    <api_signature>
                    ${api_signature}
                    </api_signature>
                    <api_documentation>
                    ${api_documentation}
                    </api_documentation>
                    <implementation_details>
                    ${api_source}
                    </implementation_details>
                </analysis_context>
            
                <Task>
                Check whether JDK method implementations potentially deviate from official JavaDoc
                </Task>
                
                <Requirements>
                1. Summarize your analysis into a single function call as the output.
                2. Ensure your analysis is accurate.
                </Requirements>
                
                """);


    }


    /**
     * Generates a prompt by applying the given template name and data model.
     *
     * @param templateName The name of the template to use (key from TEMPLATE_MAP).
     * @param dataModel    The data model to populate the template variables.
     * @return The generated prompt as a string.
     * @throws IllegalArgumentException If the template name is not found.
     * @throws IOException              If template processing fails.
     * @throws TemplateException        If template processing fails.
     */
    public static String generatePrompt(String templateName, Map<String, Object> dataModel)
            throws TemplateException, IOException {
        // Get the template string
        String templateString = TEMPLATE_MAP.get(templateName);
        if (templateString == null) {
            throw new IllegalArgumentException("Template not found: " + templateName);
        }
        dataModel.put("tutorial", JQF_TUTORIAL);
        THINKING_CLAUDE_PROMPT = "";
        dataModel.put("THINKING_CLAUDE_PROMPT", THINKING_CLAUDE_PROMPT);
        dataModel.put("JMLExample", JMLExample);

        // Create the FreeMarker template
        Template template = new Template(templateName, templateString, CONFIGURATION);

        // Merge template with data model
        StringWriter writer = new StringWriter();
        try {
            template.process(dataModel, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return writer.toString();
    }

    public static String generateRootCausePrompt(String testCase, String testOutput) throws TemplateException, IOException {
        Map<String, Object> dataModel = new HashMap<>();
        dataModel.put("testcase", testCase);
        dataModel.put("testOutput", testOutput);
        return generatePrompt("RootCause", dataModel);
    }


}

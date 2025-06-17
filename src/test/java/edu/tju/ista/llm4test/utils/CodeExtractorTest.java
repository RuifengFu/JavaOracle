package edu.tju.ista.llm4test.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

public class CodeExtractorTest {

    @Test
    public void testExtractCode() {
        String text = """
<thinking>

</thinking>

```java
//contains java code
```""";
        ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
        for (String code : codeBlocks) {
            System.out.println("Code: \n" + code);
        }
    }
}

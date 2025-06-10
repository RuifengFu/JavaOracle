package edu.tju.ista.llm4test.llm;

import edu.tju.ista.llm4test.utils.CodeExtractor;
import org.junit.Test;

import java.util.ArrayList;


public class OpenAITest {

    @Test
    public void testCall() {
        String res = OpenAI.R1.messageCompletion("write a snake game");
        System.out.println("Answer: " + res);
    }


    @Test
    public void testExtractCode() {
        String text = OpenAI.Doubao.messageCompletion("write a quicksort");
        ArrayList<String> codeBlocks = CodeExtractor.extractCode(text);
        for (String code: codeBlocks) {
            System.out.println("Code: \n" + code);
        }
    }


}

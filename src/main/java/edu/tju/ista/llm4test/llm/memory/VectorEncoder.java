package edu.tju.ista.llm4test.llm.memory;

import java.util.List;

public interface VectorEncoder {
    List<Float> encodeText(String text);
}
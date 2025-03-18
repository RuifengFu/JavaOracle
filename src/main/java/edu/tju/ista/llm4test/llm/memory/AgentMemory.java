package edu.tju.ista.llm4test.llm.memory;

import java.util.List;

public class AgentMemory implements AutoCloseable {
    private final MemoryStore memoryStore;
    private final VectorEncoder vectorEncoder;
    
    public AgentMemory(MilvusConfig config, VectorEncoder vectorEncoder) {
        this.memoryStore = new MemoryStore(config);
        this.vectorEncoder = vectorEncoder;
    }
    
    public void addMemory(String content, String metadata) {
        List<Float> vector = vectorEncoder.encodeText(content);
        Memory memory = new Memory(content, vector, metadata);
        memoryStore.storeMemory(memory);
    }
    
    public void addMemory(Memory memory) {
        if (memory.getVector() == null) {
            List<Float> vector = vectorEncoder.encodeText(memory.getContent());
            memory = new Memory(
                memory.getId(),
                memory.getContent(),
                vector,
                memory.getTimestamp(),
                memory.getMetadata()
            );
        }
        memoryStore.storeMemory(memory);
    }
    
    public List<Memory> findSimilarMemories(String queryText, int limit) {
        List<Float> queryVector = vectorEncoder.encodeText(queryText);
        return findSimilarMemories(queryVector, limit);
    }
    
    public List<Memory> findSimilarMemories(List<Float> queryVector, int limit) {
        return memoryStore.searchSimilarMemories(queryVector, limit);
    }
    
    public void forgetMemory(Long id) {
        memoryStore.deleteMemory(id);
    }
    
    @Override
    public void close() {
        memoryStore.close();
    }
}
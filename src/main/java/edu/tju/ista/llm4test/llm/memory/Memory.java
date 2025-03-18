package edu.tju.ista.llm4test.llm.memory;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;

public class Memory {
    private Long id;
    private String content;
    private List<Float> vector;
    private Instant timestamp;
    private String metadata;
    private Float similarityScore; // 新增字段：相似度分数

    public Memory(String content, List<Float> vector, String metadata) {
        this.content = content;
        this.vector = vector;
        this.metadata = metadata;
        this.timestamp = Instant.now();
    }

    public Memory(Long id, String content, List<Float> vector, Instant timestamp, String metadata) {
        this.id = id;
        this.content = content;
        this.vector = vector;
        this.timestamp = timestamp;
        this.metadata = metadata;
    }

    public Memory(Long id, String content, List<Float> vector, Instant timestamp, String metadata, Float similarityScore) {
        this.id = id;
        this.content = content;
        this.vector = vector;
        this.timestamp = timestamp;
        this.metadata = metadata;
        this.similarityScore = similarityScore;
    }

    public JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        if (id != null) {
            json.addProperty("memory_id", id);
        }
        json.addProperty("content", content);
        json.addProperty("timestamp", timestamp.toEpochMilli());
        json.addProperty("metadata", metadata);

        // 使用Gson将向量转换为JsonArray
        com.google.gson.Gson gson = new com.google.gson.Gson();
        json.add("vector", gson.toJsonTree(vector));

        return json;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public List<Float> getVector() {
        return vector;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getMetadata() {
        return metadata;
    }

    public Float getSimilarityScore() {
        return similarityScore;
    }

    public void setSimilarityScore(Float similarityScore) {
        this.similarityScore = similarityScore;
    }

    @Override
    public String toString() {
        return "Memory{" +
                "id=" + id +
                ", content='" + content + '\'' +
                ", timestamp=" + timestamp +
                ", metadata='" + metadata + '\'' +
                (similarityScore != null ? ", similarityScore=" + similarityScore : "") +
                '}';
    }
}
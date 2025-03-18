package edu.tju.ista.llm4test.llm.memory;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;

public class MilvusConfig {
    private final String uri;
    private final String token;
    private final String collectionName;
    private final int dimension;
    private final boolean secure;
    private final long connectTimeoutMs;

    public static MilvusConfig defaultConfig() {
        return new MilvusConfig("https://in03-7f34bee9ac02181.serverless.gcp-us-west1.cloud.zilliz.com", "cabef64e4423748c1e9dc2dae0f3a5231e4226248d86bc6e365a1c8e82aa04dfbe81ce633169744b21618164e45af7414d22e950", "default", 128, true, 1000);
    }
    public MilvusConfig(String uri, String token, String collectionName, int dimension, boolean secure, long connectTimeoutMs) {
        this.uri = uri;
        this.token = token;
        this.collectionName = collectionName;
        this.dimension = dimension;
        this.secure = secure;
        this.connectTimeoutMs = connectTimeoutMs;
    }
    
    public MilvusClientV2 createClient() {
        return new MilvusClientV2(ConnectConfig.builder()
                .uri(uri)
                .token(token)
                .secure(secure)
                .connectTimeoutMs(connectTimeoutMs)
                .build());
    }
    
    public String getUri() {
        return uri;
    }
    
    public String getToken() {
        return token;
    }
    
    public String getCollectionName() {
        return collectionName;
    }
    
    public int getDimension() {
        return dimension;
    }
}
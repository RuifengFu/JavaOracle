package edu.tju.ista.llm4test.llm.memory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;

import java.time.Instant;
import java.util.*;

public class MemoryStore {
    private final MilvusClientV2 client;
    private final String collectionName;
    private final int dimension;
    
    private static final String MEMORY_ID_FIELD = "memory_id";
    private static final String VECTOR_FIELD = "vector";
    private static final String CONTENT_FIELD = "content";
    private static final String TIMESTAMP_FIELD = "timestamp";
    private static final String METADATA_FIELD = "metadata";
    
    public MemoryStore(MilvusConfig config) {
        this.client = config.createClient();
        this.collectionName = config.getCollectionName();
        this.dimension = config.getDimension();
        
        initializeCollection();
    }
    
    private void initializeCollection() {
        // 代码与之前实现相同，初始化集合
        // 检查集合是否存在
        DescribeCollectionResp describeCollectionResp = null;
        try {
            describeCollectionResp = client.describeCollection(
                DescribeCollectionReq.builder()
                    .collectionName(collectionName)
                    .build()
            );
        } catch (Exception e) {
            // 集合不存在
            System.out.println("Collection does not exist, creating: " + e.getMessage());
        }
        
        if (describeCollectionResp != null) {
            // 集合存在，删除以重新创建
            client.dropCollection(
                DropCollectionReq.builder()
                    .collectionName(collectionName)
                    .build()
            );
        }
        
        // 创建集合及字段
        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .autoID(false)
                .dataType(DataType.Int64)
                .isPrimaryKey(true)
                .name(MEMORY_ID_FIELD)
                .build();
        
        CreateCollectionReq.FieldSchema vectorField = CreateCollectionReq.FieldSchema.builder()
                .dataType(DataType.FloatVector)
                .name(VECTOR_FIELD)
                .isPrimaryKey(false)
                .dimension(dimension)
                .build();
        
        CreateCollectionReq.FieldSchema contentField = CreateCollectionReq.FieldSchema.builder()
                .dataType(DataType.VarChar)
                .name(CONTENT_FIELD)
                .isPrimaryKey(false)
                .maxLength(65535)
                .build();
        
        CreateCollectionReq.FieldSchema timestampField = CreateCollectionReq.FieldSchema.builder()
                .dataType(DataType.Int64)
                .name(TIMESTAMP_FIELD)
                .isPrimaryKey(false)
                .build();
        
        CreateCollectionReq.FieldSchema metadataField = CreateCollectionReq.FieldSchema.builder()
                .dataType(DataType.VarChar)
                .name(METADATA_FIELD)
                .isPrimaryKey(false)
                .maxLength(65535)
                .build();
        
        List<CreateCollectionReq.FieldSchema> fieldSchemaList = new ArrayList<>();
        fieldSchemaList.add(idField);
        fieldSchemaList.add(contentField);
        fieldSchemaList.add(vectorField);
        fieldSchemaList.add(timestampField);
        fieldSchemaList.add(metadataField);
        
        CreateCollectionReq.CollectionSchema collectionSchema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(fieldSchemaList)
                .build();
        
        CreateCollectionReq createCollectionReq = CreateCollectionReq.builder()
                .collectionSchema(collectionSchema)
                .collectionName(collectionName)
                .enableDynamicField(false)
                .description("Agent Memory Collection")
                .numShards(1)
                .build();
        
        client.createCollection(createCollectionReq);
        
        // 创建索引
        IndexParam indexParam = IndexParam.builder()
                .fieldName(VECTOR_FIELD)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();
        
        client.createIndex(CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(Collections.singletonList(indexParam))
                .build());
        
        // 加载集合到内存
        client.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .async(false)
                .build());
    }
    
    public void storeMemory(Memory memory) {
        // 如果没有提供ID，则生成ID
        if (memory.getId() == null) {
            memory.setId(System.currentTimeMillis());
        }
        
        List<JsonObject> jsonList = new ArrayList<>();
        jsonList.add(memory.toJsonObject());
        
        InsertResp insertResp = client.insert(InsertReq.builder()
                .collectionName(collectionName)
                .data(jsonList)
                .build());
        
        // 插入后刷新，使数据立即可用
        client.flush(FlushReq.builder()
                .collectionNames(Collections.singletonList(collectionName))
                .build());
    }
    
    // 修改后的方法，处理嵌套搜索结果结构
    public List<Memory> searchSimilarMemories(List<Float> queryVector, int topK) {
        // 准备搜索数据
        List<BaseVector> data = new ArrayList<>();
        data.add(new FloatVec(queryVector));
        
        // 搜索参数
        Map<String, Object> searchParams = new HashMap<>();
        searchParams.put("level", 1);
        
        // 要返回的输出字段
        List<String> outputFields = Arrays.asList(
                MEMORY_ID_FIELD, CONTENT_FIELD, TIMESTAMP_FIELD, METADATA_FIELD
        );
        
        // 执行搜索
        SearchResp searchResp = client.search(SearchReq.builder()
                .data(data)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .collectionName(collectionName)
                .searchParams(searchParams)
                .outputFields(outputFields)
                .metricType(IndexParam.MetricType.COSINE)
                .topK(topK)
                .build());
        
        // 处理嵌套搜索结果
        List<Memory> results = new ArrayList<>();
        Gson gson = new Gson();
        
        // 获取搜索结果
        List<List<SearchResp.SearchResult>> searchResults = searchResp.getSearchResults();
        
        if (searchResults != null && !searchResults.isEmpty()) {
            // 只处理第一个查询向量的结果（因为我们只搜索了一个向量）
            List<SearchResp.SearchResult> queryResults = searchResults.get(0);
            
            for (SearchResp.SearchResult result : queryResults) {
                Map<String, Object> entity = result.getEntity();
                Float score = result.getScore();
                
                Long id = ((Number) entity.get(MEMORY_ID_FIELD)).longValue();
                String content = (String) entity.get(CONTENT_FIELD);
                Long timestampMillis = ((Number) entity.get(TIMESTAMP_FIELD)).longValue();
                Instant timestamp = Instant.ofEpochMilli(timestampMillis);
                String metadata = (String) entity.get(METADATA_FIELD);
                
                Memory memory = new Memory(id, content, null, timestamp, metadata);
                results.add(memory);
                
                // 可以选择添加相似度分数到内存对象的元数据中
                // 这里只是打印出来，但您可以修改Memory类以包含相似度分数
                System.out.println("Memory ID: " + id + ", Similarity Score: " + score);
            }
        }
        
        return results;
    }
    
    public void deleteMemory(Long id) {
        // 由于Milvus V2 API中无法直接通过ID删除，
        // 我们需要实现基于向量搜索的删除或使用更复杂的方法
        // 这是当前简化示例的局限性
        System.out.println("Delete operation not implemented in this example");
    }
    
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
package io.github.jerryt92.j2agent.service.rag.vdb.milvus;

import com.google.gson.JsonObject;
import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.model.Translator;
import io.github.jerryt92.j2agent.service.rag.knowledge.bo.KnowledgeVectorBo;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.partition.request.CreatePartitionReq;
import io.milvus.v2.service.partition.request.HasPartitionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.WeightedRanker;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class MilvusService implements VectorDatabaseService {
    private static final int COLLECTION_ABSENT_MAX_ATTEMPTS = 5;
    private static final long COLLECTION_ABSENT_WAIT_MS = 200L;
    private static final int SCHEMA_READ_MAX_ATTEMPTS = 5;

    private final String clusterEndpoint;
    private final String token;
    private final String schemaConfigPath;
    private IndexParam.MetricType metricType;
    private Integer lastDimension;
    private volatile MilvusClientV2 client;

    public MilvusService(
            String clusterEndpoint,
            String token,
            String schemaConfigPath
    ) {
        this.clusterEndpoint = clusterEndpoint;
        this.token = token;
        this.schemaConfigPath = schemaConfigPath;
    }

    @Override
    public void reBuildVectorDatabase(int dimension, String metricTypeStr) {
        metricType = IndexParam.MetricType.valueOf(metricTypeStr);
        lastDimension = dimension;
        initClientIfNeeded();
        log.info("Milvus 向量库参数已同步: lastDimension={}, metricType={}", lastDimension, metricType);
        // 启动阶段仅初始化连接与参数，不主动创建默认 collection。
        // 真实 collection 在写入时按文件映射按需创建，避免出现不需要的默认集合。
    }

    @Override
    public Integer getExpectedDimension() {
        return lastDimension;
    }

    /**
     * 在连接未初始化时创建 Milvus 客户端。
     */
    private void initClientIfNeeded() {
        if (client != null) {
            return;
        }
        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri(clusterEndpoint)
                .token(token)
                .build();
        this.client = new MilvusClientV2(connectConfig);
    }

    /**
     * 根据统一 schema 定义创建 collection 与索引。
     */
    private void createCollection(String targetCollectionName, int dimension) {
        log.info("Creating collection {} with embedding dimension={}", targetCollectionName, dimension);
        // 通过统一定义构建 Milvus schema
        CreateCollectionReq.CollectionSchema schema = client.createSchema();
        for (MilvusSchemaDefinition.FieldDef fieldDef : MilvusSchemaConfigLoader.load(schemaConfigPath, dimension)) {
            var fieldBuilder = AddFieldReq.builder()
                    .fieldName(fieldDef.getName())
                    .dataType(fieldDef.getDataType())
                    .description(fieldDef.getDescription())
                    .isPrimaryKey(fieldDef.isPrimaryKey())
                    .autoID(fieldDef.isAutoId());
            if (fieldDef.getMaxLength() != null) {
                fieldBuilder.maxLength(fieldDef.getMaxLength());
            }
            if (fieldDef.getDimension() != null) {
                fieldBuilder.dimension(fieldDef.getDimension());
            }
            if (fieldDef.isEnableAnalyzer()) {
                fieldBuilder.enableAnalyzer(true).analyzerParams(Map.of("tokenizer", "icu"));
            }
            schema.addField(fieldBuilder.build());
        }
        List<IndexParam> indexParams = new ArrayList<>();
        // Prepare index parameters
        IndexParam indexParamForIdField = IndexParam.builder()
                .fieldName(MilvusSchemaDefinition.FIELD_SEGMENT_ID)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .build();
        indexParams.add(indexParamForIdField);
        // 创建稠密向量索引
        IndexParam indexParamForVectorField = IndexParam.builder()
                .fieldName(MilvusSchemaDefinition.FIELD_EMBEDDING)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(metricType)
                .build();
        indexParams.add(indexParamForVectorField);
        // 创建稀疏向量索引
        IndexParam indexParamForSparseVectorField = IndexParam.builder()
                .fieldName(MilvusSchemaDefinition.FIELD_SPARSE)
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build();
        indexParams.add(indexParamForSparseVectorField);
        CreateCollectionReq.Function bm25Function = CreateCollectionReq.Function.builder()
                .name("text_bm25_emb")
                .description("将text通过BM25转换为稀疏向量")
                .functionType(FunctionType.BM25)
                .inputFieldNames(Collections.singletonList(MilvusSchemaDefinition.FIELD_TEXT))
                .outputFieldNames(Collections.singletonList(MilvusSchemaDefinition.FIELD_SPARSE))
                .build();
        schema.addFunction(bm25Function);
        // Create a collection with schema and index parameters
        CreateCollectionReq customizedSetupReq = CreateCollectionReq.builder()
                .collectionName(targetCollectionName)
                .collectionSchema(schema)
                .indexParams(indexParams)
                .build();

        client.createCollection(customizedSetupReq);

        // Get load state of the collection
        GetLoadStateReq customSetupLoadStateReq = GetLoadStateReq.builder()
                .collectionName(targetCollectionName)
                .build();

        Boolean loaded = client.getLoadState(customSetupLoadStateReq);
        log.info("Collection {} is loaded: {}", targetCollectionName, loaded);
        assertCollectionSchemaDimension(targetCollectionName, dimension);
    }

    /**
     * 按指定 collection 写入向量。
     */
    @Override
    public void putData(String targetCollectionName, List<KnowledgeVectorBo> knowledgeVectors) {
        putData(targetCollectionName, knowledgeVectors, null);
    }

    /**
     * 按指定 collection 写入向量，可选按知识库高级配置创建分区并写入首分区。
     */
    @Override
    public void putData(String targetCollectionName, List<KnowledgeVectorBo> knowledgeVectors, List<String> partitionNames) {
        initClientIfNeeded();
        ensureCollectionReady(targetCollectionName);
        ensurePartitionsExist(targetCollectionName, partitionNames);
        validateEmbeddingDimensions(targetCollectionName, knowledgeVectors);
        List<JsonObject> milvusData = new ArrayList<>();
        for (KnowledgeVectorBo vectorBo : knowledgeVectors) {
            milvusData.add(Translator.translateToMilvusData(vectorBo));
        }
        if (!milvusData.isEmpty()) {
            var upsertBuilder = UpsertReq.builder()
                    .collectionName(targetCollectionName)
                    .data(milvusData);
            String writePartition = resolveWritePartitionName(partitionNames);
            if (writePartition != null) {
                upsertBuilder.partitionName(writePartition);
            }
            UpsertResp upsertResp = client.upsert(upsertBuilder.build());
            log.info("Upserted {} vectors into collection {} partition {}", upsertResp.getUpsertCnt(), targetCollectionName,
                    writePartition == null ? "_default" : writePartition);
        }
    }

    /**
     * 确保自定义分区已创建（跳过内置 _default）。
     */
    private void ensurePartitionsExist(String targetCollectionName, List<String> partitionNames) {
        if (partitionNames == null || partitionNames.isEmpty()) {
            return;
        }
        for (String name : partitionNames) {
            if (name == null || name.isBlank() || "_default".equals(name)) {
                continue;
            }
            Boolean exists = client.hasPartition(HasPartitionReq.builder()
                    .collectionName(targetCollectionName)
                    .partitionName(name)
                    .build());
            if (Boolean.TRUE.equals(exists)) {
                continue;
            }
            client.createPartition(CreatePartitionReq.builder()
                    .collectionName(targetCollectionName)
                    .partitionName(name)
                    .build());
        }
    }

    /**
     * Upsert 使用的分区名：列表首项；若为 _default 则返回 null（由 SDK 走默认分区）。
     */
    private String resolveWritePartitionName(List<String> partitionNames) {
        if (partitionNames == null || partitionNames.isEmpty()) {
            return null;
        }
        String first = partitionNames.getFirst();
        if (first == null || first.isBlank() || "_default".equals(first)) {
            return null;
        }
        return first;
    }

    /**
     * 按指定 collection 进行稠密向量检索。
     */
    private List<EmbeddingModel.EmbeddingsQueryItem> knnRetrieval(String targetCollectionName, float[] queryVector, int topK, List<String> partitionNames) {
        validateQueryVectorDimension(targetCollectionName, queryVector);
        FloatVec floatVec = new FloatVec(queryVector);
        var searchBuilder = SearchReq.builder()
                .filter(io.github.jerryt92.j2agent.service.security.KnowledgeReadScope.filter())
                .collectionName(targetCollectionName)
                .data(List.of(floatVec))
                .topK(topK)
                .searchParams(Map.of(
                        "metric_type", metricType.toString(),
                        "anns_field", MilvusSchemaDefinition.FIELD_EMBEDDING
                ))
                .outputFields(MilvusSchemaDefinition.outputFields());
        applyPartitionNames(searchBuilder, partitionNames);
        SearchResp searchResp = client.search(searchBuilder.build());
        return toQueryItems(searchResp, ScoreChannel.DENSE);
    }

    private void applyPartitionNames(io.milvus.v2.service.vector.request.SearchReq.SearchReqBuilder<?, ?> builder, List<String> partitionNames) {
        if (partitionNames != null && !partitionNames.isEmpty()) {
            builder.partitionNames(partitionNames);
        }
    }

    private void applyPartitionNames(io.milvus.v2.service.vector.request.QueryReq.QueryReqBuilder<?, ?> builder, List<String> partitionNames) {
        if (partitionNames != null && !partitionNames.isEmpty()) {
            builder.partitionNames(partitionNames);
        }
    }

    private void applyPartitionNames(io.milvus.v2.service.vector.request.HybridSearchReq.HybridSearchReqBuilder<?, ?> builder, List<String> partitionNames) {
        if (partitionNames != null && !partitionNames.isEmpty()) {
            builder.partitionNames(partitionNames);
        }
    }

    /**
     * 按指定 collection 的源文件删除对应的全部分片向量。
     */
    @Override
    public void deleteBySourceFile(String targetCollectionName, String sourceFile) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return;
        }
        initClientIfNeeded();
        if (!hasCollection(targetCollectionName)) {
            return;
        }
        String escaped = sourceFile.replace("\\", "\\\\").replace("\"", "\\\"");
        DeleteResp deleteResp = client.delete(DeleteReq.builder()
                .collectionName(targetCollectionName)
                .filter(MilvusSchemaDefinition.FIELD_SOURCE_FILE + " == \"" + escaped + "\"")
                .build());
        log.info("Deleted {} vectors by source file {} in collection {}", deleteResp.getDeleteCnt(), sourceFile, targetCollectionName);
    }

    /**
     * 判断指定 collection 是否存在。
     */
    @Override
    public boolean hasCollection(String targetCollectionName) {
        initClientIfNeeded();
        return client.hasCollection(HasCollectionReq.builder().collectionName(targetCollectionName).build());
    }

    /**
     * 删除指定 collection。
     */
    @Override
    public void dropCollection(String targetCollectionName) {
        initClientIfNeeded();
        if (!hasCollection(targetCollectionName)) {
            return;
        }
        client.dropCollection(DropCollectionReq.builder().collectionName(targetCollectionName).build());
        log.info("Dropped collection {}", targetCollectionName);
        waitUntilCollectionAbsent(targetCollectionName);
    }

    /**
     * upsert 前校验向量维度与 collection schema，避免 Milvus ParamException。
     */
    private void validateEmbeddingDimensions(String targetCollectionName, List<KnowledgeVectorBo> knowledgeVectors) {
        if (lastDimension == null || knowledgeVectors == null || knowledgeVectors.isEmpty()) {
            return;
        }
        int schemaDimension = requireCollectionEmbeddingDimension(targetCollectionName);
        if (schemaDimension != lastDimension) {
            throw new IllegalStateException(
                    "Milvus collection schema 维度与当前 Embedding 不一致: collection=" + targetCollectionName
                            + ", schemaDimension=" + schemaDimension + ", expected=" + lastDimension
                            + "。请触发完全重建。");
        }
        for (KnowledgeVectorBo vectorBo : knowledgeVectors) {
            List<Float> embedding = vectorBo.getEmbedding();
            if (embedding == null) {
                continue;
            }
            if (embedding.size() != lastDimension) {
                throw new IllegalStateException(
                        "向量维度与当前 Embedding 不一致: expected=" + lastDimension + ", actual=" + embedding.size()
                                + ", collection=" + vectorBo.getCollectionTag()
                                + ", milvusSchemaDimension=" + schemaDimension);
            }
        }
        log.debug("Upsert 维度校验通过: collection={}, milvusLastDimension={}, schemaDimension={}, vectorBatchSize={}",
                targetCollectionName, lastDimension, schemaDimension, knowledgeVectors.size());
    }

    /**
     * 读取 collection 中 embedding 字段的 schema 维度（带重试）。
     */
    private Integer resolveCollectionEmbeddingDimensionInternal(String targetCollectionName) {
        if (targetCollectionName == null || targetCollectionName.isBlank() || !hasCollection(targetCollectionName)) {
            return null;
        }
        for (int attempt = 1; attempt <= SCHEMA_READ_MAX_ATTEMPTS; attempt++) {
            Integer dimension = readCollectionEmbeddingDimensionOnce(targetCollectionName);
            if (dimension != null) {
                return dimension;
            }
            sleepQuietly(COLLECTION_ABSENT_WAIT_MS);
        }
        return null;
    }

    private int requireCollectionEmbeddingDimension(String targetCollectionName) {
        Integer schemaDimension = resolveCollectionEmbeddingDimensionInternal(targetCollectionName);
        if (schemaDimension == null) {
            throw new IllegalStateException(
                    "无法读取 collection " + targetCollectionName + " 的 embedding schema 维度，拒绝 upsert/检索");
        }
        return schemaDimension;
    }

    private Integer readCollectionEmbeddingDimensionOnce(String targetCollectionName) {
        if (targetCollectionName == null || targetCollectionName.isBlank() || !hasCollection(targetCollectionName)) {
            return null;
        }
        DescribeCollectionResp response = client.describeCollection(DescribeCollectionReq.builder()
                .collectionName(targetCollectionName)
                .build());
        CreateCollectionReq.CollectionSchema schema = response.getCollectionSchema();
        if (schema == null) {
            return null;
        }
        CreateCollectionReq.FieldSchema embeddingField = schema.getField(MilvusSchemaDefinition.FIELD_EMBEDDING);
        return embeddingField == null ? null : embeddingField.getDimension();
    }

    /**
     * 确保 collection 存在且 embedding 字段维度与 {@link #lastDimension} 一致。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private org.redisson.api.RedissonClient maintenanceRedis;
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces maintenanceKeys;

    private void ensureCollectionReady(String targetCollectionName) {
        var lock=maintenanceRedis.getLock(maintenanceKeys.key("knowledge-repo:collection:"+targetCollectionName));
        lock.lock();
        try { ensureCollectionReadyLocked(targetCollectionName); }
        finally { if(lock.isHeldByCurrentThread()) lock.unlock(); }
    }
    private void ensureCollectionReadyLocked(String targetCollectionName) {
        if (lastDimension == null) {
            throw new IllegalStateException("未初始化向量维度，无法创建 collection: " + targetCollectionName);
        }
        if (!hasCollection(targetCollectionName)) {
            createCollection(targetCollectionName, lastDimension);
            return;
        }
        int schemaDimension = requireCollectionEmbeddingDimension(targetCollectionName);
        if (schemaDimension == lastDimension) {
            return;
        }
        throw new IllegalStateException("Collection dimension mismatch; an administrator must run a global embedding rebuild: " + targetCollectionName);
    }

    private void assertCollectionSchemaDimension(String targetCollectionName, int expectedDimension) {
        Integer schemaDimension = resolveCollectionEmbeddingDimensionInternal(targetCollectionName);
        if (schemaDimension == null || schemaDimension != expectedDimension) {
            dropCollection(targetCollectionName);
            throw new IllegalStateException(
                    "Collection " + targetCollectionName + " 创建后 schema 维度=" + schemaDimension
                            + " 与期望 " + expectedDimension + " 不一致，已 drop");
        }
        log.info("Collection {} schema 维度校验通过: milvusLastDimension={}, collectionSchemaDimension={}",
                targetCollectionName, lastDimension, schemaDimension);
    }

    private void waitUntilCollectionAbsent(String targetCollectionName) {
        for (int attempt = 1; attempt <= COLLECTION_ABSENT_MAX_ATTEMPTS; attempt++) {
            if (!hasCollection(targetCollectionName)) {
                return;
            }
            sleepQuietly(COLLECTION_ABSENT_WAIT_MS);
        }
        if (hasCollection(targetCollectionName)) {
            log.warn("Collection {} drop 后仍存在（已重试 {} 次），可能影响后续 create",
                    targetCollectionName, COLLECTION_ABSENT_MAX_ATTEMPTS);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 检索前校验 query 向量维度与 collection schema / 期望维度一致。
     */
    private void validateQueryVectorDimension(String targetCollectionName, float[] queryVector) {
        if (queryVector == null || queryVector.length == 0) {
            return;
        }
        if (lastDimension != null && queryVector.length != lastDimension) {
            throw new IllegalStateException(
                    "检索向量维度与当前 Embedding 不一致: expected=" + lastDimension + ", actual=" + queryVector.length
                            + ", collection=" + targetCollectionName);
        }
        Integer schemaDimension = resolveCollectionEmbeddingDimensionInternal(targetCollectionName);
        if (schemaDimension != null && schemaDimension != queryVector.length) {
            throw new IllegalStateException(
                    "检索向量维度与 Milvus collection schema 不一致: collection=" + targetCollectionName
                            + ", schemaDimension=" + schemaDimension + ", actual=" + queryVector.length
                            + "。请等待知识库完全重建完成。");
        }
    }

    /**
     * 若不存在则创建指定 collection。
     */
    @Override
    public void createCollectionIfAbsent(String targetCollectionName) {
        initClientIfNeeded();
        ensureCollectionReady(targetCollectionName);
    }

    /**
     * 列出当前 Milvus 实例中的全部 collection。
     */
    @Override
    public List<String> listCollections() {
        initClientIfNeeded();
        return client.listCollections().getCollectionNames();
    }

    /**
     * 删除当前 Milvus 实例中的全部 collection。
     */
    @Override
    public void dropAllCollections() {
        List<String> collectionNames = new ArrayList<>(listCollections());
        log.warn("Milvus 全量清理开始，待删除 collection={}", collectionNames);
        for (String collectionName : collectionNames) {
            dropCollection(collectionName);
        }
        log.warn("Milvus 全量清理完成，删除 collection 数量={}", collectionNames.size());
    }

    @Override
    public Integer resolveCollectionEmbeddingDimension(String collectionName) {
        return resolveCollectionEmbeddingDimensionInternal(collectionName);
    }

    @Override
    public String sampleStoredCheckEmbeddingHash(String targetCollectionName) {
        initClientIfNeeded();
        if (targetCollectionName == null || targetCollectionName.isBlank() || !hasCollection(targetCollectionName)) {
            return null;
        }
        QueryResp queryResp = client.query(QueryReq.builder()
                .collectionName(targetCollectionName)
                .filter(MilvusSchemaDefinition.FIELD_TEXT_CHUNK_ID + " != \"\"")
                .limit(1)
                .outputFields(List.of(MilvusSchemaDefinition.FIELD_CHECK_EMBEDDING_HASH))
                .build());
        if (queryResp == null || queryResp.getQueryResults() == null || queryResp.getQueryResults().isEmpty()) {
            return null;
        }
        Object hash = queryResp.getQueryResults().getFirst().getEntity()
                .get(MilvusSchemaDefinition.FIELD_CHECK_EMBEDDING_HASH);
        return hash == null ? null : String.valueOf(hash);
    }

    @Override
    public void resetClient() {
        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri(clusterEndpoint)
                .token(token)
                .build();
        MilvusClientV2 freshClient = new MilvusClientV2(connectConfig);
        MilvusClientV2 oldClient = this.client;
        this.client = freshClient;
        if (oldClient != null) {
            try {
                oldClient.close();
            } catch (Exception e) {
                log.warn("关闭旧 Milvus 客户端异常", e);
            }
        }
        log.warn("Milvus 客户端已重建，schema 缓存已清空");
    }

    /**
     * 按指定 collection 分页查询知识分片。
     */
    @Override
    public List<EmbeddingModel.EmbeddingsQueryItem> queryKnowledge(String targetCollectionName,
                                                                   int offset,
                                                                   int limit,
                                                                   String search) {
        return queryKnowledge(targetCollectionName, offset, limit, search, null);
    }

    @Override
    public List<EmbeddingModel.EmbeddingsQueryItem> queryKnowledge(String targetCollectionName,
                                                                   int offset,
                                                                   int limit,
                                                                   String search,
                                                                   List<String> partitionNames) {
        initClientIfNeeded();
        if (targetCollectionName == null || targetCollectionName.isBlank() || !hasCollection(targetCollectionName)) {
            return Collections.emptyList();
        }
        var queryBuilder = QueryReq.builder()
                .collectionName(targetCollectionName)
                .filter(buildKnowledgeQueryFilter(search))
                .offset(Math.max(offset, 0))
                .limit(Math.max(limit, 1))
                .outputFields(MilvusSchemaDefinition.outputFields());
        applyPartitionNames(queryBuilder, partitionNames);
        QueryResp queryResp = client.query(queryBuilder.build());
        return toQueryItems(queryResp);
    }

    private String buildKnowledgeQueryFilter(String search) {
        if (search == null || search.isBlank()) {
            return MilvusSchemaDefinition.FIELD_TEXT_CHUNK_ID + " != \"\"";
        }
        String escaped = escapeFilterString(search.trim());
        return MilvusSchemaDefinition.FIELD_TEXT + " like \"%" + escaped + "%\""
                + " or " + MilvusSchemaDefinition.FIELD_HEADING_PATH + " like \"%" + escaped + "%\""
                + " or " + MilvusSchemaDefinition.FIELD_TYPE + " like \"%" + escaped + "%\""
                + " or " + MilvusSchemaDefinition.FIELD_SOURCE_FILE + " like \"%" + escaped + "%\"";
    }

    private String escapeFilterString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 按指定 collection 执行混合检索。
     */
    @Override
    public List<EmbeddingModel.EmbeddingsQueryItem> hybridRetrieval(String targetCollectionName,
                                                                    String queryText,
                                                                    float[] queryVector,
                                                                    int topK,
                                                                    String metricTypeStr,
                                                                    float denseWeight,
                                                                    float sparseWeight) {
        return hybridRetrieval(targetCollectionName, queryText, queryVector, topK, metricTypeStr, denseWeight, sparseWeight, null);
    }

    /**
     * 按指定 collection 与可选分区执行混合检索。
     */
    @Override
    public List<EmbeddingModel.EmbeddingsQueryItem> hybridRetrieval(String targetCollectionName,
                                                                    String queryText,
                                                                    float[] queryVector,
                                                                    int topK,
                                                                    String metricTypeStr,
                                                                    float denseWeight,
                                                                    float sparseWeight,
                                                                    List<String> partitionNames) {
        initClientIfNeeded();
        log.info("Milvus混合检索请求: collection={}, topK={}, metricType={}, denseWeight={}, sparseWeight={}, partitions={}, queryPreview={}",
                targetCollectionName, topK, metricTypeStr, denseWeight, sparseWeight, partitionNames, previewForLog(queryText));
        if (targetCollectionName == null || targetCollectionName.isBlank() || !hasCollection(targetCollectionName)) {
            return Collections.emptyList();
        }
        validateQueryVectorDimension(targetCollectionName, queryVector);
        float safeDenseWeight = Math.max(denseWeight, 0f);
        float safeSparseWeight = Math.max(sparseWeight, 0f);
        if (safeDenseWeight <= 0f && safeSparseWeight <= 0f) {
            safeDenseWeight = 1f;
        }
        if (safeSparseWeight <= 0f) {
            return knnRetrieval(targetCollectionName, queryVector, topK, partitionNames);
        }
        if (safeDenseWeight <= 0f) {
            return sparseRetrieval(targetCollectionName, queryText, topK, partitionNames);
        }
        IndexParam.MetricType denseMetricType = metricType;
        if (metricTypeStr != null) {
            try {
                denseMetricType = IndexParam.MetricType.valueOf(metricTypeStr);
            } catch (IllegalArgumentException ignored) {
                denseMetricType = metricType;
            }
        }
        AnnSearchReq denseSearchReq = AnnSearchReq.builder()
                .expr(io.github.jerryt92.j2agent.service.security.KnowledgeReadScope.filter())
                .vectorFieldName(MilvusSchemaDefinition.FIELD_EMBEDDING)
                .metricType(denseMetricType)
                .vectors(List.of(new FloatVec(queryVector)))
                .topK(topK)
                .params("{}")
                .build();
        AnnSearchReq sparseSearchReq = AnnSearchReq.builder()
                .expr(io.github.jerryt92.j2agent.service.security.KnowledgeReadScope.filter())
                .vectorFieldName(MilvusSchemaDefinition.FIELD_SPARSE)
                .metricType(IndexParam.MetricType.BM25)
                .vectors(List.of(new EmbeddedText(queryText)))
                .topK(topK)
                .params("{}")
                .build();
        var hybridBuilder = HybridSearchReq.builder()
                .collectionName(targetCollectionName)
                .searchRequests(List.of(denseSearchReq, sparseSearchReq))
                .ranker(new WeightedRanker(List.of(safeDenseWeight, safeSparseWeight)))
                .topK(topK)
                .outFields(MilvusSchemaDefinition.outputFields());
        applyPartitionNames(hybridBuilder, partitionNames);
        SearchResp searchResp = client.hybridSearch(hybridBuilder.build());
        int scoreTopK = Math.max(topK, 50);
        var denseSearchBuilder = SearchReq.builder()
                .filter(io.github.jerryt92.j2agent.service.security.KnowledgeReadScope.filter())
                .collectionName(targetCollectionName)
                .data(List.of(new FloatVec(queryVector)))
                .topK(scoreTopK)
                .searchParams(Map.of(
                        "metric_type", denseMetricType.toString(),
                        "anns_field", MilvusSchemaDefinition.FIELD_EMBEDDING
                ))
                .outputFields(List.of(MilvusSchemaDefinition.FIELD_SEGMENT_ID));
        applyPartitionNames(denseSearchBuilder, partitionNames);
        SearchResp denseResp = client.search(denseSearchBuilder.build());
        var sparseSearchBuilder = SearchReq.builder()
                .filter(io.github.jerryt92.j2agent.service.security.KnowledgeReadScope.filter())
                .collectionName(targetCollectionName)
                .data(List.of(new EmbeddedText(queryText)))
                .annsField(MilvusSchemaDefinition.FIELD_SPARSE)
                .metricType(IndexParam.MetricType.BM25)
                .topK(scoreTopK)
                .outputFields(List.of(MilvusSchemaDefinition.FIELD_SEGMENT_ID));
        applyPartitionNames(sparseSearchBuilder, partitionNames);
        SearchResp sparseResp = client.search(sparseSearchBuilder.build());
        Map<String, Float> denseScoreMap = extractScoreMap(denseResp);
        Map<String, Float> sparseScoreMap = extractScoreMap(sparseResp);
        return toQueryItemsHybrid(searchResp, denseScoreMap, sparseScoreMap);
    }

    /**
     * 日志预览查询文本，避免输出过长原文。
     */
    private String previewForLog(String queryText) {
        if (queryText == null) {
            return "";
        }
        String normalized = queryText.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 120) + "...";
    }

    /**
     * 按指定 collection 进行稀疏检索。
     */
    private List<EmbeddingModel.EmbeddingsQueryItem> sparseRetrieval(String targetCollectionName, String queryText, int topK, List<String> partitionNames) {
        var searchBuilder = SearchReq.builder()
                .filter(io.github.jerryt92.j2agent.service.security.KnowledgeReadScope.filter())
                .collectionName(targetCollectionName)
                .data(List.of(new EmbeddedText(queryText)))
                .annsField(MilvusSchemaDefinition.FIELD_SPARSE)
                .metricType(IndexParam.MetricType.BM25)
                .topK(topK)
                .outputFields(MilvusSchemaDefinition.outputFields());
        applyPartitionNames(searchBuilder, partitionNames);
        SearchResp searchResp = client.search(searchBuilder.build());
        return toQueryItems(searchResp, ScoreChannel.SPARSE);
    }

    private List<EmbeddingModel.EmbeddingsQueryItem> toQueryItems(SearchResp searchResp, ScoreChannel channel) {
        List<List<SearchResp.SearchResult>> results = searchResp.getSearchResults();
        List<SearchResp.SearchResult> searchResults = results.isEmpty() ? Collections.emptyList() : results.get(0);
        List<EmbeddingModel.EmbeddingsQueryItem> embeddingsQueryItems = new ArrayList<>();
        for (SearchResp.SearchResult searchResult : searchResults) {
            String hash = resolveHash(searchResult);
            Float score = searchResult.getScore();
            EmbeddingModel.EmbeddingsQueryItem embeddingsQueryItem = new EmbeddingModel.EmbeddingsQueryItem()
                    .setHash(hash)
                    .setScore(score == null ? 0f : score)
                    .setEmbeddingModel((String) searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_EMBEDDING_MODEL))
                    .setEmbeddingProvider((String) searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_EMBEDDING_PROVIDER))
                    .setText((String) searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_TEXT))
                    .setTextChunkId(String.valueOf(searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_TEXT_CHUNK_ID)))
                    .setSourceFile((String) searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_SOURCE_FILE))
                    .setType((String) searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_TYPE))
                    .setHeadingPath((String) searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_HEADING_PATH));
            if (score != null) {
                if (channel == ScoreChannel.DENSE) {
                    embeddingsQueryItem.setDenseScore(score).setHybridScore(score);
                } else if (channel == ScoreChannel.SPARSE) {
                    embeddingsQueryItem.setSparseScore(score).setHybridScore(score);
                } else {
                    embeddingsQueryItem.setHybridScore(score);
                }
            }
            embeddingsQueryItems.add(embeddingsQueryItem);
        }
        return embeddingsQueryItems;
    }

    private List<EmbeddingModel.EmbeddingsQueryItem> toQueryItems(QueryResp queryResp) {
        if (queryResp == null || queryResp.getQueryResults() == null) {
            return Collections.emptyList();
        }
        List<EmbeddingModel.EmbeddingsQueryItem> embeddingsQueryItems = new ArrayList<>();
        for (QueryResp.QueryResult queryResult : queryResp.getQueryResults()) {
            Map<String, Object> entity = queryResult.getEntity();
            EmbeddingModel.EmbeddingsQueryItem embeddingsQueryItem = new EmbeddingModel.EmbeddingsQueryItem()
                    .setHash(toString(entity.get(MilvusSchemaDefinition.FIELD_SEGMENT_ID)))
                    .setTextChunkId(toString(entity.get(MilvusSchemaDefinition.FIELD_TEXT_CHUNK_ID)))
                    .setEmbeddingModel(toString(entity.get(MilvusSchemaDefinition.FIELD_EMBEDDING_MODEL)))
                    .setEmbeddingProvider(toString(entity.get(MilvusSchemaDefinition.FIELD_EMBEDDING_PROVIDER)))
                    .setText(toString(entity.get(MilvusSchemaDefinition.FIELD_TEXT)))
                    .setSourceFile(toString(entity.get(MilvusSchemaDefinition.FIELD_SOURCE_FILE)))
                    .setType(toString(entity.get(MilvusSchemaDefinition.FIELD_TYPE)))
                    .setHeadingPath(toString(entity.get(MilvusSchemaDefinition.FIELD_HEADING_PATH)));
            embeddingsQueryItems.add(embeddingsQueryItem);
        }
        return embeddingsQueryItems;
    }

    private String toString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<EmbeddingModel.EmbeddingsQueryItem> toQueryItemsHybrid(SearchResp searchResp,
                                                                        Map<String, Float> denseScoreMap,
                                                                        Map<String, Float> sparseScoreMap) {
        List<List<SearchResp.SearchResult>> results = searchResp.getSearchResults();
        List<SearchResp.SearchResult> searchResults = results.isEmpty() ? Collections.emptyList() : results.get(0);
        List<EmbeddingModel.EmbeddingsQueryItem> embeddingsQueryItems = new ArrayList<>();
        for (SearchResp.SearchResult searchResult : searchResults) {
            String hash = resolveHash(searchResult);
            Float hybridScore = searchResult.getScore();
            EmbeddingModel.EmbeddingsQueryItem embeddingsQueryItem = new EmbeddingModel.EmbeddingsQueryItem()
                    .setHash(hash)
                    .setScore(hybridScore == null ? 0f : hybridScore)
                    .setHybridScore(hybridScore)
                    .setDenseScore(denseScoreMap.get(hash))
                    .setSparseScore(sparseScoreMap.get(hash))
                    .setEmbeddingModel((String) searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_EMBEDDING_MODEL))
                    .setEmbeddingProvider((String) searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_EMBEDDING_PROVIDER))
                    .setText((String) searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_TEXT))
                    .setTextChunkId(String.valueOf(searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_TEXT_CHUNK_ID)))
                    .setSourceFile((String) searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_SOURCE_FILE))
                    .setType((String) searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_TYPE))
                    .setHeadingPath((String) searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_HEADING_PATH));
            embeddingsQueryItems.add(embeddingsQueryItem);
        }
        return embeddingsQueryItems;
    }

    private Map<String, Float> extractScoreMap(SearchResp searchResp) {
        Map<String, Float> scoreMap = new HashMap<>();
        List<List<SearchResp.SearchResult>> results = searchResp.getSearchResults();
        List<SearchResp.SearchResult> searchResults = results.isEmpty() ? Collections.emptyList() : results.get(0);
        for (SearchResp.SearchResult searchResult : searchResults) {
            String hash = resolveHash(searchResult);
            if (hash != null && searchResult.getScore() != null) {
                scoreMap.put(hash, searchResult.getScore());
            }
        }
        return scoreMap;
    }

    private String resolveHash(SearchResp.SearchResult searchResult) {
        if (searchResult == null) {
            return null;
        }
        Object hashObj = searchResult.getEntity() == null ? null : searchResult.getEntity().get(MilvusSchemaDefinition.FIELD_SEGMENT_ID);
        if (hashObj != null) {
            return hashObj.toString();
        }
        Object id = searchResult.getId();
        return id == null ? null : id.toString();
    }

    private enum ScoreChannel {
        DENSE,
        SPARSE,
        HYBRID
    }
}

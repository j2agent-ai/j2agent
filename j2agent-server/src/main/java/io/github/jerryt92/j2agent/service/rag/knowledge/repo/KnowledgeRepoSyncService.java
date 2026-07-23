package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.VectorDatabaseInit;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.MilvusKnowledgeWriteService;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeTextChunkService;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import io.github.jerryt92.j2agent.utils.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 知识库目录同步服务。
 */
@Slf4j
@Service
@DependsOn("flywayInitializer")
public class KnowledgeRepoSyncService {
    private static final long WATCH_SYNC_DEBOUNCE_MILLIS = 30_000L;

    /**
     * 文件最新状态：含 collection 与可选 Milvus 分区列表。
     */
    private record FileState(String fileSha256, String infoJsonHash, String collectionName, List<String> partitionNames,
                             String diffHash) {
    }

    private final KnowledgeRepoMetadataService metadataService;
    private final KnowledgeRepoHashTreeService hashTreeService;
    private final KnowledgeTextChunkParser knowledgeTextChunkParser;
    private final KnowledgeMarkdownImageRewriter knowledgeMarkdownImageRewriter;
    private final MilvusKnowledgeWriteService milvusKnowledgeWriteService;
    private final EmbeddingService embeddingService;
    private final VectorDatabaseService vectorDatabaseService;
    private final VectorDatabaseInit vectorDatabaseInit;
    private final KnowledgeTextChunkService knowledgeTextChunkService;
    private final KnowledgeRepoSyncProgressTracker progressTracker;
    private final KnowledgeRepoHashCache hashCache = new KnowledgeRepoHashCache();
    private final Set<Path> watchedDirectories = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean watchSyncScheduled = new AtomicBoolean(false);
    private final AtomicLong lastWatchEventTime = new AtomicLong(0L);
    private WatchService watchService;
    private volatile Consumer<String> watchSyncTrigger;

    public KnowledgeRepoSyncService(KnowledgeRepoMetadataService metadataService,
                                    KnowledgeRepoHashTreeService hashTreeService,
                                    KnowledgeTextChunkParser knowledgeTextChunkParser,
                                    KnowledgeMarkdownImageRewriter knowledgeMarkdownImageRewriter,
                                    MilvusKnowledgeWriteService milvusKnowledgeWriteService,
                                    EmbeddingService embeddingService,
                                    VectorDatabaseService vectorDatabaseService,
                                    VectorDatabaseInit vectorDatabaseInit,
                                    KnowledgeTextChunkService knowledgeTextChunkService,
                                    KnowledgeRepoSyncProgressTracker progressTracker) {
        this.metadataService = metadataService;
        this.hashTreeService = hashTreeService;
        this.knowledgeTextChunkParser = knowledgeTextChunkParser;
        this.knowledgeMarkdownImageRewriter = knowledgeMarkdownImageRewriter;
        this.milvusKnowledgeWriteService = milvusKnowledgeWriteService;
        this.embeddingService = embeddingService;
        this.vectorDatabaseService = vectorDatabaseService;
        this.vectorDatabaseInit = vectorDatabaseInit;
        this.knowledgeTextChunkService = knowledgeTextChunkService;
        this.progressTracker = progressTracker;
    }

    private record UpsertOutcome(boolean synced, int knowledgeCount, String skipReason) {
        static UpsertOutcome synced(int knowledgeCount) {
            return new UpsertOutcome(true, knowledgeCount, null);
        }

        static UpsertOutcome skipped(String reason) {
            return new UpsertOutcome(false, 0, reason);
        }
    }

    /**
     * 从 DB 加载 hash 快照到内存。
     */
    public void initializeHashCache() {
        hashCache.replaceAll(hashTreeService.loadSnapshot());
    }

    /**
     * 启动时判断是否需要 exclusive 完全重建（Embedding 维度或模型指纹与 Milvus 中已有数据不一致）。
     */
    public boolean needsEmbeddingFullRebuild() {
        if (!embeddingService.isReady()) {
            return false;
        }
        metadataService.reloadMetadata();
        Integer expectedDimension = embeddingService.getDimension();
        String expectedHash = embeddingService.getCheckEmbeddingHash();
        for (String collectionName : collectKnowledgeCollectionNames()) {
            if (!vectorDatabaseService.hasCollection(collectionName)) {
                continue;
            }
            Integer schemaDimension = vectorDatabaseService.resolveCollectionEmbeddingDimension(collectionName);
            if (schemaDimension != null && expectedDimension != null && !schemaDimension.equals(expectedDimension)) {
                log.warn("检测到 collection {} schema 维度={} 与当前 Embedding 维度={} 不一致，需要完全重建",
                        collectionName, schemaDimension, expectedDimension);
                return true;
            }
            String storedHash = vectorDatabaseService.sampleStoredCheckEmbeddingHash(collectionName);
            if (storedHash != null && expectedHash != null && !storedHash.equals(expectedHash)) {
                log.warn("检测到 collection {} 存储的 check_embedding_hash 与当前 Embedding 不一致，需要完全重建",
                        collectionName);
                return true;
            }
        }
        return false;
    }

    /**
     * 执行增量同步（由协调器在持锁后调用）。
     */
    public void executeIncrementalSync(KnowledgeRepoSyncGuard guard) {
        if (!guard.shouldContinue()) {
            return;
        }
        if (!embeddingService.isReady()) {
            log.warn("Embedding 当前不可用，本轮增量同步跳过");
            return;
        }
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null || !Files.exists(rootPath)) {
            return;
        }
        try {
            doSyncBody(rootPath, guard);
        } catch (Exception e) {
            log.error("知识库增量同步失败: 根目录={}", rootPath.toAbsolutePath().normalize(), e);
            throw e;
        }
    }

    /**
     * 执行完全重建：drop 知识库 collection + 清空 hash + 获取当前 Embedding 维度 + 全量 re-embed。
     *
     * @return 是否完整执行成功（准备、同步均完成且未被 guard 中断）
     */
    public boolean executeFullRebuild(KnowledgeRepoSyncGuard guard) {
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null || !Files.exists(rootPath)) {
            log.warn("完全重建跳过：知识库根目录不可用");
            return false;
        }
        try {
            if (!guard.shouldContinue()) {
                log.info("完全重建在准备阶段被打断");
                return false;
            }
            if (!runFullRebuildPreparation(guard)) {
                log.warn("完全重建准备未完成");
                return false;
            }
            doSyncBody(rootPath, guard);
            if (!guard.shouldContinue()) {
                log.error("完全重建在同步 body 阶段被中断，Milvus/哈希可能处于不完整状态，请再次执行完全重建");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("知识库完全重建失败: 根目录={}", rootPath.toAbsolutePath().normalize(), e);
            throw e;
        }
    }

    private boolean runFullRebuildPreparation(KnowledgeRepoSyncGuard guard) {
        if (!guard.shouldContinue()) {
            log.info("完全重建准备被打断");
            return false;
        }
        progressTracker.beginPreparing();
        metadataService.reloadMetadata();
        Set<String> knowledgeCollections = collectKnowledgeCollectionNames();
        log.warn("知识库完全重建准备开始：将 drop 知识库绑定 collections={}", knowledgeCollections);
        dropKnowledgeCollections(knowledgeCollections);
        hashTreeService.deleteAll();
        knowledgeTextChunkService.deleteAll();
        hashCache.replaceAll(Map.of());

        if (!guard.shouldContinue()) {
            log.info("完全重建在 drop 后、probe 前被打断");
            return false;
        }
        vectorDatabaseService.resetClient();
        if (!vectorDatabaseInit.probeAndConfigure()) {
            log.warn("完全重建准备失败：Embedding 探测未成功, probeError={}",
                    embeddingService.getLastProbeError());
            return false;
        }
        Integer probeDimension = embeddingService.getDimension();
        Integer milvusLastDimension = vectorDatabaseService.getExpectedDimension();
        log.warn("知识库完全重建获取当前 Embedding 维度完成：embeddingProbeDimension={}, milvusLastDimension={}",
                probeDimension, milvusLastDimension);
        return true;
    }

    private Set<String> collectKnowledgeCollectionNames() {
        LinkedHashSet<String> collectionNames = new LinkedHashSet<>();
        collectionNames.addAll(metadataService.listConfiguredCollectionNames());
        collectionNames.addAll(hashTreeService.loadActiveCollectionCounts().keySet());
        collectionNames.addAll(hashTreeService.loadActiveFileCollections().values());
        collectionNames.removeIf(name -> name == null || name.isBlank());
        return collectionNames;
    }

    private void dropKnowledgeCollections(Set<String> collectionNames) {
        List<String> collectionsBeforeDrop = vectorDatabaseService.listCollections();
        log.warn("完全重建 drop 前 Milvus collections={}", collectionsBeforeDrop);
        for (String collectionName : collectionNames) {
            milvusKnowledgeWriteService.dropCollection(collectionName);
        }
        List<String> collectionsAfterDrop = vectorDatabaseService.listCollections();
        log.warn("完全重建 drop 后 Milvus collections={}", collectionsAfterDrop);
        List<String> remainingKnowledgeCollections = collectionNames.stream()
                .filter(milvusKnowledgeWriteService::hasCollection)
                .toList();
        if (!remainingKnowledgeCollections.isEmpty()) {
            log.error("完全重建 drop 后仍有知识库 collection 残留={}，后续 upsert 可能维度不一致", remainingKnowledgeCollections);
        }
    }

    /**
     * 同步主流程（便于统一捕获异常并打日志）。
     */
    private void doSyncBody(Path rootPath, KnowledgeRepoSyncGuard guard) {
        try {
            doSyncBodyInternal(rootPath, guard);
        } catch (MilvusKnowledgeWriteService.SyncInterruptedException e) {
            log.info("知识库同步被中断: {}", e.getMessage());
        }
    }

    private void doSyncBodyInternal(Path rootPath, KnowledgeRepoSyncGuard guard) {
        progressTracker.setPhase(KnowledgeRepoSyncPhase.SCANNING);
        metadataService.reloadMetadata();
        Map<String, String> previousSnapshot = hashTreeService.loadSnapshot();
        Map<String, String> previousFileCollectionMap = hashTreeService.loadActiveFileCollections();
        Map<String, Long> previousCollectionCounts = hashTreeService.loadActiveCollectionCounts();
        Set<String> previousActiveCollections = new HashSet<>(previousCollectionCounts.keySet());
        Map<String, FileState> latestFileStateMap = buildLatestFileState(rootPath);
        Map<String, String> latestSnapshot = buildLatestSnapshot(latestFileStateMap);
        Map<String, String> latestFileCollectionMap = buildFileCollectionMap(latestFileStateMap);
        Map<String, Long> latestCollectionCounts = toCollectionCounts(latestFileCollectionMap);
        logCollectionSet("同步前", previousActiveCollections);
        logCollectionStats("本轮扫描", latestCollectionCounts);
        hashCache.replaceAll(previousSnapshot);
        KnowledgeRepoHashCache.DiffResult diffResult = hashCache.diff(latestSnapshot);
        progressTracker.registerDiff(
                diffResult.deleted(),
                diffResult.added(),
                diffResult.modified(),
                latestFileCollectionMap,
                previousFileCollectionMap);
        long now = System.currentTimeMillis();
        for (String deletedPath : diffResult.deleted().stream().sorted().toList()) {
            if (!guard.shouldContinue()) {
                log.info("知识库同步在删除文件阶段被中断");
                return;
            }
            progressTracker.markFileInProgress(deletedPath);
            String collection = previousFileCollectionMap.get(deletedPath);
            if (collection != null && !collection.isBlank()) {
                milvusKnowledgeWriteService.deleteBySourceFile(collection, deletedPath);
            } else {
                knowledgeTextChunkService.deleteBySourceFile(deletedPath);
            }
            hashTreeService.markDeleted(Path.of(deletedPath), now);
            progressTracker.markFileDeleted(deletedPath);
        }
        progressTracker.setPhase(KnowledgeRepoSyncPhase.UPSERTING);
        for (String changedPath : orderChangedPaths(rootPath, diffResult.added())) {
            if (!guard.shouldContinue()) {
                log.info("知识库同步在新增文件阶段被中断");
                return;
            }
            processUpsert(rootPath, changedPath, latestFileStateMap.get(changedPath), now, guard);
        }
        for (String changedPath : orderChangedPaths(rootPath, diffResult.modified())) {
            if (!guard.shouldContinue()) {
                log.info("知识库同步在修改文件阶段被中断");
                return;
            }
            Path path = resolveAbsolutePath(rootPath, changedPath);
            FileState latestState = latestFileStateMap.get(changedPath);
            deleteVectorsForModifiedFile(changedPath, previousFileCollectionMap.get(changedPath),
                    latestState == null ? null : latestState.collectionName());
            processUpsert(rootPath, changedPath, latestFileStateMap.get(changedPath), now, guard);
        }
        hashCache.replaceAll(latestSnapshot);
        recycleEmptyCollections(previousActiveCollections, latestCollectionCounts);
        progressTracker.complete();
    }

    private void processUpsert(Path rootPath,
                               String changedPath,
                               FileState fileState,
                               long scanTime,
                               KnowledgeRepoSyncGuard guard) {
        progressTracker.markFileInProgress(changedPath);
        String collection = fileState == null ? null : fileState.collectionName();
        try {
            UpsertOutcome outcome = upsertFile(
                    rootPath,
                    resolveAbsolutePath(rootPath, changedPath),
                    changedPath,
                    fileState,
                    scanTime,
                    guard);
            if (outcome.synced()) {
                progressTracker.markFileSynced(changedPath, collection, outcome.knowledgeCount());
            } else {
                log.warn("知识库文件跳过并等待后续重试: {}", changedPath);
                progressTracker.markFileSkipped(changedPath, collection, outcome.skipReason());
            }
        } catch (MilvusKnowledgeWriteService.SyncInterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            progressTracker.markFileFailed(changedPath, collection, e.getMessage());
            log.error("知识库文件处理失败，已跳过并继续同步: path={}, collection={}", changedPath, collection, e);
        }
    }

    /**
     * 将单个文件解析并 upsert 到 Milvus。
     */
    private UpsertOutcome upsertFile(Path rootPath, Path filePath, String relativePath, FileState fileState, long scanTime,
                                     KnowledgeRepoSyncGuard guard) {
        if (!guard.shouldContinue()) {
            log.info("知识库文件 upsert 被中断: {}", relativePath);
            return UpsertOutcome.skipped("同步被中断");
        }
        if (fileState == null) {
            return UpsertOutcome.skipped("文件状态不可用");
        }
        try {
            String documentContent = readKnowledgeDocument(filePath, relativePath);
            int minHeadingLevel = metadataService.resolveMinHeadingLevel(filePath);
            boolean filenameAsTitle = metadataService.resolveFilenameAsTitle(filePath);
            String filenameTitle = resolveFilenameTitle(relativePath);
            List<KnowledgeTextChunkParser.TextChunk> chunks = knowledgeTextChunkParser.parse(
                    relativePath, documentContent, minHeadingLevel, filenameAsTitle, filenameTitle);
            chunks = knowledgeMarkdownImageRewriter.rewriteChunks(relativePath, chunks);
            if (chunks.isEmpty()) {
                log.warn("知识库文档未产生分片（Markdown 需存在 #/##/### 标题，AsciiDoc 需存在 =/==/=== 标题；或启用 filename_as_title 且全文非空）: path={}, collection={}, minHeadingLevel={}, filenameAsTitle={}",
                        relativePath, fileState.collectionName(), minHeadingLevel, filenameAsTitle);
            }
            int upsertedCount = milvusKnowledgeWriteService.upsertTextChunks(
                    chunks, relativePath, fileState.fileSha256(), fileState.collectionName(), fileState.partitionNames(), guard);
            long fileSizeBytes = Files.size(filePath);
            hashTreeService.upsertActive(
                    Path.of(relativePath),
                    fileState.fileSha256(),
                    fileState.infoJsonHash(),
                    fileState.collectionName(),
                    fileState.partitionNames(),
                    upsertedCount,
                    fileSizeBytes,
                    scanTime
            );
            return UpsertOutcome.synced(upsertedCount);
        } catch (MilvusKnowledgeWriteService.SyncInterruptedException e) {
            log.info("知识库文件 upsert 被中断: {}", relativePath);
            throw e;
        } catch (MilvusKnowledgeWriteService.EmbeddingUnavailableException e) {
            log.warn("知识库向量化降级: sourceFile={}, reason={}", relativePath, e.getMessage());
            return UpsertOutcome.skipped(e.getMessage());
        } catch (IOException e) {
            throw new IllegalStateException("读取知识库文档失败: " + filePath, e);
        }
    }

    /**
     * 读取知识库文档内容；遇非法 UTF-8 字节时用替换字符继续解码。
     */
    private String readKnowledgeDocument(Path filePath, String relativePath) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        try {
            return newUtf8Decoder(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            log.warn("知识库文档包含非法 UTF-8 字节，已使用替换字符继续入库: path={}", relativePath);
            return decodeWithReplacement(bytes);
        }
    }

    /**
     * 以替换字符方式解码非法 UTF-8 字节。
     */
    private String decodeWithReplacement(byte[] bytes) throws IOException {
        try {
            CharBuffer decoded = newUtf8Decoder(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            throw new IOException("替换非法 UTF-8 字节后仍无法读取知识库文档", e);
        }
    }

    /**
     * 创建指定错误处理策略的 UTF-8 解码器。
     */
    private CharsetDecoder newUtf8Decoder(CodingErrorAction codingErrorAction) {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(codingErrorAction)
                .onUnmappableCharacter(codingErrorAction);
    }

    /**
     * 将相对路径中的文档文件名转为标题，去掉支持的文档后缀。
     */
    private String resolveFilenameTitle(String relativePath) {
        String filename = Path.of(relativePath).getFileName().toString();
        for (String extension : List.of(".asciidoc", ".adoc", ".md")) {
            if (filename.length() > extension.length()
                    && filename.regionMatches(true, filename.length() - extension.length(), extension, 0, extension.length())) {
                return filename.substring(0, filename.length() - extension.length());
            }
        }
        return filename;
    }

    /**
     * 按文件大小从小到大处理，避免超大文件阻塞其它文件的状态落库。
     */
    private List<String> orderChangedPaths(Path rootPath, Set<String> changedPaths) {
        return changedPaths.stream()
                .sorted((left, right) -> {
                    int sizeCompare = Long.compare(fileSizeForOrder(rootPath, left), fileSizeForOrder(rootPath, right));
                    return sizeCompare == 0 ? left.compareTo(right) : sizeCompare;
                })
                .toList();
    }

    /**
     * 读取文件大小用于排序，异常时放到最后处理。
     */
    private long fileSizeForOrder(Path rootPath, String relativePath) {
        try {
            return Files.size(resolveAbsolutePath(rootPath, relativePath));
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 从文件状态构建差异快照。
     */
    private Map<String, String> buildLatestSnapshot(Map<String, FileState> latestFileStateMap) {
        Map<String, String> snapshot = new HashMap<>();
        for (Map.Entry<String, FileState> entry : latestFileStateMap.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().diffHash());
        }
        return snapshot;
    }

    /**
     * 基于文件状态构建文件到 collection 的映射。
     */
    private Map<String, String> buildFileCollectionMap(Map<String, FileState> latestFileStateMap) {
        Map<String, String> mapping = new HashMap<>();
        for (Map.Entry<String, FileState> entry : latestFileStateMap.entrySet()) {
            mapping.put(entry.getKey(), entry.getValue().collectionName());
        }
        return mapping;
    }

    /**
     * 将文件映射转换为 collection 计数。
     */
    private Map<String, Long> toCollectionCounts(Map<String, String> fileCollectionMap) {
        Map<String, Long> counts = new HashMap<>();
        for (String collectionName : fileCollectionMap.values()) {
            if (collectionName == null || collectionName.isBlank()) {
                continue;
            }
            counts.merge(collectionName, 1L, Long::sum);
        }
        return counts;
    }

    /**
     * 回收上一轮存在但当前已空的 collection。
     */
    private void recycleEmptyCollections(Set<String> previousActiveCollections, Map<String, Long> latestCollectionCounts) {
        for (String collectionName : previousActiveCollections) {
            long latestCount = latestCollectionCounts.getOrDefault(collectionName, 0L);
            if (latestCount > 0) {
                continue;
            }
            if (!milvusKnowledgeWriteService.hasCollection(collectionName)) {
                continue;
            }
            milvusKnowledgeWriteService.dropCollection(collectionName);
            log.info("回收空 collection: {}, 当前文件数: {}", collectionName, latestCount);
        }
        logCollectionStats("同步后", latestCollectionCounts);
    }

    /**
     * 打印 collection 级文件统计。
     */
    private void logCollectionStats(String phase, Map<String, Long> collectionCounts) {
        log.info("{} collection 文件统计: {}", phase, collectionCounts);
    }

    /**
     * 打印 collection 集合。
     */
    private void logCollectionSet(String phase, Set<String> collections) {
        log.info("{} collection 集合: {}", phase, collections);
    }

    /**
     * 扫描根目录构建最新知识库文档哈希快照。
     */
    private Map<String, FileState> buildLatestFileState(Path rootPath) {
        Map<String, FileState> snapshot = new HashMap<>();
        if (!metadataService.hasMetadata()) {
            log.info("知识库目录未找到 info.json，本轮按空知识库处理: {}", rootPath.toAbsolutePath().normalize());
            return snapshot;
        }
        // 与 KnowledgeRepoMetadataService 扫描一致，跟随符号链接以识别外链的知识库目录
        try (Stream<Path> pathStream = Files.walk(rootPath, Integer.MAX_VALUE, FileVisitOption.FOLLOW_LINKS)) {
            pathStream.filter(Files::isRegularFile)
                    .filter(this::isSupportedKnowledgeDocument)
                    .forEach(path -> {
                        String relativePath = toRepoRelativePath(rootPath, path);
                        String fileSha256 = calculateSha256(path);
                        String infoJsonHash = metadataService.resolveInfoJsonHash(path);
                        String collectionName = metadataService.resolveCollection(path);
                        List<String> partitionNames = metadataService.resolvePartitionNames(path);
                        String diffHash = KnowledgeRepoDiffHash.build(fileSha256, infoJsonHash, collectionName);
                        snapshot.put(relativePath, new FileState(fileSha256, infoJsonHash, collectionName, partitionNames, diffHash));
                    });
        } catch (IOException e) {
            throw new IllegalStateException("扫描知识库目录失败", e);
        }
        return snapshot;
    }

    /**
     * 判断是否为支持进入向量库的知识库文档。
     */
    private boolean isSupportedKnowledgeDocument(Path path) {
        String lowerFilename = path.getFileName().toString().toLowerCase();
        return lowerFilename.endsWith(".md")
                || lowerFilename.endsWith(".adoc")
                || lowerFilename.endsWith(".asciidoc");
    }

    /**
     * 计算文件 sha256。
     */
    private String calculateSha256(Path path) {
        try {
            return HashUtil.getMessageDigest(Files.readAllBytes(path), HashUtil.MdAlgorithm.SHA256);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("计算文件哈希失败: " + path, e);
        }
    }

    /**
     * 修改文件时：在 DB 记录的旧 collection 与当前解析的新 collection 上分别按 source_file 删除，避免共用 collection 迁名后旧库残留。
     */
    private void deleteVectorsForModifiedFile(String relativePath, String previousCollection, String nextCollection) {
        Set<String> collections = new LinkedHashSet<>();
        if (previousCollection != null && !previousCollection.isBlank()) {
            collections.add(previousCollection.trim());
        }
        if (nextCollection != null && !nextCollection.isBlank()) {
            collections.add(nextCollection.trim());
        }
        for (String col : collections) {
            milvusKnowledgeWriteService.deleteBySourceFile(col, relativePath);
        }
    }

    /**
     * 将绝对文件路径转换为 root-path 下的相对路径。
     */
    private String toRepoRelativePath(Path rootPath, Path filePath) {
        return rootPath.toAbsolutePath().normalize().relativize(filePath.toAbsolutePath().normalize()).toString().replace("\\", "/");
    }

    /**
     * 将仓内相对路径恢复为绝对路径。
     */
    private Path resolveAbsolutePath(Path rootPath, String relativePath) {
        return rootPath.toAbsolutePath().normalize().resolve(relativePath).normalize();
    }

    /**
     * 启动目录变更监听；文件变更时调用 {@code onFileChange}（通常为协调器增量同步入口）。
     */
    public void startWatch(Consumer<String> onFileChange) {
        this.watchSyncTrigger = onFileChange;
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null || !Files.exists(rootPath)) {
            return;
        }
        if (watchService != null) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            watchedDirectories.clear();
            registerAllDirectories(rootPath);
            Thread.startVirtualThread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key;
                    try {
                        key = watchService.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    Path watchedDir = (Path) key.watchable();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }
                        Path changedPath = watchedDir.resolve((Path) event.context());
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changedPath)) {
                            registerAllDirectoriesSafely(changedPath);
                        }
                        scheduleWatchSync();
                    }
                    if (!key.reset()) {
                        watchedDirectories.remove(watchedDir.toAbsolutePath().normalize());
                    }
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("启动目录监听失败", e);
        }
    }

    private void scheduleWatchSync() {
        lastWatchEventTime.set(System.currentTimeMillis());
        if (!watchSyncScheduled.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    long quietMillis = System.currentTimeMillis() - lastWatchEventTime.get();
                    long remainingMillis = WATCH_SYNC_DEBOUNCE_MILLIS - quietMillis;
                    if (remainingMillis <= 0) {
                        break;
                    }
                    TimeUnit.MILLISECONDS.sleep(Math.min(remainingMillis, WATCH_SYNC_DEBOUNCE_MILLIS));
                }
                Consumer<String> trigger = watchSyncTrigger;
                if (trigger != null && !Thread.currentThread().isInterrupted()) {
                    trigger.accept("watch");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                watchSyncScheduled.set(false);
                if (System.currentTimeMillis() - lastWatchEventTime.get() < WATCH_SYNC_DEBOUNCE_MILLIS) {
                    scheduleWatchSync();
                }
            }
        });
    }

    /**
     * 递归注册知识库目录监听；info.json 可位于子目录，必须监听整棵目录树。
     */
    private void registerAllDirectories(Path rootPath) throws IOException {
        try (Stream<Path> stream = Files.walk(rootPath, Integer.MAX_VALUE, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(Files::isDirectory).forEach(this::registerDirectorySafely);
        }
    }

    /**
     * 安全注册新增目录，避免监听线程因单个目录失败退出。
     */
    private void registerAllDirectoriesSafely(Path rootPath) {
        try {
            registerAllDirectories(rootPath);
        } catch (IOException e) {
            log.warn("注册知识库新增目录监听失败: {}", rootPath, e);
        }
    }

    /**
     * 注册单个目录监听，重复注册会被跳过。
     */
    private void registerDirectorySafely(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        if (!watchedDirectories.add(normalized)) {
            return;
        }
        try {
            normalized.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
            watchedDirectories.remove(normalized);
            throw new IllegalStateException("注册知识库目录监听失败: " + normalized, e);
        }
    }

    /**
     * 释放目录监听资源。
     */
    public void shutdownWatch() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                log.warn("关闭 watchService 失败");
            }
            watchService = null;
        }
        watchedDirectories.clear();
        watchSyncScheduled.set(false);
    }
}

package io.github.jerryt92.j2agent.service.llm.agent.builtin.knowledgeqa;

import io.github.jerryt92.j2agent.service.llm.agent.builtin.AgentToolContextSupport;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.rag.RagSourcePublicationService;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeCollectionSelection;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeMarkdownImageRewriter;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 通用知识库问答助手专用 Markdown grep/read 工具。
 */
@Slf4j
class KnowledgeQaGrepTool {

    private static final int MAX_FILES = 500;
    private static final int MAX_MATCHES = 40;
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final int CONTEXT_LINES = 2;
    private static final int FILENAME_PREVIEW_LINES = 30;
    private static final int DEFAULT_READ_MAX_CHARS = 32_000;
    private static final int MIN_PATTERN_LENGTH_FOR_TOKEN_SPLIT = 4;
    private static final String MD_SUFFIX = ".md";

    private final KnowledgeRepoMetadataService metadataService;
    private final KnowledgeMarkdownImageRewriter imageRewriter;

    KnowledgeQaGrepTool(KnowledgeRepoMetadataService metadataService,
                        KnowledgeMarkdownImageRewriter imageRewriter) {
        this.metadataService = metadataService;
        this.imageRewriter = imageRewriter;
    }

    @Tool(name = "grep_knowledge_repo", description = "在本轮已选择的知识库 Markdown 中按关键词检索：匹配 .md 正文行或文件名，返回命中片段。")
    public String grepKnowledgeRepo(
            @ToolParam(description = "检索关键词或短语，对 .md 文件正文行与文件名做包含匹配（忽略大小写）") String pattern,
            @ToolParam(description = "可选，在已选择知识库目录下再收窄的相对子路径") String relativeSubDir,
            ToolContext toolContext
    ) {
        log.info("knowledge_qa grep_knowledge_repo 开始: pattern={}, relativeSubDir={}", pattern, relativeSubDir);
        if (StringUtils.isBlank(pattern)) {
            return "检索关键词不能为空，请提供 pattern。";
        }
        Path normalizedRoot = resolveRepoRoot();
        if (normalizedRoot == null) {
            return "知识库根目录未配置或不存在，无法执行 grep。";
        }
        Set<KnowledgeCollectionSelection.Parsed> selectedCollections = selectedCollections(toolContext);
        if (selectedCollections.isEmpty()) {
            return "本轮未选择知识库，无法执行 grep。请先选择知识库后再检索。";
        }

        String trimmedPattern = pattern.trim();
        String patternLower = trimmedPattern.toLowerCase(Locale.ROOT);
        List<String> fallbackTokens = splitFallbackTokens(trimmedPattern);
        long startMs = System.currentTimeMillis();
        List<String> blocks = new ArrayList<>();
        LinkedHashSet<String> matchedSourceFiles = new LinkedHashSet<>();
        int fileCount = 0;
        int matchCount = 0;
        int skippedLargeFiles = 0;

        try {
            List<Path> mdFiles = collectSelectedMarkdownFiles(normalizedRoot, selectedCollections, relativeSubDir);
            List<Path> filenameHits = new ArrayList<>();
            List<Path> otherFiles = new ArrayList<>();
            for (Path file : mdFiles) {
                if (filenameMatches(file.getFileName().toString(), patternLower)) {
                    filenameHits.add(file);
                } else {
                    otherFiles.add(file);
                }
            }
            List<Path> scanOrder = new ArrayList<>(filenameHits);
            scanOrder.addAll(otherFiles);

            for (Path file : scanOrder) {
                if (fileCount >= MAX_FILES || matchCount >= MAX_MATCHES) {
                    break;
                }
                fileCount++;
                long size = Files.size(file);
                if (size > MAX_FILE_BYTES) {
                    skippedLargeFiles++;
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                String relativeFile = normalizedRoot.relativize(file).toString().replace('\\', '/');
                if (filenameHits.contains(file)) {
                    matchCount++;
                    matchedSourceFiles.add(relativeFile);
                    blocks.add(formatFilenameMatchBlock(relativeFile, lines));
                    if (matchCount >= MAX_MATCHES) {
                        break;
                    }
                }
                for (int lineIndex : findContentHitLines(lines, patternLower, fallbackTokens)) {
                    if (matchCount >= MAX_MATCHES) {
                        break;
                    }
                    matchCount++;
                    matchedSourceFiles.add(relativeFile);
                    blocks.add(formatMatchBlock(relativeFile, lines, lineIndex));
                }
            }
        } catch (IOException e) {
            log.warn("knowledge_qa grep_knowledge_repo 扫描异常: pattern={}", pattern, e);
            return "知识库检索失败: " + e.getMessage();
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        if (blocks.isEmpty()) {
            log.info("knowledge_qa grep_knowledge_repo 无命中: pattern={}, collections={}, scannedMdFiles={}, " +
                            "skippedLargeFiles={}, elapsedMs={}",
                    pattern, selectedCollections, fileCount, skippedLargeFiles, elapsedMs);
            return "行级检索未命中关键词「" + trimmedPattern + "」（已在本轮选择的知识库中扫描 "
                    + fileCount + " 个 Markdown 文件）。请结合当前参考上下文作答；"
                    + "如果参考上下文含【来源文件】路径，可调用 read_knowledge_repo_file 读取完整原文；"
                    + "也可换更短关键词重试 grep。";
        }
        log.info("knowledge_qa grep_knowledge_repo 完成: pattern={}, collections={}, scannedMdFiles={}, " +
                        "matchCount={}, skippedLargeFiles={}, hitLimit={}, fileLimit={}, elapsedMs={}",
                pattern, selectedCollections, fileCount, matchCount, skippedLargeFiles,
                matchCount >= MAX_MATCHES, fileCount >= MAX_FILES, elapsedMs);
        publishMatchedSourceFiles(toolContext, matchedSourceFiles);
        return "共 " + matchCount + " 处命中（最多展示 " + MAX_MATCHES + " 处，扫描文件上限 "
                + MAX_FILES + "）：\n\n" + String.join("\n\n---\n\n", blocks);
    }

    protected void publishMatchedSourceFiles(ToolContext toolContext, Set<String> matchedSourceFiles) {
        if (matchedSourceFiles == null || matchedSourceFiles.isEmpty()) {
            return;
        }
        String conversationId = AgentToolContextSupport.parentConversationId(toolContext);
        if (StringUtils.isBlank(conversationId)) {
            log.debug("knowledge_qa grep 跳过来源发布: conversationId 为空, files={}", matchedSourceFiles.size());
            return;
        }
        String agentId = AgentToolContextSupport.stringKey(toolContext, AgentRunnableContextKeys.CONTEXT_KEY_AGENT_ID);
        List<Document> documents = matchedSourceFiles.stream()
                .map(sourceFile -> Document.builder()
                        .text("")
                        .metadata(Map.of("sourceFile", sourceFile))
                        .build())
                .toList();
        RagSourcePublicationService.tryPublishFromRetriever(conversationId, agentId, documents);
    }

    @Tool(name = "read_knowledge_repo_file", description = "按相对知识库根的路径读取本轮已选择知识库内的 Markdown 原文。")
    public String readKnowledgeRepoFile(
            @ToolParam(description = "相对知识库根的文件路径，如 knowledge_base/common/文档.md（与【来源文件】一致）") String relativeFilePath,
            @ToolParam(description = "可选，返回的最大字符数，默认 32000") Integer maxChars,
            ToolContext toolContext
    ) {
        log.info("knowledge_qa read_knowledge_repo_file 开始: relativeFilePath={}, maxChars={}", relativeFilePath, maxChars);
        if (StringUtils.isBlank(relativeFilePath)) {
            return "文件路径不能为空，请提供 relativeFilePath。";
        }
        Path normalizedRoot = resolveRepoRoot();
        if (normalizedRoot == null) {
            return "知识库根目录未配置或不存在，无法读取文件。";
        }
        Set<KnowledgeCollectionSelection.Parsed> selectedCollections = selectedCollections(toolContext);
        if (selectedCollections.isEmpty()) {
            return "本轮未选择知识库，无法读取文件。请先选择知识库后再读取。";
        }
        Path resolvedFile = resolveReadableFile(normalizedRoot, relativeFilePath);
        if (resolvedFile == null || !isInSelectedCollection(resolvedFile, selectedCollections)) {
            return "文件路径无效、越界，或不属于本轮选择的知识库。";
        }
        if (!Files.exists(resolvedFile) || !Files.isRegularFile(resolvedFile)) {
            return "文件不存在: " + normalizedRoot.relativize(resolvedFile).toString().replace('\\', '/');
        }
        try {
            long size = Files.size(resolvedFile);
            if (size > MAX_FILE_BYTES) {
                return "文件过大（" + size + " 字节），超过读取上限 " + MAX_FILE_BYTES + " 字节。";
            }
            String content = Files.readString(resolvedFile, StandardCharsets.UTF_8);
            int limit = maxChars == null || maxChars <= 0 ? DEFAULT_READ_MAX_CHARS : maxChars;
            String relative = normalizedRoot.relativize(resolvedFile).toString().replace('\\', '/');
            content = rewriteOutputMarkdown(relative, content);
            if (content.length() <= limit) {
                return "**文件**: `" + relative + "`\n\n```markdown\n" + content + "\n```";
            }
            return "**文件**: `" + relative + "`（已截断，共 " + content.length() + " 字符，展示前 "
                    + limit + " 字符）\n\n```markdown\n" + content.substring(0, limit) + "\n```";
        } catch (IOException e) {
            log.warn("knowledge_qa read_knowledge_repo_file 读取失败: path={}", resolvedFile, e);
            return "读取文件失败: " + e.getMessage();
        }
    }

    private Path resolveRepoRoot() {
        Path repoRoot = metadataService.getRepoRootPath();
        if (repoRoot == null || !Files.exists(repoRoot)) {
            return null;
        }
        return repoRoot.toAbsolutePath().normalize();
    }

    private List<Path> collectSelectedMarkdownFiles(Path normalizedRoot,
                                                    Set<KnowledgeCollectionSelection.Parsed> selectedCollections,
                                                    String relativeSubDir) throws IOException {
        if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            return List.of();
        }
        String normalizedSubDir = normalizeRelativeSubDir(relativeSubDir);
        try (Stream<Path> walk = Files.walk(normalizedRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(MD_SUFFIX))
                    .filter(path -> isInSelectedCollection(path, selectedCollections))
                    .filter(path -> matchesRelativeSubDir(normalizedRoot, path, normalizedSubDir))
                    .sorted(Comparator.comparing(path -> normalizedRoot.relativize(path).toString()))
                    .toList();
        }
    }

    private String normalizeRelativeSubDir(String relativeSubDir) {
        if (StringUtils.isBlank(relativeSubDir)) {
            return null;
        }
        String sub = relativeSubDir.replace('\\', '/').trim();
        if (sub.startsWith("/")) {
            sub = sub.substring(1);
        }
        if (sub.contains("..")) {
            return "__invalid__";
        }
        return StringUtils.stripEnd(sub, "/");
    }

    private boolean matchesRelativeSubDir(Path normalizedRoot, Path file, String normalizedSubDir) {
        if (normalizedSubDir == null) {
            return true;
        }
        if ("__invalid__".equals(normalizedSubDir)) {
            return false;
        }
        String relative = normalizedRoot.relativize(file).toString().replace('\\', '/');
        return relative.startsWith(normalizedSubDir + "/") || relative.contains("/" + normalizedSubDir + "/");
    }

    private Path resolveReadableFile(Path normalizedRoot, String relativeFilePath) {
        String normalized = relativeFilePath.replace('\\', '/').trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        Path resolved = normalizedRoot.resolve(normalized).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            return null;
        }
        if (!resolved.getFileName().toString().endsWith(MD_SUFFIX)) {
            return null;
        }
        return resolved;
    }

    private boolean isInSelectedCollection(Path file, Set<KnowledgeCollectionSelection.Parsed> selectedCollections) {
        try {
            String collection = metadataService.resolveCollection(file);
            String sourceFile = resolveRepoRoot().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
            return selectedCollections.stream()
                    .anyMatch(selection -> selection.collection().equals(collection)
                            && KnowledgeCollectionSelection.matchesSourceFile(selection, sourceFile));
        } catch (Exception ex) {
            log.debug("knowledge_qa grep 跳过无 collection 元数据文件: path={}, reason={}", file, ex.getMessage());
            return false;
        }
    }

    private Set<KnowledgeCollectionSelection.Parsed> selectedCollections(ToolContext toolContext) {
        Object raw = AgentToolContextSupport.contextMap(toolContext)
                .get(AgentRunnableContextKeys.CONTEXT_KEY_KNOWLEDGE_COLLECTIONS);
        LinkedHashSet<KnowledgeCollectionSelection.Parsed> selected = new LinkedHashSet<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                addCollectionValue(selected, item);
            }
        } else {
            addCollectionValue(selected, raw);
        }
        return selected;
    }

    private void addCollectionValue(Set<KnowledgeCollectionSelection.Parsed> target, Object raw) {
        if (raw == null) {
            return;
        }
        KnowledgeCollectionSelection.Parsed parsed = KnowledgeCollectionSelection.parse(raw.toString());
        if (parsed != null) {
            target.add(parsed);
        }
    }

    private List<String> splitFallbackTokens(String pattern) {
        if (countCjkChars(pattern) < MIN_PATTERN_LENGTH_FOR_TOKEN_SPLIT) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (int i = 0; i < pattern.length() - 1; i++) {
            if (isCjkChar(pattern.charAt(i)) && isCjkChar(pattern.charAt(i + 1))) {
                tokens.add(pattern.substring(i, i + 2).toLowerCase(Locale.ROOT));
            }
        }
        return tokens.isEmpty() ? List.of() : List.copyOf(tokens);
    }

    private boolean filenameMatches(String fileName, String patternLower) {
        return fileName.toLowerCase(Locale.ROOT).contains(patternLower);
    }

    private List<Integer> findContentHitLines(List<String> lines, String patternLower,
                                              List<String> fallbackTokens) {
        List<Integer> primaryHits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase(Locale.ROOT).contains(patternLower)) {
                primaryHits.add(i);
            }
        }
        if (!primaryHits.isEmpty() || fallbackTokens.isEmpty()) {
            return primaryHits;
        }
        List<Integer> tokenHits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String lineLower = lines.get(i).toLowerCase(Locale.ROOT);
            for (String token : fallbackTokens) {
                if (lineLower.contains(token)) {
                    tokenHits.add(i);
                    break;
                }
            }
        }
        return tokenHits;
    }

    private static int countCjkChars(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (isCjkChar(value.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isCjkChar(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    private String rewriteOutputMarkdown(String relativeFile, String markdown) {
        if (imageRewriter == null || StringUtils.isBlank(markdown)) {
            return markdown;
        }
        return imageRewriter.rewriteTextChunkImages(relativeFile, markdown);
    }

    private String formatFilenameMatchBlock(String relativeFile, List<String> lines) {
        int to = Math.min(lines.size(), FILENAME_PREVIEW_LINES);
        String rewrittenPreview = rewriteOutputMarkdown(relativeFile, String.join("\n", lines.subList(0, to)));
        String[] rewrittenLines = rewrittenPreview.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("**文件**: `").append(relativeFile).append("`\n");
        sb.append("**命中方式**: 文件名匹配\n");
        sb.append("**预览** (前 ").append(FILENAME_PREVIEW_LINES).append(" 行):\n```\n");
        for (int i = 0; i < rewrittenLines.length; i++) {
            sb.append(i + 1).append(": ").append(rewrittenLines[i]).append('\n');
        }
        sb.append("```\n");
        sb.append("可调用 read_knowledge_repo_file 读取完整原文。");
        return sb.toString();
    }

    private String formatMatchBlock(String relativeFile, List<String> lines, int matchLineIndex) {
        int from = Math.max(0, matchLineIndex - CONTEXT_LINES);
        int to = Math.min(lines.size() - 1, matchLineIndex + CONTEXT_LINES);
        String rewrittenContext = rewriteOutputMarkdown(relativeFile, String.join("\n", lines.subList(from, to + 1)));
        String[] rewrittenLines = rewrittenContext.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("**文件**: `").append(relativeFile).append("`\n");
        sb.append("**命中行**: ").append(matchLineIndex + 1).append("\n```\n");
        for (int i = 0; i < rewrittenLines.length; i++) {
            int lineNum = from + i + 1;
            String prefix = (lineNum - 1 == matchLineIndex) ? ">> " : "   ";
            sb.append(prefix).append(lineNum).append(": ").append(rewrittenLines[i]).append('\n');
        }
        sb.append("```");
        return sb.toString();
    }
}

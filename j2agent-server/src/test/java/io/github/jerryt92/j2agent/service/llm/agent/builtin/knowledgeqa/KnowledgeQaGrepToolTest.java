package io.github.jerryt92.j2agent.service.llm.agent.builtin.knowledgeqa;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeMarkdownImageRewriter;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repository.KnowledgeRepositoryAutoRegistrar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import java.io.IOException;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识库问答专用 grep/read 工具范围约束测试。
 */
class KnowledgeQaGrepToolTest {

    @TempDir
    Path tempDir;

    private CapturingKnowledgeQaGrepTool tool;

    @BeforeEach
    void setUp() throws IOException {
        writeKnowledgeRepo("knowledge_base", "knowledge_base", "common/guide.md",
                "# 通用文档\n共享输出设备需完成身份验证后方可使用。\n");
        writeKnowledgeRepo("intelligent_report_kb", "intelligent_report_kb", "report/report.md",
                "# 报表文档\n智能报表支持告警排行统计。\n");

        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        properties.setRootPath(tempDir.toString());
        KnowledgeRepositoryMapper mapper = mock(KnowledgeRepositoryMapper.class);
        when(mapper.selectEnabledAll()).thenReturn(List.of(
                repository("knowledge_base", "knowledge_base"),
                repository("intelligent_report_kb", "intelligent_report_kb")));
        KnowledgeRepoMetadataService metadataService = new KnowledgeRepoMetadataService(
                properties, mapper, new KnowledgeRepositoryAutoRegistrar(mapper, properties));
        metadataService.init();
        tool = new CapturingKnowledgeQaGrepTool(metadataService, new KnowledgeMarkdownImageRewriter());
    }

    @Test
    void grep_matchesOnlySelectedCollection() {
        String result = tool.grepKnowledgeRepo("智能报表", "", context("knowledge_base"));

        assertTrue(result.contains("行级检索未命中"));
        assertFalse(result.contains("report.md"));
        assertFalse(result.contains("智能报表支持告警排行统计"));
    }

    @Test
    void grep_matchesMultipleSelectedCollections() {
        String result = tool.grepKnowledgeRepo("智能报表", "", context("knowledge_base", "intelligent_report_kb"));

        assertTrue(result.contains("命中"));
        assertTrue(result.contains("intelligent_report_kb/report/report.md"));
        assertTrue(result.contains("智能报表支持告警排行统计"));
        assertTrue(tool.publishedSourceFiles.contains("intelligent_report_kb/report/report.md"));
    }

    @Test
    void grep_supportsRelativeSubDirInsideSelectedCollection() {
        String result = tool.grepKnowledgeRepo("共享输出设备", "common", context("knowledge_base"));

        assertTrue(result.contains("命中"));
        assertTrue(result.contains("knowledge_base/common/guide.md"));
        assertTrue(tool.publishedSourceFiles.contains("knowledge_base/common/guide.md"));
    }

    @Test
    void grep_publishesSameFileOnlyOnceWhenMultipleLinesHit() throws IOException {
        writeKnowledgeRepo("knowledge_base", "knowledge_base", "common/multi.md",
                "甲乙丙丁第一行。\n甲乙丙丁第二行。\n");

        String result = tool.grepKnowledgeRepo("甲乙丙丁", "common", context("knowledge_base"));

        assertTrue(result.contains("命中"));
        assertTrue(tool.publishedSourceFiles.contains("knowledge_base/common/multi.md"));
        assertTrue(tool.publishedSourceFiles.size() == 1);
    }

    @Test
    void grep_doesNotPublishWhenNoMatch() {
        String result = tool.grepKnowledgeRepo("zzzz-not-found-term", "", context("knowledge_base"));

        assertTrue(result.contains("行级检索未命中"));
        assertTrue(tool.publishedSourceFiles.isEmpty());
    }

    @Test
    void read_rejectsFileOutsideSelectedCollection() {
        String result = tool.readKnowledgeRepoFile(
                "intelligent_report_kb/report/report.md",
                null,
                context("knowledge_base"));

        assertTrue(result.contains("不属于本轮选择的知识库"));
    }

    @Test
    void read_readsSelectedCollectionMarkdown() {
        String result = tool.readKnowledgeRepoFile("knowledge_base/common/guide.md", null, context("knowledge_base"));

        assertTrue(result.contains("knowledge_base/common/guide.md"));
        assertTrue(result.contains("共享输出设备需完成身份验证后方可使用"));
    }

    @Test
    void grep_withoutSelectedCollectionsDoesNotScanRepo() {
        String result = tool.grepKnowledgeRepo("共享输出设备", "", new ToolContext(Map.of()));

        assertTrue(result.contains("本轮未选择知识库"));
        assertFalse(result.contains("共享输出设备需完成身份验证后方可使用"));
    }

    private void writeKnowledgeRepo(String dir, String collection, String relativeFile, String content) throws IOException {
        Path repoDir = tempDir.resolve(dir);
        Files.createDirectories(repoDir);
        Path md = repoDir.resolve(relativeFile);
        Files.createDirectories(md.getParent());
        Files.writeString(md, content);
    }

    private KnowledgeRepositoryPo repository(String repoCode, String collection) {
        KnowledgeRepositoryPo po = new KnowledgeRepositoryPo();
        po.setRepoCode(repoCode);
        po.setEnabled(true);
        po.setMetadataConfig("{\"collectionName\":\"" + collection
                + "\",\"partitionNames\":[],\"minHeadingLevel\":2,\"filenameAsTitle\":true}");
        return po;
    }

    private ToolContext context(String... collections) {
        return new ToolContext(Map.of(
                AgentRunnableContextKeys.CONTEXT_KEY_KNOWLEDGE_COLLECTIONS,
                List.of(collections)));
    }

    private static final class CapturingKnowledgeQaGrepTool extends KnowledgeQaGrepTool {

        final List<String> publishedSourceFiles = new ArrayList<>();

        CapturingKnowledgeQaGrepTool(KnowledgeRepoMetadataService metadataService,
                                     KnowledgeMarkdownImageRewriter imageRewriter) {
            super(metadataService, imageRewriter);
        }

        @Override
        protected void publishMatchedSourceFiles(ToolContext toolContext, Set<String> matchedSourceFiles) {
            publishedSourceFiles.addAll(matchedSourceFiles);
        }
    }
}

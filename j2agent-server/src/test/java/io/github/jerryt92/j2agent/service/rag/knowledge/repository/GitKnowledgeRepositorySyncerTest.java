package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitKnowledgeRepositorySyncerTest {
    @TempDir
    Path tempDir;

    @Test
    void syncHardResetsAndCleansLocalRepository() throws Exception {
        System.setProperty("user.home", tempDir.resolve("home").toString());
        System.setProperty("XDG_CONFIG_HOME", tempDir.resolve("xdg").toString());
        Path remote = tempDir.resolve("remote");
        Path local = tempDir.resolve("knowledge-repo").resolve("repo");
        Files.createDirectories(remote);
        try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
            Files.writeString(remote.resolve("doc.md"), "# Title\n\n### A\n\nBody");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("Tester", "test@example.com").call();
        }

        KnowledgeRepositoryPo po = repository(remote);
        GitKnowledgeRepositorySyncer syncer = new GitKnowledgeRepositorySyncer();
        KnowledgeRepositorySyncResult first = syncer.sync(po, new KnowledgeRepositoryDtos.CredentialConfig(), local);

        assertTrue(Files.exists(local.resolve("doc.md")));
        Files.writeString(local.resolve("extra.md"), "local only", StandardCharsets.UTF_8);
        try (Git git = Git.open(remote.toFile())) {
            Files.delete(remote.resolve("doc.md"));
            Files.writeString(remote.resolve("next.md"), "# Title\n\n### B\n\nNext", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.rm().addFilepattern("doc.md").call();
            git.commit().setMessage("replace doc").setAuthor("Tester", "test@example.com").call();
        }

        KnowledgeRepositorySyncResult second = syncer.sync(po, new KnowledgeRepositoryDtos.CredentialConfig(), local);

        assertNotEquals(first.revision(), second.revision());
        assertFalse(Files.exists(local.resolve("extra.md")));
        assertFalse(Files.exists(local.resolve("doc.md")));
        assertTrue(Files.exists(local.resolve("next.md")));
    }

    private KnowledgeRepositoryPo repository(Path remote) {
        KnowledgeRepositoryPo po = new KnowledgeRepositoryPo();
        po.setRepoCode("repo");
        po.setRemoteUrl(remote.toUri().toString());
        return po;
    }
}

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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void syncWithSubPathsChecksOutOnlyConfiguredPaths() throws Exception {
        System.setProperty("user.home", tempDir.resolve("home").toString());
        System.setProperty("XDG_CONFIG_HOME", tempDir.resolve("xdg").toString());
        Path remote = tempDir.resolve("remote-sparse");
        Path local = tempDir.resolve("knowledge-repo").resolve("repo-sparse");
        Files.createDirectories(remote.resolve("docs"));
        Files.createDirectories(remote.resolve("src"));
        try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
            Files.writeString(remote.resolve("docs").resolve("guide.md"), "# Guide\n", StandardCharsets.UTF_8);
            Files.writeString(remote.resolve("src").resolve("app.java"), "class App {}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("Tester", "test@example.com").call();
        }

        KnowledgeRepositoryPo po = repository(remote);
        po.setProtocolConfig("{\"subPaths\":[\"docs\"]}");
        GitKnowledgeRepositorySyncer syncer = new GitKnowledgeRepositorySyncer();

        syncer.sync(po, new KnowledgeRepositoryDtos.CredentialConfig(), local);

        assertTrue(Files.exists(local.resolve("docs").resolve("guide.md")));
        assertFalse(Files.exists(local.resolve("src")));
    }

    @Test
    void syncWithSubPathsCleansPreviouslyCheckedOutPaths() throws Exception {
        System.setProperty("user.home", tempDir.resolve("home").toString());
        System.setProperty("XDG_CONFIG_HOME", tempDir.resolve("xdg").toString());
        Path remote = tempDir.resolve("remote-sparse-change");
        Path local = tempDir.resolve("knowledge-repo").resolve("repo-sparse-change");
        Files.createDirectories(remote.resolve("docs"));
        Files.createDirectories(remote.resolve("kb"));
        try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
            Files.writeString(remote.resolve("docs").resolve("guide.md"), "# Guide\n", StandardCharsets.UTF_8);
            Files.writeString(remote.resolve("kb").resolve("faq.md"), "# FAQ\n", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("Tester", "test@example.com").call();
        }

        KnowledgeRepositoryPo po = repository(remote);
        po.setProtocolConfig("{\"subPaths\":[\"docs\"]}");
        GitKnowledgeRepositorySyncer syncer = new GitKnowledgeRepositorySyncer();
        syncer.sync(po, new KnowledgeRepositoryDtos.CredentialConfig(), local);

        po.setProtocolConfig("{\"subPaths\":[\"kb\"]}");
        syncer.sync(po, new KnowledgeRepositoryDtos.CredentialConfig(), local);

        assertFalse(Files.exists(local.resolve("docs")));
        assertTrue(Files.exists(local.resolve("kb").resolve("faq.md")));
    }

    @Test
    void syncRestoresFullCheckoutWhenSubPathsAreCleared() throws Exception {
        System.setProperty("user.home", tempDir.resolve("home").toString());
        System.setProperty("XDG_CONFIG_HOME", tempDir.resolve("xdg").toString());
        Path remote = tempDir.resolve("remote-sparse-clear");
        Path local = tempDir.resolve("knowledge-repo").resolve("repo-sparse-clear");
        Files.createDirectories(remote.resolve("docs"));
        Files.createDirectories(remote.resolve("src"));
        try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
            Files.writeString(remote.resolve("docs").resolve("guide.md"), "# Guide\n", StandardCharsets.UTF_8);
            Files.writeString(remote.resolve("src").resolve("app.java"), "class App {}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("Tester", "test@example.com").call();
        }

        KnowledgeRepositoryPo po = repository(remote);
        po.setProtocolConfig("{\"subPaths\":[\"docs\"]}");
        GitKnowledgeRepositorySyncer syncer = new GitKnowledgeRepositorySyncer();
        syncer.sync(po, new KnowledgeRepositoryDtos.CredentialConfig(), local);

        po.setProtocolConfig("{}");
        syncer.sync(po, new KnowledgeRepositoryDtos.CredentialConfig(), local);

        assertTrue(Files.exists(local.resolve("docs").resolve("guide.md")));
        assertTrue(Files.exists(local.resolve("src").resolve("app.java")));
    }

    @Test
    void syncWithMissingSubPathFails() throws Exception {
        System.setProperty("user.home", tempDir.resolve("home").toString());
        System.setProperty("XDG_CONFIG_HOME", tempDir.resolve("xdg").toString());
        Path remote = tempDir.resolve("remote-sparse-missing");
        Path local = tempDir.resolve("knowledge-repo").resolve("repo-sparse-missing");
        Files.createDirectories(remote);
        try (Git git = Git.init().setDirectory(remote.toFile()).setInitialBranch("main").call()) {
            Files.writeString(remote.resolve("doc.md"), "# Doc\n", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("Tester", "test@example.com").call();
        }

        KnowledgeRepositoryPo po = repository(remote);
        po.setProtocolConfig("{\"subPaths\":[\"missing\"]}");
        GitKnowledgeRepositorySyncer syncer = new GitKnowledgeRepositorySyncer();

        assertThrows(IllegalStateException.class,
                () -> syncer.sync(po, new KnowledgeRepositoryDtos.CredentialConfig(), local));
    }

    private KnowledgeRepositoryPo repository(Path remote) {
        KnowledgeRepositoryPo po = new KnowledgeRepositoryPo();
        po.setRepoCode("repo");
        po.setRemoteUrl(remote.toUri().toString());
        po.setProtocolConfig("{}");
        return po;
    }
}

package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Git 协议知识库仓库同步器。
 */
@Component
public class GitKnowledgeRepositorySyncer implements KnowledgeRepositorySyncer {

    @Override
    public String protocol() {
        return KnowledgeRepositoryConstants.PROTOCOL_GIT;
    }

    @Override
    public KnowledgeRepositorySyncResult sync(KnowledgeRepositoryPo repository,
                                              KnowledgeRepositoryDtos.CredentialConfig credentialConfig,
                                              Path localPath) {
        Path normalizedLocalPath = localPath.toAbsolutePath().normalize();
        String branch = StringUtils.trimToNull(repository.getDefaultBranch());
        List<String> subPaths = KnowledgeRepositorySubPathSupport.parseProtocolConfigSubPaths(repository.getProtocolConfig());
        try {
            abortIfInterrupted();
            Files.createDirectories(normalizedLocalPath.getParent());
            UsernamePasswordCredentialsProvider credentials = credentialsProvider(credentialConfig);
            if (!Files.exists(normalizedLocalPath.resolve(".git"))) {
                deleteExistingNonGitDirectory(normalizedLocalPath);
                cloneRepository(repository, branch, normalizedLocalPath, credentials, subPaths);
            } else {
                hardResetToRemote(branch, normalizedLocalPath, credentials, subPaths);
            }
            return readHead(normalizedLocalPath);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Git 知识库同步失败: " + repository.getRepoCode(), e);
        }
    }

    private void cloneRepository(KnowledgeRepositoryPo repository,
                                 String branch,
                                 Path localPath,
                                 UsernamePasswordCredentialsProvider credentials,
                                 List<String> subPaths) throws Exception {
        var command = Git.cloneRepository()
                .setURI(repository.getRemoteUrl())
                .setDirectory(localPath.toFile())
                .setCloneAllBranches(false);
        if (StringUtils.isNotBlank(branch)) {
            command.setBranch(branch);
        }
        if (!subPaths.isEmpty()) {
            command.setNoCheckout(true);
        }
        if (credentials != null) {
            command.setCredentialsProvider(credentials);
        }
        command.setProgressMonitor(interruptibleMonitor());
        try (Git git = command.call()) {
            if (!subPaths.isEmpty()) {
                checkoutSubPaths(git, resolveActiveBranch(branch, git), subPaths);
            }
        }
    }

    private void hardResetToRemote(String branch,
                                   Path localPath,
                                   UsernamePasswordCredentialsProvider credentials,
                                   List<String> subPaths) throws Exception {
        try (Git git = Git.open(localPath.toFile())) {
            var fetch = git.fetch()
                    .setRemote("origin")
                    .setRemoveDeletedRefs(true);
            if (StringUtils.isNotBlank(branch)) {
                fetch.setRefSpecs(new RefSpec("+refs/heads/" + branch + ":refs/remotes/origin/" + branch));
            }
            if (credentials != null) {
                fetch.setCredentialsProvider(credentials);
            }
            fetch.setProgressMonitor(interruptibleMonitor());
            fetch.call();
            abortIfInterrupted();

            String activeBranch = resolveActiveBranch(branch, git);
            if (StringUtils.isBlank(activeBranch)) {
                throw new IllegalStateException("无法确定 Git 知识库分支");
            }
            if (!subPaths.isEmpty()) {
                checkoutSubPaths(git, activeBranch, subPaths);
                return;
            }
            String remoteBranch = "refs/remotes/origin/" + activeBranch;
            Ref localBranch = git.getRepository().findRef(activeBranch);
            var checkout = git.checkout().setName(activeBranch).setForced(true);
            if (localBranch == null) {
                checkout.setCreateBranch(true).setStartPoint(remoteBranch);
            }
            checkout.call();
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/" + activeBranch).call();
            git.clean().setCleanDirectories(true).setIgnore(false).setForce(true).call();
        }
    }

    private String resolveActiveBranch(String branch, Git git) throws IOException {
        String activeBranch = StringUtils.trimToNull(branch);
        if (StringUtils.isNotBlank(activeBranch)) {
            return activeBranch;
        }
        activeBranch = StringUtils.trimToNull(git.getRepository().getBranch());
        if (StringUtils.isNotBlank(activeBranch) && !ObjectId.isId(activeBranch)) {
            return activeBranch;
        }
        Ref head = git.getRepository().exactRef(Constants.HEAD);
        if (head != null && head.isSymbolic()) {
            return git.getRepository().shortenRemoteBranchName(head.getTarget().getName());
        }
        return activeBranch;
    }

    private void checkoutSubPaths(Git git, String activeBranch, List<String> subPaths) throws Exception {
        String remoteBranch = "refs/remotes/origin/" + activeBranch;
        ObjectId remoteHead = git.getRepository().resolve(remoteBranch);
        if (remoteHead == null) {
            throw new IllegalStateException("无法找到远端分支: origin/" + activeBranch);
        }
        alignLocalBranch(git, activeBranch, remoteHead);
        clearWorkingTree(git.getRepository().getWorkTree().toPath());
        List<String> checkoutPaths = resolveCheckoutPaths(git, remoteHead, subPaths);
        git.checkout()
                .setStartPoint(remoteHead.name())
                .addPaths(checkoutPaths)
                .call();
    }

    private void alignLocalBranch(Git git, String activeBranch, ObjectId remoteHead) throws IOException {
        String localBranchRef = Constants.R_HEADS + activeBranch;
        RefUpdate branchUpdate = git.getRepository().updateRef(localBranchRef);
        branchUpdate.setNewObjectId(remoteHead);
        branchUpdate.setForceUpdate(true);
        RefUpdate.Result branchResult = branchUpdate.forceUpdate();
        if (!Set.of(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE,
                RefUpdate.Result.FAST_FORWARD).contains(branchResult)) {
            throw new IllegalStateException("更新本地 Git 分支失败: " + branchResult.name());
        }
        RefUpdate headUpdate = git.getRepository().updateRef(Constants.HEAD);
        RefUpdate.Result headResult = headUpdate.link(localBranchRef);
        if (!Set.of(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE).contains(headResult)) {
            throw new IllegalStateException("更新 Git HEAD 失败: " + headResult.name());
        }
    }

    private List<String> resolveCheckoutPaths(Git git, ObjectId commitId, List<String> subPaths) throws Exception {
        Set<String> matched = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>(subPaths);
        try (RevWalk revWalk = new RevWalk(git.getRepository());
             TreeWalk treeWalk = new TreeWalk(git.getRepository(), revWalk.getObjectReader())) {
            RevCommit commit = revWalk.parseCommit(commitId);
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                for (String subPath : subPaths) {
                    if (path.equals(subPath) || path.startsWith(subPath + "/")) {
                        matched.add(path);
                        missing.remove(subPath);
                    }
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Git 知识库子路径不存在: " + String.join(", ", missing));
        }
        return List.copyOf(matched);
    }

    private void clearWorkingTree(Path workTree) throws IOException {
        if (!Files.exists(workTree)) {
            return;
        }
        Path normalizedWorkTree = workTree.toAbsolutePath().normalize();
        Path gitDir = normalizedWorkTree.resolve(".git").toAbsolutePath().normalize();
        try (var stream = Files.walk(normalizedWorkTree)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Path normalized = path.toAbsolutePath().normalize();
                if (normalized.equals(normalizedWorkTree) || normalized.startsWith(gitDir)) {
                    continue;
                }
                Files.deleteIfExists(path);
            }
        }
    }

    private KnowledgeRepositorySyncResult readHead(Path localPath) throws Exception {
        try (Git git = Git.open(localPath.toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            Iterable<RevCommit> commits = git.log().setMaxCount(1).call();
            var iterator = commits.iterator();
            RevCommit commit = iterator.hasNext() ? iterator.next() : null;
            return new KnowledgeRepositorySyncResult(
                    head == null ? null : head.name(),
                    commit == null ? null : commit.getShortMessage(),
                    commit == null || commit.getAuthorIdent() == null ? null : commit.getAuthorIdent().getName(),
                    commit == null ? null : Instant.ofEpochSecond(commit.getCommitTime()).toEpochMilli());
        }
    }

    /** 可被删除请求打断的 JGit 进度监视器。 */
    private static ProgressMonitor interruptibleMonitor() {
        return new ProgressMonitor() {
            @Override
            public void start(int totalTasks) {
                abortIfInterrupted();
            }

            @Override
            public void beginTask(String title, int totalWork) {
                abortIfInterrupted();
            }

            @Override
            public void update(int completed) {
                abortIfInterrupted();
            }

            @Override
            public void endTask() {
                abortIfInterrupted();
            }

            @Override
            public boolean isCancelled() {
                return Thread.currentThread().isInterrupted();
            }

            @Override
            public void showDuration(boolean enabled) {
                // JGit 进度回调，中断路径不关心耗时展示
            }
        };
    }

    /** 删除请求打断当前线程后，立即停止 clone/fetch。 */
    private static void abortIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Git 同步已中断");
        }
    }

    private UsernamePasswordCredentialsProvider credentialsProvider(
            KnowledgeRepositoryDtos.CredentialConfig credentialConfig) {
        if (credentialConfig == null) {
            return null;
        }
        String username = StringUtils.defaultString(credentialConfig.getUsername());
        String password = StringUtils.defaultIfBlank(credentialConfig.getToken(),
                StringUtils.defaultString(credentialConfig.getPassword()));
        if (StringUtils.isBlank(username) && StringUtils.isBlank(password)) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(username, password);
    }

    private void deleteExistingNonGitDirectory(Path localPath) throws IOException {
        if (!Files.exists(localPath)) {
            return;
        }
        try (var stream = Files.walk(localPath)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

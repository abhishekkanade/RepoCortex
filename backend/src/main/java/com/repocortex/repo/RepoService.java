package com.repocortex.repo;

import com.repocortex.common.NotFoundException;
import com.repocortex.common.ServiceBusyException;
import com.repocortex.github.GitHubClient;
import com.repocortex.github.GitHubRepoInfo;
import com.repocortex.github.GitHubUrlParser;
import com.repocortex.github.RepoCoordinates;
import com.repocortex.indexing.ChunkStore;
import com.repocortex.indexing.IndexingQueue;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class RepoService {

    private final GitHubUrlParser urlParser;
    private final GitHubClient gitHubClient;
    private final RepoRepository repoRepository;
    private final IndexingQueue indexingQueue;
    private final ChunkStore chunkStore;

    public RepoService(GitHubUrlParser urlParser, GitHubClient gitHubClient, RepoRepository repoRepository,
                       IndexingQueue indexingQueue, ChunkStore chunkStore) {
        this.urlParser = urlParser;
        this.gitHubClient = gitHubClient;
        this.repoRepository = repoRepository;
        this.indexingQueue = indexingQueue;
        this.chunkStore = chunkStore;
    }

    // Not @Transactional on purpose: no DB transaction should stay open while we wait on GitHub.
    public Repository submit(String url) {
        RepoCoordinates coords = urlParser.parse(url);
        GitHubRepoInfo info = gitHubClient.getRepo(coords.owner(), coords.name());
        String latestSha = gitHubClient.getLatestCommitSha(info.owner(), info.name(), info.defaultBranch());

        // use GitHub's canonical owner/name so different spellings map to the same row
        String fullName = new RepoCoordinates(info.owner(), info.name()).fullName();
        Repository repo = repoRepository.findByFullName(fullName).orElseGet(Repository::new);
        boolean isNew = repo.getId() == null;

        if (!isNew) {
            boolean upToDate = repo.getStatus() == RepoStatus.READY && latestSha.equals(repo.getIndexedCommitSha());
            if (upToDate || indexingQueue.isActive(repo.getId())) {
                return repo;
            }
        }

        RepoStatus previousStatus = repo.getStatus();
        String previousSha = repo.getCommitSha();

        repo.setOwner(info.owner());
        repo.setName(info.name());
        repo.setFullName(fullName);
        repo.setDescription(info.description());
        repo.setLanguage(info.language());
        repo.setStars(info.stars());
        repo.setDefaultBranch(info.defaultBranch());
        repo.setCommitSha(latestSha);
        repo.setStatus(RepoStatus.PENDING);
        repo.setErrorMessage(null);
        repo = repoRepository.save(repo);

        try {
            indexingQueue.enqueue(repo.getId());
        } catch (ServiceBusyException e) {
            // undo, so the row doesn't sit in PENDING with no job behind it
            if (isNew) {
                repoRepository.delete(repo);
            } else {
                repo.setStatus(previousStatus);
                repo.setCommitSha(previousSha);
                repoRepository.save(repo);
            }
            throw e;
        }
        return repo;
    }

    public Repository get(String owner, String name) {
        String fullName = (owner + "/" + name).toLowerCase(Locale.ROOT);
        return repoRepository.findByFullName(fullName)
                .orElseThrow(() -> new NotFoundException("Repository " + fullName + " has not been added yet"));
    }

    public List<Repository> recent() {
        return repoRepository.findTop12ByStatusOrderByIndexedAtDesc(RepoStatus.READY);
    }

    // true when there is a complete index, or the first index already has some chunks
    public boolean isChatReady(Repository repo) {
        if (repo.getIndexedCommitSha() != null) {
            return true;
        }
        return repo.getCommitSha() != null && chunkStore.existsForCommit(repo.getId(), repo.getCommitSha());
    }
}

package com.repocortex.repo;

import com.repocortex.common.ServiceBusyException;
import com.repocortex.github.GitHubClient;
import com.repocortex.github.GitHubRepoInfo;
import com.repocortex.github.GitHubUrlParser;
import com.repocortex.indexing.ChunkStore;
import com.repocortex.indexing.IndexingQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepoServiceTest {

    private static final String URL = "https://github.com/spring-projects/spring-petclinic";
    private static final String OLD_SHA = "1111111111111111111111111111111111111111";
    private static final String NEW_SHA = "2222222222222222222222222222222222222222";

    private final GitHubClient gitHubClient = mock(GitHubClient.class);
    private final RepoRepository repoRepository = mock(RepoRepository.class);
    private final IndexingQueue indexingQueue = mock(IndexingQueue.class);
    private final RepoService service = new RepoService(
            new GitHubUrlParser(), gitHubClient, repoRepository, indexingQueue, mock(ChunkStore.class));

    @BeforeEach
    void setUp() {
        when(gitHubClient.getRepo("spring-projects", "spring-petclinic")).thenReturn(new GitHubRepoInfo(
                "spring-projects", "spring-petclinic", "A sample app", "Java", 100, "main", false, 1000));
        when(gitHubClient.getLatestCommitSha("spring-projects", "spring-petclinic", "main")).thenReturn(NEW_SHA);
        when(repoRepository.save(any())).thenAnswer(inv -> {
            Repository repo = inv.getArgument(0);
            if (repo.getId() == null) {
                repo.setId(UUID.randomUUID());
            }
            return repo;
        });
    }

    // regression: commitSha used to stay null after submit
    @Test
    void newRepoStoresLatestCommitShaAndQueuesIndexing() {
        when(repoRepository.findByFullName("spring-projects/spring-petclinic")).thenReturn(Optional.empty());

        Repository repo = service.submit(URL);

        assertEquals(NEW_SHA, repo.getCommitSha());
        assertEquals(RepoStatus.PENDING, repo.getStatus());
        verify(indexingQueue).enqueue(repo.getId());
    }

    @Test
    void readyRepoOnLatestCommitIsReturnedWithoutReindexing() {
        Repository existing = existingRepo(RepoStatus.READY, NEW_SHA);
        when(repoRepository.findByFullName("spring-projects/spring-petclinic")).thenReturn(Optional.of(existing));

        Repository repo = service.submit(URL);

        assertSame(existing, repo);
        verify(repoRepository, never()).save(any());
        verify(indexingQueue, never()).enqueue(any());
    }

    @Test
    void newCommitKeepsOldIndexAndQueuesReindex() {
        Repository existing = existingRepo(RepoStatus.READY, OLD_SHA);
        when(repoRepository.findByFullName("spring-projects/spring-petclinic")).thenReturn(Optional.of(existing));

        Repository repo = service.submit(URL);

        assertEquals(NEW_SHA, repo.getCommitSha());
        assertEquals(OLD_SHA, repo.getIndexedCommitSha());
        verify(indexingQueue).enqueue(existing.getId());
    }

    @Test
    void repoAlreadyBeingIndexedIsNotQueuedTwice() {
        Repository existing = existingRepo(RepoStatus.INDEXING, null);
        when(repoRepository.findByFullName("spring-projects/spring-petclinic")).thenReturn(Optional.of(existing));
        when(indexingQueue.isActive(existing.getId())).thenReturn(true);

        service.submit(URL);

        verify(indexingQueue, never()).enqueue(any());
    }

    @Test
    void busyQueueRemovesTheNewRow() {
        when(repoRepository.findByFullName("spring-projects/spring-petclinic")).thenReturn(Optional.empty());
        doThrow(new ServiceBusyException("busy")).when(indexingQueue).enqueue(any());

        assertThrows(ServiceBusyException.class, () -> service.submit(URL));
        verify(repoRepository).delete(any());
    }

    private static Repository existingRepo(RepoStatus status, String indexedSha) {
        Repository repo = new Repository();
        repo.setId(UUID.randomUUID());
        repo.setFullName("spring-projects/spring-petclinic");
        repo.setStatus(status);
        repo.setCommitSha(indexedSha);
        repo.setIndexedCommitSha(indexedSha);
        return repo;
    }
}

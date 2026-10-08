package com.repocortex.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repositories")
@Getter
@Setter
@NoArgsConstructor
public class Repository {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    // always lowercase "owner/name", one row per GitHub repo
    @Column(name = "full_name", nullable = false, unique = true)
    private String fullName;

    @Column(length = 1000)
    private String description;

    private String language;

    private int stars;

    @Column(name = "default_branch")
    private String defaultBranch;

    // latest commit on the default branch, set on every submit
    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    // last commit with a complete index; chat uses it while a newer commit is being indexed
    @Column(name = "indexed_commit_sha", length = 40)
    private String indexedCommitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RepoStatus status = RepoStatus.PENDING;

    @Column(name = "files_total")
    private int filesTotal;

    @Column(name = "files_processed")
    private int filesProcessed;

    @Column(name = "chunk_count")
    private int chunkCount;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "file_tree", columnDefinition = "text")
    private String fileTree;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

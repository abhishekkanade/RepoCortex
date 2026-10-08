package com.repocortex.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepoRepository extends JpaRepository<Repository, UUID> {

    Optional<Repository> findByFullName(String fullName);

    List<Repository> findTop12ByStatusOrderByIndexedAtDesc(RepoStatus status);
}

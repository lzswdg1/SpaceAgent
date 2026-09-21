package com.spaceagent.platform.project.domain;

import java.util.List;
import java.util.Optional;

/** Persistence port for Project-owned source repository metadata. */
public interface SourceRepositoryRepository {

    void save(SourceRepository sourceRepository);

    Optional<SourceRepository> findById(String id);

    List<SourceRepository> findByProjectId(String projectId);
    default List<SourceRepository> pageByProjectId(String projectId,int offset,int limit){
        return findByProjectId(projectId).stream().sorted(java.util.Comparator.comparing(SourceRepository::createdAt).thenComparing(SourceRepository::id))
                .skip(offset).limit(limit).toList();
    }

    boolean existsGithub(String projectId, String providerRepositoryId);

    Optional<SourceRepository> findActiveGithub(String projectId, String providerRepositoryId);

    Optional<SourceRepository> findByMaterializationSessionId(String materializationSessionId);

    boolean existsLocal(String projectId, String bridgeId, String rootHandle);
}

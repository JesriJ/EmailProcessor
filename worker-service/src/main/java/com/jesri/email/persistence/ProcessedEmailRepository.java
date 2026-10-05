package com.jesri.email.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProcessedEmailRepository extends JpaRepository<ProcessedEmailEntity, String> {

    List<ProcessedEmailEntity> findTop25ByOrderByProcessedAtDesc();

    @Query("select p.classification, count(p) from ProcessedEmailEntity p group by p.classification")
    List<Object[]> countGroupedByClassification();

    @Query("select p.priority, count(p) from ProcessedEmailEntity p group by p.priority")
    List<Object[]> countGroupedByPriority();

    @Query("""
            select p from ProcessedEmailEntity p, EmailCacheEntity c
            where c.cacheId = p.cacheId
              and (:classification is null or :classification = '' or p.classification = :classification)
              and (:priority is null or :priority = '' or p.priority = :priority)
              and (
                   :q is null or :q = ''
                   or lower(p.emailId) like lower(concat('%', :q, '%'))
                   or lower(p.summary) like lower(concat('%', :q, '%'))
                   or lower(p.suggestedAction) like lower(concat('%', :q, '%'))
                   or lower(c.subject) like lower(concat('%', :q, '%'))
                   or lower(c.sender) like lower(concat('%', :q, '%'))
                   or lower(c.bodyText) like lower(concat('%', :q, '%'))
              )
            """)
    Page<ProcessedEmailEntity> search(
            @Param("classification") String classification,
            @Param("priority") String priority,
            @Param("q") String q,
            Pageable pageable
    );

    Optional<ProcessedEmailEntity> findByEmailId(String emailId);
}

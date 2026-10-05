package com.jesri.email.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmailCacheRepository extends JpaRepository<EmailCacheEntity, Long> {

    Optional<EmailCacheEntity> findByEmailId(String emailId);

    Optional<EmailCacheEntity> findByProviderMessageId(String providerMessageId);

    @Query("select e from EmailCacheEntity e where e.cacheId between :fromId and :toId order by e.cacheId")
    List<EmailCacheEntity> findByCacheIdRange(@Param("fromId") long fromId, @Param("toId") long toId);

    long countByEnqueuedAtIsNotNull();

    long countBySource(String source);

    @Query("select e.source, count(e) from EmailCacheEntity e group by e.source")
    List<Object[]> countGroupedBySource();
}

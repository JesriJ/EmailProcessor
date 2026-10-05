package com.jesri.email.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailCacheRepository extends JpaRepository<EmailCacheEntity, Long> {
    Optional<EmailCacheEntity> findByProviderMessageId(String providerMessageId);
    Optional<EmailCacheEntity> findByEmailId(String emailId);
}

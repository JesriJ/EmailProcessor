package com.jesri.email.producer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailCacheRepository extends JpaRepository<EmailCacheEntity, Long> {
    Optional<EmailCacheEntity> findByEmailId(String emailId);
}

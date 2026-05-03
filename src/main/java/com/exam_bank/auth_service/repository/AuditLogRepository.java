package com.exam_bank.auth_service.repository;

import com.exam_bank.auth_service.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("""
            select a from AuditLog a
            where a.userId = :userId
              and (:module is null or :module = '' or lower(a.module) like lower(concat('%', :module, '%')))
              and (:action is null or :action = '' or lower(a.action) like lower(concat('%', :action, '%')))
            order by a.createdAt desc
            """)
    Page<AuditLog> searchByUserId(
            @Param("userId") Long userId,
            @Param("module") String module,
            @Param("action") String action,
            Pageable pageable);
}

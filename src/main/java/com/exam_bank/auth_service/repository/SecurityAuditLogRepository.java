package com.exam_bank.auth_service.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.exam_bank.auth_service.entity.SecurityAuditLog;

@Repository
public interface SecurityAuditLogRepository extends JpaRepository<SecurityAuditLog, Long>,
        JpaSpecificationExecutor<SecurityAuditLog> {

    @Query("""
            select a from SecurityAuditLog a
                                                where (:useEmail = false or lower(a.email) like lower(concat('%', :email, '%')))
                                                        and (:useAction = false or a.action = :action)
                                                        and (:useOutcome = false or a.outcome = :outcome)
                                                        and (:useFromDate = false or a.createdAt >= :fromDate)
                                                        and (:useToDate = false or a.createdAt <= :toDate)
            order by a.createdAt desc
            """)
    Page<SecurityAuditLog> search(
            @Param("useEmail") boolean useEmail,
            @Param("email") String email,
            @Param("useAction") boolean useAction,
            @Param("action") String action,
            @Param("useOutcome") boolean useOutcome,
            @Param("outcome") String outcome,
            @Param("useFromDate") boolean useFromDate,
            @Param("fromDate") Instant fromDate,
            @Param("useToDate") boolean useToDate,
            @Param("toDate") Instant toDate,
            Pageable pageable);

    List<SecurityAuditLog> findByEmailOrderByCreatedAtDesc(String email, Pageable pageable);

    long countByActionAndOutcome(String action, String outcome);

    long countByActionAndOutcomeAndCreatedAtBetween(String action, String outcome, Instant from, Instant to);

    @Query("""
            select distinct a.action from SecurityAuditLog a
            where a.action is not null
            order by a.action asc
            """)
    List<String> findDistinctActions();
}

package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.entity.SecurityAuditLog;
import com.exam_bank.auth_service.repository.SecurityAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private SecurityAuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditLogService auditLogService;

    @Test
    void search_shouldNormalizeFiltersBeforeQuery() {
        Instant from = Instant.parse("2026-04-04T00:00:00Z");
        Instant to = Instant.parse("2026-04-04T23:59:59Z");
        PageRequest pageable = PageRequest.of(0, 20);

        when(auditLogRepository.search(
                anyBoolean(), anyString(),
                anyBoolean(), anyString(),
                anyBoolean(), anyString(),
                anyBoolean(), any(),
                anyBoolean(), any(),
                any()))
                .thenReturn(Page.empty());

        auditLogService.search("  USER@EXAMPLE.COM ", " login ", " success ", from, to, pageable);

        verify(auditLogRepository).search(
                eq(true),
                eq("USER@EXAMPLE.COM"),
                eq(true),
                eq("LOGIN"),
                eq(true),
                eq("SUCCESS"),
                eq(true),
                eq(from),
                eq(true),
                eq(to),
                eq(pageable));
    }

    @Test
    void search_shouldPassEmptyEmailWhenFilterIsBlank() {
        PageRequest pageable = PageRequest.of(0, 20);

        when(auditLogRepository.search(
                anyBoolean(), anyString(),
                anyBoolean(), anyString(),
                anyBoolean(), anyString(),
                anyBoolean(), any(),
                anyBoolean(), any(),
                any()))
                .thenReturn(Page.empty());

        auditLogService.search("   ", null, null, null, null, pageable);

        verify(auditLogRepository).search(
                eq(false),
                eq(""),
                eq(false),
                eq(""),
                eq(false),
                eq(""),
                eq(false),
                eq(Instant.EPOCH),
                eq(false),
                eq(Instant.EPOCH),
                eq(pageable));
    }

    @Test
    void getActionLabels_shouldContainDefaultsAndDistinctActions() {
        when(auditLogRepository.findDistinctActions()).thenReturn(List.of("CUSTOM_ACTION", "LOGIN"));

        Map<String, String> labels = auditLogService.getActionLabels();

        assertEquals("Đăng nhập", labels.get("LOGIN"));
        assertEquals("CUSTOM_ACTION", labels.get("CUSTOM_ACTION"));
        assertTrue(labels.containsKey("UPLOAD_AVATAR"));
    }
}

package com.exam_bank.auth_service.audit.service;

import com.exam_bank.auth_service.entity.AuditLog;
import com.exam_bank.auth_service.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditLogService auditLogService;

    @Test
    void saveShouldNormalizeFieldsAndPersistAuditLog() {
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditLog auditLog = auditLogService.save(42L, "  VIP_UPGRADE  ", "  VIP  ", "  upgraded  ");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog captured = captor.getValue();
        assertThat(captured.getUserId()).isEqualTo(42L);
        assertThat(captured.getAction()).isEqualTo("VIP_UPGRADE");
        assertThat(captured.getModule()).isEqualTo("VIP");
        assertThat(captured.getDescription()).isEqualTo("upgraded");
        assertThat(auditLog).isSameAs(captured);
    }

    @Test
    void searchShouldDelegateToRepositoryWithNormalizedFilters() {
        Page<AuditLog> page = new PageImpl<>(List.of());
        when(auditLogRepository.searchByUserId(42L, "VIP", "upgrade", PageRequest.of(0, 20))).thenReturn(page);

        Page<AuditLog> result = auditLogService.search(42L, " VIP ", " upgrade ", PageRequest.of(0, 20));

        assertThat(result).isSameAs(page);
        verify(auditLogRepository).searchByUserId(42L, "VIP", "upgrade", PageRequest.of(0, 20));
    }
}

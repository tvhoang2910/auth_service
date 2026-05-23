package com.exam_bank.auth_service.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class SecurityAuditSchemaRepair {

    private static final String SECURITY_AUDIT_REPAIR_SQL = """
            DO $$
            BEGIN
              IF EXISTS (
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'security_audit_logs'
              ) THEN

                IF EXISTS (
                  SELECT 1 FROM information_schema.columns
                  WHERE table_schema = 'public'
                    AND table_name = 'security_audit_logs'
                    AND column_name = 'email'
                    AND data_type = 'bytea'
                ) THEN
                  ALTER TABLE public.security_audit_logs
                    ALTER COLUMN email TYPE VARCHAR(255)
                    USING encode(email, 'escape');
                END IF;

                IF EXISTS (
                  SELECT 1 FROM information_schema.columns
                  WHERE table_schema = 'public'
                    AND table_name = 'security_audit_logs'
                    AND column_name = 'action'
                    AND data_type = 'bytea'
                ) THEN
                  ALTER TABLE public.security_audit_logs
                    ALTER COLUMN action TYPE VARCHAR(50)
                    USING encode(action, 'escape');
                END IF;

                IF EXISTS (
                  SELECT 1 FROM information_schema.columns
                  WHERE table_schema = 'public'
                    AND table_name = 'security_audit_logs'
                    AND column_name = 'outcome'
                    AND data_type = 'bytea'
                ) THEN
                  ALTER TABLE public.security_audit_logs
                    ALTER COLUMN outcome TYPE VARCHAR(20)
                    USING encode(outcome, 'escape');
                END IF;

                IF EXISTS (
                  SELECT 1 FROM information_schema.columns
                  WHERE table_schema = 'public'
                    AND table_name = 'security_audit_logs'
                    AND column_name = 'ip_address'
                    AND data_type = 'bytea'
                ) THEN
                  ALTER TABLE public.security_audit_logs
                    ALTER COLUMN ip_address TYPE VARCHAR(45)
                    USING encode(ip_address, 'escape');
                END IF;

                IF EXISTS (
                  SELECT 1 FROM information_schema.columns
                  WHERE table_schema = 'public'
                    AND table_name = 'security_audit_logs'
                    AND column_name = 'user_agent'
                    AND data_type = 'bytea'
                ) THEN
                  ALTER TABLE public.security_audit_logs
                    ALTER COLUMN user_agent TYPE VARCHAR(500)
                    USING encode(user_agent, 'escape');
                END IF;

                IF EXISTS (
                  SELECT 1 FROM information_schema.columns
                  WHERE table_schema = 'public'
                    AND table_name = 'security_audit_logs'
                    AND column_name = 'details'
                    AND data_type = 'bytea'
                ) THEN
                  ALTER TABLE public.security_audit_logs
                    ALTER COLUMN details TYPE VARCHAR(500)
                    USING encode(details, 'escape');
                END IF;
              END IF;
            END $$;
            """;

    private static final String USER_ROLE_REPAIR_SQL = """
            DO $$
            BEGIN
              IF EXISTS (
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'users'
              ) AND EXISTS (
                SELECT 1
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'users'
                  AND constraint_name = 'users_role_check'
              ) THEN
                ALTER TABLE public.users
                  DROP CONSTRAINT users_role_check;

                ALTER TABLE public.users
                  ADD CONSTRAINT users_role_check
                  CHECK (role IN ('USER', 'CONTRIBUTOR', 'ADMIN', 'AUDIT', 'SYSTEM_ADMIN'));
              END IF;
            END $$;
            """;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void repairIfNeeded() {
        if (!isPostgreSql()) {
            return;
        }

        try {
            jdbcTemplate.execute(SECURITY_AUDIT_REPAIR_SQL);
            jdbcTemplate.execute(USER_ROLE_REPAIR_SQL);
            log.info("Security audit schema repair check completed");
        } catch (Exception ex) {
            log.warn("Security audit schema repair skipped: {}", ex.getMessage());
        }
    }

    private boolean isPostgreSql() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            return productName != null && productName.toLowerCase(Locale.ROOT).contains("postgresql");
        } catch (Exception ex) {
            log.warn("Could not detect database vendor for audit schema repair: {}", ex.getMessage());
            return false;
        }
    }
}

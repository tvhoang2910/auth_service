package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.request.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AuthServiceTransactionalConfigTest {

    @Test
    void login_shouldNotBeReadOnlyTransaction() throws Exception {
        Method method = AuthService.class.getMethod("login", LoginRequest.class);
        Transactional annotation = method.getAnnotation(Transactional.class);

        assertNotNull(annotation);
        assertFalse(annotation.readOnly());
    }

    @Test
    void logout_shouldNotBeReadOnlyTransaction() throws Exception {
        Method method = AuthService.class.getMethod("logout", String.class);
        Transactional annotation = method.getAnnotation(Transactional.class);

        assertNotNull(annotation);
        assertFalse(annotation.readOnly());
    }

    @Test
    void verifyForgotPasswordOtp_shouldNotBeReadOnlyTransaction() throws Exception {
        Method method = AuthService.class.getMethod("verifyForgotPasswordOtp", String.class, String.class);
        Transactional annotation = method.getAnnotation(Transactional.class);

        assertNotNull(annotation);
        assertFalse(annotation.readOnly());
    }
}

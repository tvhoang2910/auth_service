# Auth Service (Spring Boot)

Tai lieu tong hop cho dich vu xac thuc cua he thong Exam Bank.

## 1. Tong quan
Auth Service hien tai ho tro:
- Dang ky va dang nhap bang email/mat khau.
- Dang nhap Google OAuth2.
- JWT Access Token + Refresh Token.
- Logout tuc thi thong qua JWT blacklist tren Redis.
- API lay thong tin nguoi dung hien tai (/me) co cache Redis.
- Flow quen mat khau day du:
1. Gui OTP.
2. Xac thuc OTP.
3. Dat mat khau moi bang reset token.
- Rate limit cho cac endpoint forgot-password, verify OTP, resend OTP.
- Security audit log cho cac su kien dang nhap, refresh, logout, forgot/reset password.

## 2. Kien truc bao mat va luu tru token
### 2.1 Access Token + Blacklist
- Access token duoc ky HS256.
- Khi logout, token duoc dua vao Redis blacklist den khi het han.
- Moi request Bearer token di qua JwtBlacklistFilter de chan token da logout.

Key mau:
- auth:blacklist:{sha256(access_token)}

### 2.2 Refresh Token rotate
- Sau login, he thong cap access token + refresh token.
- Refresh token duoc luu Redis voi TTL.
- API /refresh se rotate refresh token:
1. Thu hoi token cu.
2. Phat hanh cap token moi.

Key mau:
- auth:refresh:user:{userId} -> refreshToken
- auth:refresh:value:{sha256(refreshToken)} -> userId

### 2.3 Forgot password (OTP + reset token)
- OTP 6 chu so duoc tao ngau nhien.
- OTP duoc luu Redis va publish qua RabbitMQ de gui email.
- Sau verify OTP thanh cong, he thong cap reset token tam thoi (TTL 10 phut).
- API reset password cap nhat mat khau moi va xoa token tam.
- Cac hanh dong forgot/verify/resend deu co rate-limit theo email de giam abuse.

### 2.4 Security audit log
- He thong ghi nhat ky bao mat co cau truc vao log voi format SECURITY_AUDIT.
- Du lieu chinh: action, outcome (SUCCESS/FAILURE), email, details.
- Cac action da duoc ghi log:
1. LOGIN
2. LOGOUT
3. REFRESH_TOKEN
4. FORGOT_PASSWORD
5. VERIFY_RESET_OTP
6. RESET_PASSWORD

Key mau:
- reset_password:otp:{otp} -> email (TTL = auth.forgot-password.otp-ttl-seconds)
- reset_password:email:{email} -> otp (TTL = auth.forgot-password.otp-ttl-seconds)
- reset_password:token:{resetToken} -> email (TTL = 10 phut)

## 3. Cac endpoint hien co
Base path: /api/v1/auth

### 3.1 Public endpoints
1. POST /register
2. POST /login
3. POST /refresh
4. POST /forgot-password
5. POST /forgot-password/resend
6. POST /forgot-password/verify-otp
7. POST /reset-password
8. GET/POST /oauth2/**

### 3.2 Authenticated endpoints
1. POST /logout
2. GET /me

### 3.3 Request/response mau
Dang ky:
```json
{
  "email": "user@example.com",
  "password": "password123",
  "fullName": "Nguyen Van A",
  "role": "USER"
}
```

Dang nhap:
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

Verify OTP:
```json
{
  "email": "user@example.com",
  "otp": "123456"
}
```

Response verify OTP:
```json
{
  "resetToken": "generated_reset_token",
  "message": "OTP verified successfully"
}
```

Reset password:
```json
{
  "resetToken": "generated_reset_token",
  "newPassword": "new-password-123"
}
```

## 4. Cau hinh moi truong
Tat ca cau hinh nam trong src/main/resources/application.properties.

### 4.1 PostgreSQL
- DATABASE_URL (default: jdbc:postgresql://localhost:5432/exam_bank_db)
- DATABASE_USERNAME (default: postgres)
- DATABASE_PASSWORD

### 4.2 JWT va refresh token
- JWT_ISSUER (default: auth_service)
- JWT_EXPIRATION_SECONDS (default: 3600)
- JWT_SECRET_BASE64
- REFRESH_TOKEN_EXPIRATION_SECONDS (default: 604800)

### 4.3 Redis
- REDIS_HOST (default: localhost)
- REDIS_PORT (default: 6379)
- REDIS_TIMEOUT (default: 2s)
- REDIS_CONNECT_TIMEOUT (default: 2s)

### 4.4 Forgot password + RabbitMQ
- FORGOT_PASSWORD_OTP_TTL_SECONDS (default: 300)
- FORGOT_PASSWORD_RATE_LIMIT_FORGOT_MAX_ATTEMPTS (default: 5)
- FORGOT_PASSWORD_RATE_LIMIT_FORGOT_WINDOW_SECONDS (default: 300)
- FORGOT_PASSWORD_RATE_LIMIT_VERIFY_MAX_ATTEMPTS (default: 10)
- FORGOT_PASSWORD_RATE_LIMIT_VERIFY_WINDOW_SECONDS (default: 300)
- FORGOT_PASSWORD_RATE_LIMIT_RESEND_MAX_ATTEMPTS (default: 3)
- FORGOT_PASSWORD_RATE_LIMIT_RESEND_WINDOW_SECONDS (default: 300)
- NOTIFICATION_EXCHANGE (default: notification.events)
- NOTIFICATION_EMAIL_OTP_ROUTING_KEY (default: notification.send.email.otp)
- NOTIFICATION_EMAIL_QUEUE (default: notification-service.email-queue)
- RABBITMQ_USERNAME (default: guest)
- RABBITMQ_PASSWORD (default: guest)

### 4.5 Google OAuth2
- GOOGLE_CLIENT_ID
- GOOGLE_CLIENT_SECRET
- OAUTH2_SUCCESS_REDIRECT_URL (default: http://localhost:5173/oauth2/success)

## 5. Chay dich vu
Yeu cau:
- Java 21+
- PostgreSQL dang chay
- Redis dang chay
- RabbitMQ dang chay neu can gui OTP email qua queue

Lenh chay:
- Windows: .\mvnw.cmd spring-boot:run
- Linux/Mac: ./mvnw spring-boot:run

### 5.1 Build va chay bang Docker
Tai folder auth_service:

Build image:
```bash
docker build -t auth-service:latest .
```

Run container:
```bash
docker run -d --name auth-service -p 8080:8080 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/exam_bank_db \
  -e DATABASE_USERNAME=postgres \
  -e DATABASE_PASSWORD=password_tu_dien_cua_nguoi_ta \
  -e REDIS_HOST=host.docker.internal \
  -e RABBITMQ_HOST=host.docker.internal \
  auth-service:latest
```

Luu y:
1. Bien dung la DATABASE_USERNAME (khong co khoang trang).
2. Dockerfile da co HEALTHCHECK qua endpoint /api/v1/auth/actuator/health.

## 6. Kiem thu
Backend:
- Lenh: .\mvnw.cmd test
- Ket qua gan nhat: BUILD SUCCESS
- Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
- Test classes:
1. AuthServiceApplicationTests
2. JwtBlacklistFilterTest
3. AuthServiceTest

Frontend lien quan auth flow (tai repo exam-web):
- Lenh: npx playwright test --quiet
- Ket qua gan nhat: 6 passed

## 7. Tich hop frontend flow quen mat khau
Frontend da su dung chuoi man hinh:
1. /forgot-password
2. /forgot-password/verify
3. /reset-password

Flow:
1. Nguoi dung nhap email va gui OTP.
2. Nguoi dung nhap OTP hoac resend OTP.
3. Verify thanh cong -> nhan resetToken.
4. Nguoi dung dat mat khau moi bang resetToken.

## 8. Auth service nen lam gi tiep theo
De dua production-ready, nen uu tien:

1. Hash OTP truoc khi luu Redis (khong luu OTP plain text).
2. Them endpoint reset password co revoke toan bo refresh token hien tai cua user (tat ca session).
3. Chuan hoa error code/business code de FE xu ly theo ma thay vi message text.
4. Them integration test cho Redis va RabbitMQ bang Testcontainers.
5. Bo secrets mac dinh trong application.properties (jwt secret, DB password, OAuth secret) o moi truong that.
6. Hoan thien tai lieu OpenAPI/Swagger cho toan bo endpoint.

## 9. Ghi chu trien khai
- Khong hard-code secret o production.
- Redis nen bat auth va gioi han network.
- RabbitMQ nen tao user rieng cho app, khong dung tai khoan guest cho moi truong deploy.

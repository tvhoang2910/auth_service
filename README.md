# Auth Service (exam_bank)

Tai lieu nay duoc cap nhat theo source code hien tai trong module auth_service.

## 1. Tong quan

Auth Service la dich vu identity + account management cho he thong Exam Bank.

Phu trach cac nhom nghiep vu chinh:

- Email/password auth: register, verify email OTP, login, refresh token, logout.
- Google OAuth2 login (code exchange flow cho frontend).
- Forgot password OTP flow (send/resend/verify/reset) co rate limit.
- User self-profile: xem profile, cap nhat profile, upload avatar.
- Admin user management: search/filter users, create, lock/unlock, update role, import JSON.
- Subscription purchase review flow: plan management, upload payment bill, review queue, approval audit.
- Push subscription management (VAPID key, subscribe/unsubscribe, internal fetch).
- Presence realtime qua SSE + Redis pub/sub.
- Security audit log va truy van audit cho admin.

Context path mac dinh:

- /api/v1/auth

## 2. Tech stack va dependencies

- Java 21
- Spring Boot 4.0.3
- Spring MVC + Validation
- Spring Security + OAuth2 Client + Resource Server (JWT)
- Spring Data JPA (PostgreSQL)
- Redis (blacklist token, refresh token, OTP, rate limit, profile cache, presence)
- RabbitMQ (notification events)
- MinIO (avatar va payment bill image storage)
- Maven Wrapper (mvnw/mvnw.cmd)

## 3. Runtime requirements

Can toi thieu:

- PostgreSQL
- Redis
- RabbitMQ
- MinIO
- JDK 21

Health check:

- GET /api/v1/auth/actuator/health

## 4. Chay local

### 4.1 Chuan bi environment variables

Duoi day la bo bien quan trong can set cho local/dev:

```bash
PORT=8080

DATABASE_URL=jdbc:postgresql://localhost:5432/exam_bank_db
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=postgres

JWT_ISSUER=auth_service
JWT_EXPIRATION_SECONDS=3600
JWT_SECRET_BASE64=<base64-secret-at-least-32-bytes-after-decode>
REFRESH_TOKEN_EXPIRATION_SECONDS=604800

REDIS_HOST=localhost
REDIS_PORT=6379

RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest

MINIO_URL=http://localhost:9000
MINIO_ACCESS_KEY=admin
MINIO_SECRET_KEY=password
MINIO_BUCKET_NAME=users

VAPID_PUBLIC_KEY=<public-vapid-key>
NOTIFICATION_INTERNAL_TOKEN=<shared-internal-token>

GOOGLE_CLIENT_ID=<google-client-id>
GOOGLE_CLIENT_SECRET=<google-client-secret>
OAUTH2_SUCCESS_REDIRECT_URL=http://localhost:5173/oauth2/success

CORS_ALLOWED_ORIGINS=http://localhost:5173
```

### 4.2 Run service

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

Linux/macOS:

```bash
./mvnw spring-boot:run
```

Build package:

```powershell
.\mvnw.cmd clean package
```

Run tests:

```powershell
.\mvnw.cmd test
```

## 5. API map

Tat ca path ben duoi la relative path theo context /api/v1/auth.

### 5.1 Public endpoints

- POST /register
- POST /register/resend-verification
- POST /register/verify-email
- POST /login
- POST /refresh
- POST /forgot-password
- POST /forgot-password/resend
- POST /forgot-password/verify-otp
- POST /reset-password
- GET /oauth2/authorization/google
- POST /oauth2/exchange
- GET /push-subscription/vapid-public-key
- GET /push-subscription/user/{userId} (DEPRECATED, internal, yeu cau header X-Internal-Token; sunset 2026-10-31; migrate sang auth.events push-subscription sync)
- GET /push-subscription/role/{role} (DEPRECATED, internal, yeu cau header X-Internal-Token; sunset 2026-10-31; migrate sang auth.events push-subscription sync)
- GET /internal/users/{userId}/display-name (DEPRECATED, internal, yeu cau header X-Internal-Token; sunset 2026-10-31; migrate sang auth.events user profile sync)
- GET /internal/users/{userId}/premium-status (DEPRECATED, internal, yeu cau header X-Internal-Token; sunset 2026-10-31; migrate sang auth.events user profile sync)
- POST /internal/users/display-names (DEPRECATED, internal, yeu cau header X-Internal-Token; sunset 2026-10-31; migrate sang auth.events user profile sync)
- GET /sse/presence (token qua Authorization hoac query param token)
- POST /sse/presence/heartbeat (token qua Authorization hoac query param token)
- GET /actuator/health

### 5.2 Authenticated endpoints

- POST /logout
- GET /me
- PATCH /me
- POST /me/avatar (multipart/form-data, field file)
- POST /push-subscription
- DELETE /push-subscription
- GET /subscriptions/plans
- GET /subscriptions/plans/manage
- POST /subscriptions/plans
- POST /subscriptions/purchase-requests (multipart/form-data, field bill)
- GET /subscriptions/my-requests
- GET /subscriptions/review-queue
- PATCH /subscriptions/purchase-requests/{subscriptionId}/review
- GET /subscriptions/purchase-requests/{subscriptionId}/approvals

### 5.3 Admin endpoints

- GET /admin/users
- POST /admin/users
- PUT /admin/users/{id}/status
- PUT /admin/users/{id}/role
- POST /admin/users/import-json
- GET /admin/audit-logs
- GET /admin/audit-logs/stats
- GET /admin/audit-logs/actions

## 6. Auth va security behavior

### 6.1 JWT

- Access token duoc ky HS256.
- JWT claims chinh:
  - sub = email
  - userId = id user
  - role = role user
- Resource server validate issuer + role claim + userId claim.

### 6.2 Refresh token

- Login va refresh tra ve ca access token + refresh token.
- Refresh token duoc rotate moi lan refresh thanh cong.
- Redis luu ca mapping user -> refresh token va token -> user (token key duoc hash SHA-256).

### 6.3 Logout va revoke

- Access token bi blacklist den het han JWT TTL.
- Request mang token da blacklist se bi chan boi JwtBlacklistFilter voi 401.
- Logout cung revoke refresh token cua user.

### 6.4 Brute-force protection

- Login limiter:
  - 5 lan sai -> block 30 phut.
- Forgot password OTP limiter:
  - forgot: max 5 lan / 300s
  - verify: max 10 lan / 300s
  - resend: max 3 lan / 300s

### 6.5 Email verification

- User register bang email/password mac dinh emailVerified = false.
- Login se bi tu choi neu email chua verify.
- OTP verify email va forgot password deu la ma 6 chu so.

### 6.6 Role model

- USER
- CONTRIBUTOR
- ADMIN

### 6.7 Internal token endpoints

Nhung endpoint danh cho service-to-service duoc bao ve bang header:

- X-Internal-Token: <NOTIFICATION_INTERNAL_TOKEN>

Trang thai hien tai:

- Cac endpoint internal cu duoc giu tam thoi de backward compatibility va da danh dau DEPRECATED.
- Auth service se tra them header Deprecation=true, Sunset=Sat, 31 Oct 2026 23:59:59 GMT va Warning de canh bao migration.
- Huong thay the chinh thuc: su dung auth.events projection (user profile sync + push-subscription sync), khong goi HTTP internal endpoint moi.

## 7. Presence realtime (SSE)

PresenceService su dung Redis pub/sub channel presence:all.

- SSE stream: GET /sse/presence
- Heartbeat: POST /sse/presence/heartbeat
- Chi user role ADMIN hoac CONTRIBUTOR moi duoc ket noi SSE presence.
- Login/logout/timeout se phat event join/leave va cap nhat online count theo role.

## 8. Subscription + payment review

### 8.1 Premium plans

- Tao plan moi qua POST /subscriptions/plans.
- durationDays >= 1 neu khong phai lifetime.
- lifetime=true se co han su dung rat dai (36500 ngay).

### 8.2 Purchase request

- Endpoint tao request: POST /subscriptions/purchase-requests
- Upload bill image len MinIO.
- Rule file:
  - Bat buoc la image/*
  - Toi da 10MB cho bill
- Chan duplicate request cho cung user + plan neu da co status PENDING_REVIEW hoac APPROVED.

### 8.3 Review flow

- Reviewer hop le: ADMIN hoac CONTRIBUTOR.
- Khong duoc tu review request cua chinh minh.
- Moi request chi review duoc khi status = PENDING_REVIEW.
- Ket qua review duoc ghi vao subscription_approval_audits.
- Service publish notification event khi request moi va khi reviewed.

## 9. Push subscription

- Public key endpoint cho browser: GET /push-subscription/vapid-public-key
- User subscribe: POST /push-subscription
- User unsubscribe (soft delete): DELETE /push-subscription
- Endpoint push la unique toan he thong; neu ton tai se duoc re-bind theo user hien tai.

## 10. User profile va file storage

### 10.1 Profile update

PATCH /me ho tro cap nhat:

- fullName
- avatarUrl
- phoneNumber
- school
- subject
- currentPassword + newPassword (cap doi, khong duoc gui le)

### 10.2 Avatar upload

- POST /me/avatar (multipart field file)
- Rule file:
  - Bat buoc la image/*
  - Toi da 5MB
- File luu MinIO duoi object key dang avatars/user-{userId}/...

## 11. Audit logs

Moi action bao mat chinh duoc log vao bang security_audit_logs:

- LOGIN
- LOGOUT
- REFRESH_TOKEN
- FORGOT_PASSWORD
- VERIFY_RESET_OTP
- VERIFY_REGISTER_EMAIL
- RESET_PASSWORD
- UPDATE_PROFILE
- UPLOAD_AVATAR

Admin co the:

- Query phan trang + filter (email/action/outcome/date)
- Lay stats login success/failure trong ngay
- Lay danh sach action labels

Luu y van hanh:

- SecurityAuditSchemaRepair tu dong repair column type neu bang security_audit_logs bi lech ve bytea tren PostgreSQL.

## 12. Error response format

GlobalExceptionHandler tra ve format chung:

```json
{
  "timestamp": "2026-04-10T09:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "fieldErrors": {
    "email": "must be a well-formed email address"
  }
}
```

Mapping thong dung:

- 400: IllegalArgumentException / payload malformed / validation fail
- 401: AuthenticationException / JWT invalid / token revoked
- 403: role khong du quyen hoac internal token sai
- 409: ConflictException (email da ton tai)
- 429: BruteForceBlockedException
- 500: loi khong du kien

## 13. Payload examples

Register:

```json
{
  "email": "user@example.com",
  "password": "password123",
  "fullName": "Nguyen Van A",
  "role": "USER"
}
```

Login:

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

AuthTokenResponse:

```json
{
  "accessToken": "<jwt>",
  "refreshToken": "<opaque-refresh-token>",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "refreshExpiresIn": 604800,
  "email": "user@example.com",
  "role": "USER"
}
```

Update user status (admin):

```json
{
  "status": 0,
  "reason": "Violation of policy"
}
```

Hoac backward-compatible:

```json
{
  "active": true,
  "reason": "Manual unlock"
}
```

Verify forgot-password OTP:

```json
{
  "email": "user@example.com",
  "otp": "123456"
}
```

Response:

```json
{
  "resetToken": "generated_reset_token",
  "message": "OTP verified successfully"
}
```

## 14. Luu y khi tich hop frontend/service khac

- Luon gui Authorization: Bearer <accessToken> cho authenticated APIs.
- Refresh flow can co refreshToken hop le va chua bi rotate.
- Internal APIs bat buoc co X-Internal-Token khop config.
- OAuth2 frontend flow:
  1. Open /oauth2/authorization/google
  2. Sau redirect, frontend nhan code
  3. Goi POST /oauth2/exchange de doi code lay token pair
- CORS origins doc tu app.cors.allowed-origins (comma-separated).

## 15. Source references

Cac file nen doc de hieu nhanh module:

- src/main/java/com/exam_bank/auth_service/controller/AuthController.java
- src/main/java/com/exam_bank/auth_service/controller/AdminUserController.java
- src/main/java/com/exam_bank/auth_service/controller/SubscriptionRequestController.java
- src/main/java/com/exam_bank/auth_service/controller/PushSubscriptionController.java
- src/main/java/com/exam_bank/auth_service/controller/PresenceSseController.java
- src/main/java/com/exam_bank/auth_service/config/WebSecurityConfig.java
- src/main/java/com/exam_bank/auth_service/service/AuthService.java
- src/main/resources/application.properties

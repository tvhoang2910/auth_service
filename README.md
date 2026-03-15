# Auth Service (Exam Bank)

Tai lieu nay mo ta nghiep vu va cach van hanh cho dich vu xac thuc tai module auth_service.

## 1. Tong quan nghiep vu

Auth Service hien dang phu trach cac nghiep vu sau:

- Dang ky tai khoan bang email/password.
- Dang nhap bang email/password.
- Dang nhap Google OAuth2.
- Lam moi phien dang nhap qua refresh token.
- Dang xuat.
- Xem thong tin ca nhan hien tai.
- Cap nhat ho ten va doi mat khau tai khoan hien tai.
- Quen mat khau bang OTP:
1. Gui OTP.
2. Xac thuc OTP.
3. Dat lai mat khau.
- Quan tri danh sach user cho admin:
1. Xem danh sach co tim kiem/loc role/phan trang.
2. Tao user moi.
3. Doi role user.
4. Khoa/mo khoa user bang status 0/1.

## 2. Base URL va endpoint map

Service context path:

- /api/v1/auth

### 2.1 Public endpoints

- POST /register
- POST /login
- POST /refresh
- POST /forgot-password
- POST /forgot-password/resend
- POST /forgot-password/verify-otp
- POST /reset-password
- GET /oauth2/**
- POST /oauth2/**

### 2.2 Authenticated endpoints

- POST /logout
- GET /me
- PATCH /me

### 2.3 Admin endpoints

- GET /admin/users
- POST /admin/users
- PUT /admin/users/{id}/status
- PUT /admin/users/{id}/role

## 3. Nghiep vu trang thai user (status)

Quy uoc hien tai:

- status = 1: active (dang hoat dong)
- status = 0: banned (bi khoa)

Chi tiet API update status:

- Endpoint: PUT /admin/users/{id}/status
- Payload uu tien dung dang so:

```json
{
  "status": 0
}
```

Hoac

```json
{
  "status": 1
}
```

De tuong thich nguoc, API van chap nhan:

```json
{
  "active": true
}
```

Hoac

```json
{
  "active": false
}
```

Khi user bi khoa (status = 0), login se bi tu choi ngay.

## 4. Mau request/response nhanh

### 4.1 Dang ky

```json
{
  "email": "user@example.com",
  "password": "password123",
  "fullName": "Nguyen Van A",
  "role": "USER"
}
```

### 4.2 Dang nhap

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

### 4.3 Cap nhat profile (/me)

```json
{
  "fullName": "Nguyen Van B",
  "currentPassword": "old-password-123",
  "newPassword": "new-password-123"
}
```

### 4.4 Tao user tu admin

```json
{
  "email": "teacher.one@example.com",
  "fullName": "Teacher One",
  "password": "strong-pass-123",
  "role": "CONTRIBUTOR"
}
```

### 4.5 Doi role user

```json
{
  "role": "ADMIN"
}
```

### 4.6 Verify OTP quen mat khau

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

## 5. Chay dich vu

Yeu cau toi thieu:

- Java 21+
- PostgreSQL
- Redis
- RabbitMQ (de day su kien gui OTP email)

Lenh chay local:

- Windows: .\\mvnw.cmd spring-boot:run
- Linux/Mac: ./mvnw spring-boot:run

## 6. Build va test

Build/test backend:

- .\\mvnw.cmd test

Ket qua gan day:

- BUILD SUCCESS
- 30 tests passed

Frontend lien quan auth/admin (exam-web):

- npm run build
- npx playwright test tests/app.spec.ts

## 7. Cac bien moi truong quan trong

Cac bien duoc map trong src/main/resources/application.properties:

- DATABASE_URL
- DATABASE_USERNAME
- DATABASE_PASSWORD
- JWT_ISSUER
- JWT_EXPIRATION_SECONDS
- JWT_SECRET_BASE64
- REFRESH_TOKEN_EXPIRATION_SECONDS
- REDIS_HOST
- REDIS_PORT
- RABBITMQ_HOST
- RABBITMQ_PORT
- RABBITMQ_USERNAME
- RABBITMQ_PASSWORD
- GOOGLE_CLIENT_ID
- GOOGLE_CLIENT_SECRET
- OAUTH2_SUCCESS_REDIRECT_URL

## 8. De xuat mo rong nghiep vu (khong tap trung hieu nang/bao mat)

Duoi day la cac chuc nang nen uu tien neu tiep tuc phat trien Auth Service theo nghiep vu Exam Bank:

1. Account lifecycle day du
- Bo sung trang thai nghiep vu: PENDING_ACTIVATION, ACTIVE, SUSPENDED, DELETED.
- Co ly do khoa mo tai khoan va nguoi thuc hien thao tac.

2. User onboarding workflow
- Kich hoat tai khoan qua email xac minh.
- Ho tro moi user vao he thong theo invite link (danh cho giao vien/contributor).

3. Quan ly ho so nghiep vu
- Mo rong profile: avatar, so dien thoai, don vi truong, bo mon.
- Cho phep cap nhat preference (ngon ngu, mui gio, thong bao).

4. Phan quyen theo mo hinh nghiep vu
- Role template theo doi tuong su dung (Student, Teacher, Content Reviewer, Admin).
- Gan role theo quy trinh phe duyet thay vi gan truc tiep ngay lap tuc.

5. Quan ly to chuc/tenant
- Ho tro user thuoc nhieu truong/organization.
- Role co pham vi theo tung organization.

6. Lich su thay doi tai khoan cho van hanh nghiep vu
- Theo doi lich su doi role, khoa/mo khoa, doi ten.
- Co API de backoffice truy vet bien dong user.

7. Batch operation cho admin
- Import user hang loat bang CSV.
- Cap nhat role/trang thai hang loat theo bo loc.

8. Rule nghiep vu theo goi dich vu
- Gan entitlement theo plan (free/premium/school plan).
- API tra ve quyen su dung tinh nang de FE dieu huong UI.

9. Dong bo voi module khac
- Phat su kien domain khi user thay doi role/status de Exam/Notification cap nhat.
- Vi du: USER_STATUS_CHANGED, USER_ROLE_CHANGED.

10. Self-service tai khoan
- Endpoint user tu khoa mo thong bao email/app.
- Endpoint user xoa tai khoan (soft delete) theo chinh sach san pham.

## 9. Ghi chu cho team

- README nay tap trung vao nghiep vu va contract API.
- Cac van de hardening bao mat/hieu nang co the duoc tach thanh tai lieu rieng de theo doi ky thuat.

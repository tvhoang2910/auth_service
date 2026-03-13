# Auth Flow Overview

## 1) Boundary va trach nhiem

### Nginx Gateway (Infrastructure Layer)
- SSL termination.
- Global rate limit theo IP.
- Routing theo path, vi du:
  - /api/v1/auth -> AuthService
  - /api/v1/exams -> ExamService
- CORS policy.
- Co the validate so bo JWT o gateway de giam tai backend.

### AuthService (Business Layer)
- Register, login, oauth2 login, logout.
- Issue va validate JWT.
- Quan ly blacklist token trong Redis de logout triet de.

## 2) Endpoints hien tai
- POST /api/v1/auth/register
- POST /api/v1/auth/login
- POST /api/v1/auth/logout
- GET /api/v1/auth/oauth2/authorization/google

## 3) Register Flow
1. Client goi POST /register voi email, password (optional), fullName.
2. AuthService normalize email va check trung email.
3. Neu co password: ma hoa BCrypt.
4. Luu user vao DB voi role mac dinh USER (neu request khong truyen role).
5. Tra ve RegisterResponse.

## 4) Login Flow (username/password)
1. Client goi POST /login voi email + password.
2. AuthenticationManager + DaoAuthenticationProvider xac thuc thong tin.
3. Neu hop le: JwtService issue access token.
4. Tra ve AuthTokenResponse (accessToken, tokenType, expiresIn, email, role).

## 5) Google OAuth2 Login Flow
1. Client mo /oauth2/authorization/google.
2. Spring Security redirect sang Google consent.
3. Google callback ve service theo oauth2 flow.
4. OAuth2AuthenticationSuccessHandler lay email, name.
5. AuthService upsert user theo email (neu chua co thi tao moi, password = null).
6. Issue JWT noi bo cho he thong.
7. Redirect ve frontend URL voi token trong query param.

## 6) JWT Validation Flow cho API
1. Request mang Authorization: Bearer <token>.
2. JwtBlacklistFilter chay truoc BearerTokenAuthenticationFilter:
   - Neu token nam trong Redis blacklist -> tra 401 ngay.
3. Neu khong bi blacklist, oauth2ResourceServer jwt decoder validate signature + exp.
4. Qua duoc validate thi vao business endpoint.

## 7) Logout Flow (Stateless + Redis Blacklist)
1. Client goi POST /logout voi Authorization: Bearer <token>.
2. AuthService tach token tu header.
3. JwtService decode token de lay expiresAt.
4. TokenBlacklistService luu hash token vao Redis voi TTL = thoi gian con lai den exp.
5. Tu request sau, neu dung token nay se bi JwtBlacklistFilter chan 401.

## 8) Du lieu va thanh phan chinh
- DB: bang users (email unique, role, password co the null neu oauth).
- Redis: key blacklist token theo hash SHA-256.
- Security:
  - DaoAuthenticationProvider cho local login.
  - oauth2Login cho Google.
  - oauth2ResourceServer(jwt) cho bearer token.

## 9) Diem mo rong de lam tiep
- Tach Access Token va Refresh Token.
- Them endpoint refresh token.
- Them revoke all sessions theo user.
- Them business rate limit theo user cho cac flow nhay cam.
- Chuyen token redirect sau oauth2 sang cookie httpOnly secure neu frontend cung domain.
- Bo sung integration test cho logout endpoint va oauth2 success flow.

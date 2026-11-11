# Danh sách API theo vendor / service

Tài liệu này liệt kê toàn bộ API HTTP hiện có trong repo, nhóm theo service (vendor).
Nguồn: các `*Controller.java`, `SecurityConfig.java`, `gateway-service/application.yml` và WireMock mappings.

## Tổng quan

| Vendor / Service | Cổng | Vai trò | Gọi qua gateway | Số API nghiệp vụ |
|---|---:|---|---|---:|
| `gateway-service` | 8080 | Cổng vào duy nhất, xác thực JWT, ép `Idempotency-Key` | — | 0 (chỉ định tuyến) |
| `auth-service` | 8081 | Đăng ký, đăng nhập, hồ sơ khách hàng, quản trị khách hàng | `/auth/**` | 6 |
| `ledger-service` | 8082 | Tài khoản, bút toán, chủ sở hữu số dư | `/accounts/**` | 7 |
| `transfer-service` | 8083 | Saga chuyển tiền, trạng thái giao dịch | `/transfers/**` | 4 |
| `fraud-service` | 8084 | Kiểm tra gian lận (hướng sự kiện) | Không có route | 0 |
| `notification-service` | 8085 | Thông báo đã lưu | `/notifications/**` | 1 |
| `wiremock` (đối tác) | 9090 | Mock partner bank, gọi nội bộ từ transfer-service | Không | 2 |

**Base URL khi dùng hệ thống:** `http://localhost:8080` (gateway).

**Xác thực:** mọi API (trừ `POST /auth/register`, `POST /auth/login` và actuator/swagger) đều cần
header `Authorization: Bearer <accessToken>`. Token lấy từ `POST /auth/login`.

**Vai trò:** claim `roles` trong JWT là `ROLE_USER` hoặc `ROLE_ADMIN` (cột `customer.role`).
Mọi API `/…/admin/**` cần `ROLE_ADMIN`, người dùng thường nhận `403`. Đăng ký luôn tạo `USER`;
tài khoản admin được auth-service tạo lúc khởi động từ `BANKING_ADMIN_EMAIL` / `BANKING_ADMIN_PASSWORD`
(docker compose dev: `admin@banking.local` / `admin12345`).

**JWT:** access token có hiệu lực **15 phút** (`expiresAt` trong `LoginResponse`).

**Định dạng lỗi:** RFC 7807 `ProblemDetail` với các trường `status`, `detail`, `code`; lỗi validation có thêm
`errors: [{field, message}]`. Gateway khi thiếu `Idempotency-Key` cũng trả cùng dạng
`{"status":400,"detail":"...","code":"IDEMPOTENCY_KEY_MISSING"}`. Client map `code` sang tiếng Việt.

| `code` | HTTP | Ý nghĩa |
|---|---:|---|
| `VALIDATION_FAILED` | 400 | Body/tham số/header không hợp lệ |
| `IDEMPOTENCY_KEY_MISSING` | 400 | Thiếu `Idempotency-Key` |
| `IDEMPOTENCY_KEY_REUSED` | 422 / 409 | Transfer: cùng key nhưng body khác (422); nạp tiền: key trùng bút toán khác (409) |
| `EMAIL_TAKEN` | 409 | Email đã đăng ký |
| `BAD_CREDENTIALS` | 401 | Sai email hoặc mật khẩu |
| `CUSTOMER_LOCKED` | 423 | Tài khoản khách hàng bị khoá |
| `TOO_MANY_ATTEMPTS` | 429 | Đăng nhập sai quá nhiều lần |
| `NOT_FOUND` | 404 | Không tồn tại hoặc không thuộc về người gọi |
| `SELF_LOCK` | 409 | Admin tự khoá chính mình |
| `SOURCE_ACCOUNT_INVALID` | 422 | Tài khoản nguồn không hợp lệ |
| `ACCOUNT_NOT_FOUND` | 422 | Không tìm thấy tài khoản nhận |
| `CURRENCY_MISMATCH` | 422 | Hai tài khoản khác loại tiền |
| `SELF_TRANSFER_NOT_ALLOWED` | 422 | Chuyển cho chính tài khoản nguồn |
| `ACCOUNT_LIMIT_REACHED` | 409 | Khách hàng đã có tài khoản (mỗi khách 1 tài khoản) |
| `UPSTREAM_UNAVAILABLE` | 503 | auth/ledger không phản hồi (timeout kết nối 2s, đọc 5s) |

**Phân trang:** các API danh sách nhận `?page` (bắt đầu từ 0) và `&size` (mặc định 50, tối đa 200);
tham số `sort` bị bỏ qua. Kết quả vẫn là mảng JSON.

**Tiền tệ:** `amount` / `balance` là `BIGINT` đơn vị nhỏ nhất (minor units), không dùng số thực.

**Swagger từng service:** `http://localhost:808{1..5}/swagger-ui.html` (OpenAPI tại `/v3/api-docs`).

---

## 1. API Gateway — `gateway-service` (:8080)

Gateway không có API nghiệp vụ; nó định tuyến và áp chính sách chung.

| Method | Path | Đích | Ghi chú |
|---|---|---|---|
| `*` | `/auth/**` | `auth-service` | Công khai, không cần JWT |
| `*` | `/accounts/internal/**` | — | Luôn trả **404** để chặn truy cập nội bộ từ bên ngoài |
| `*` | `/accounts/**` | `ledger-service` | Cần JWT |
| `*` | `/transfers/**` | `transfer-service` | Cần JWT; `POST` bắt buộc có `Idempotency-Key` |
| `*` | `/notifications/**` | `notification-service` | Cần JWT |
| `GET` | `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus` | gateway | Công khai |

Quy tắc của gateway:

- Thiếu hoặc để trống `Idempotency-Key` trên `POST /transfers` → **400**
  `{"status":400,"detail":"Missing or empty Idempotency-Key header","code":"IDEMPOTENCY_KEY_MISSING"}`.
- Không có JWT hợp lệ → **401**.
- Gateway chuyển tiếp nguyên token của người dùng xuống service phía sau.

---

## 2. Auth Service — `auth-service` (:8081)

### `POST /auth/register` — Đăng ký

- **Auth:** công khai
- **Request body:**

```json
{
  "email": "customer@example.com",
  "password": "matkhau123",
  "fullName": "Nguyen Van A"
}
```

Ràng buộc: `email` hợp lệ, `password` 8–100 ký tự, `fullName` tối đa 255 ký tự.

- **Response `201 Created`:** `LoginResponse` (như `POST /auth/login`, đăng ký xong đăng nhập luôn).
- Header `Location: /auth/me`
- **Lỗi:** `400` dữ liệu không hợp lệ, `409` email đã tồn tại.

### `POST /auth/login` — Đăng nhập

- **Auth:** công khai
- **Request body:**

```json
{ "email": "customer@example.com", "password": "matkhau123" }
```

- **Response `200 OK`:**

```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIs...",
  "tokenType": "Bearer",
  "expiresAt": "2026-10-31T08:00:00Z",
  "customer": {
    "id": "3f1c...",
    "email": "customer@example.com",
    "fullName": "Nguyen Van A",
    "status": "ACTIVE",
    "createdAt": "2026-10-31T07:00:00Z"
  }
}
```

- **Lỗi:** `401` sai thông tin đăng nhập, `423` tài khoản bị khóa, `429` thử sai quá nhiều lần (`TOO_MANY_ATTEMPTS`).

### `GET /auth/me` — Hồ sơ hiện tại

- **Auth:** Bearer JWT
- **Response `200 OK`:** cùng cấu trúc `CustomerResponse` như trên.
- **Lỗi:** `401` thiếu/sai token, `404` không tìm thấy khách hàng.

### `GET /auth/admin/customers` — Danh sách khách hàng (ADMIN)

- **Auth:** Bearer JWT có `ROLE_ADMIN`
- **Query:** `?q` lọc theo email / họ tên; `?page`, `&size` (tối đa 200).
- **Response `200 OK`:** mảng `CustomerResponse` (có trường `role`), mới nhất trước.
- **Lỗi:** `403` không phải admin.

### `POST /auth/admin/customers/{id}/lock` · `POST /auth/admin/customers/{id}/unlock` — Khoá / mở khoá (ADMIN)

- **Auth:** Bearer JWT có `ROLE_ADMIN`
- **Response `200 OK`:** `CustomerResponse` với `status` mới (`LOCKED` / `ACTIVE`).
- **Lỗi:** `403`, `404`, `409` khi admin tự khoá chính mình.
- Khoá chỉ chặn đăng nhập mới; JWT đã cấp còn hiệu lực tới khi hết hạn.

---

## 3. Ledger Service — `ledger-service` (:8082)

Ledger là nơi duy nhất giữ số dư tài khoản.

### `POST /accounts` — Mở tài khoản

- **Auth:** Bearer JWT
- **Request body:**

```json
{ "currency": "VND" }
```

`currency` là `VND`, `USD` hoặc `EUR`. Mỗi khách hàng tối đa **10** tài khoản.

- **Response `201 Created`:**

```json
{
  "id": "0b7d...",
  "accountNo": "1000000001",
  "customerId": "3f1c...",
  "currency": "VND",
  "balance": 0,
  "createdAt": "2026-10-31T07:00:00Z"
}
```

- Header `Location: /accounts/{id}`
- **Lỗi:** `400` currency không hợp lệ, `401` thiếu token, `409` khách hàng đã có tài khoản (`ACCOUNT_LIMIT_REACHED`, mỗi khách 1 tài khoản).

### `GET /accounts` — Tài khoản của tôi

- **Auth:** Bearer JWT
- **Query:** `?page`, `&size`.
- **Response `200 OK`:** mảng `AccountResponse` của đúng khách hàng đang đăng nhập.

### `GET /accounts/{id}` — Chi tiết tài khoản

- **Auth:** Bearer JWT, phải là chủ tài khoản
- **Response `200 OK`:** một `AccountResponse`
- **Lỗi:** `403` không phải chủ sở hữu, `404` không tìm thấy.

### `GET /accounts/{id}/entries` — Bút toán của tài khoản

- **Auth:** Bearer JWT, phải là chủ tài khoản
- **Response `200 OK`:**

```json
[
  {
    "id": "e1a2...",
    "transferId": "7c9f...",
    "accountId": "0b7d...",
    "direction": "DEBIT",
    "amount": 250000,
    "createdAt": "2026-10-31T07:01:00Z"
  }
]
```

- Hỗ trợ `?page`, `&size`; sắp xếp mới nhất trước; `direction` là `DEBIT` hoặc `CREDIT`.
- **Lỗi:** `403`, `404`.

### `GET /accounts/admin` — Tất cả tài khoản (ADMIN)

- **Auth:** Bearer JWT có `ROLE_ADMIN`
- **Query:** `?q` lọc theo số tài khoản; `?page`, `&size` (tối đa 200).
- **Response `200 OK`:** mảng `AccountResponse`, mới nhất trước.
- **Lỗi:** `403`.

### `POST /accounts/admin/{id}/deposit` — Nạp tiền (ADMIN)

- **Auth:** Bearer JWT có `ROLE_ADMIN`
- **Header bắt buộc:** `Idempotency-Key: <UUID>`
- **Request:** `{"amount": 10000000}` (minor units, `1..1000000000000`)
- **Response `200 OK`:** `AccountResponse` với số dư mới. Ghi một bút toán `CREDIT` có `transferId` là `Idempotency-Key`;
  gửi lại cùng key cho cùng tài khoản trả về tài khoản, không nạp thêm.
- **Lỗi:** `400` (thiếu/sai key, body), `403`, `404`, `409` key đã dùng cho bút toán khác (`IDEMPOTENCY_KEY_REUSED`).

### `GET /accounts/internal/{id}` — Tra cứu nội bộ

- **Auth:** Bearer JWT (service-to-service, transfer-service chuyển tiếp token người dùng)
- **Response `200 OK`:** một `AccountResponse`
- **Quan trọng:** gateway luôn trả **404** cho path này; chỉ gọi được trực tiếp trong mạng nội bộ
  (ví dụ `http://ledger-service:8082`).

---

## 4. Transfer Service — `transfer-service` (:8083)

### `POST /transfers` — Tạo lệnh chuyển tiền

- **Auth:** Bearer JWT
- **Header bắt buộc:** `Idempotency-Key: <chuỗi duy nhất>` (gateway và service đều kiểm tra); key có phạm vi theo từng khách hàng.
- **Request body:**

```json
{
  "sourceAccountId": "0b7d...",
  "destAccountId": "9f3a...",
  "amount": 250000,
  "currency": "VND"
}
```

Chỉ định đúng **một** trong `destAccountId` hoặc `destAccountNo` (dạng `^VN\d{10}$`).

- **Response `202 Accepted`** khi saga mới được nhận:

```json
{
  "id": "7c9f...",
  "sourceAccountId": "0b7d...",
  "destAccountId": "9f3a...",
  "sourceAccountNo": "VN0000000001",
  "destAccountNo": "VN0000000002",
  "amount": 250000,
  "currency": "VND",
  "status": "PENDING",
  "failureReason": null,
  "createdAt": "2026-10-31T07:01:00Z",
  "updatedAt": "2026-10-31T07:01:00Z",
  "direction": "OUT"
}
```

`direction`: `OUT` (người gửi / admin) hoặc `IN` (người nhận xem giao dịch đến).

- **Response `200 OK`** khi gửi lại cùng `Idempotency-Key` và cùng body: trả về giao dịch cũ, không tạo saga mới.
- **Lỗi:** `400` thiếu header/body không hợp lệ, `401`, `422` tài khoản không hợp lệ
  (`SOURCE_ACCOUNT_INVALID`, `ACCOUNT_NOT_FOUND`, `CURRENCY_MISMATCH`, `SELF_TRANSFER_NOT_ALLOWED`) hoặc cùng key khác body
  (`IDEMPOTENCY_KEY_REUSED`), `503` auth/ledger không phản hồi (`UPSTREAM_UNAVAILABLE`).

Trạng thái cuối cùng: `COMPLETED` hoặc `FAILED` kèm `failureReason`
(`FRAUD_REJECTED`, `INSUFFICIENT_FUNDS`, `PARTNER_TIMEOUT`, ...).

### `GET /transfers/{id}` — Chi tiết giao dịch

- **Auth:** Bearer JWT, phải là chủ giao dịch
- **Response `200 OK`:** một `TransferResponse`
- **Lỗi:** `401`, `404` (không tồn tại hoặc không thuộc khách hàng).

### `GET /transfers` — Giao dịch của tôi

- **Auth:** Bearer JWT
- **Query:** `?page`, `&size`.
- **Response `200 OK`:** mảng `TransferResponse`: giao dịch tôi gửi (`direction: OUT`) và giao dịch đến đã `COMPLETED` (`direction: IN`).

### `GET /transfers/admin` — Tất cả giao dịch (ADMIN)

- **Auth:** Bearer JWT có `ROLE_ADMIN`
- **Query:** `?page`, `&size` (tối đa 200).
- **Response `200 OK`:** mảng `TransferResponse`, mới nhất trước.
- **Lỗi:** `403`.

---

## 5. Notification Service — `notification-service` (:8085)

### `GET /notifications` — Thông báo của tôi

- **Auth:** Bearer JWT
- **Response `200 OK`:**

```json
[
  {
    "id": "d4e5...",
    "customerId": "3f1c...",
    "transferId": "7c9f...",
    "channel": "EMAIL",
    "recipient": "customer@example.com",
    "title": "Chuyển tiền thành công",
    "content": "...",
    "status": "SENT",
    "createdAt": "2026-10-31T07:01:05Z"
  }
]
```

- `channel`: `EMAIL` hoặc `SMS`; `status`: trạng thái gửi của bản ghi thông báo.

---

## 6. Fraud Service — `fraud-service` (:8084)

Service này **không expose REST API nghiệp vụ**. Nó giao tiếp hoàn toàn qua Kafka:

| Hướng | Topic | Ý nghĩa |
|---|---|---|
| Consume | `transfer.requested` | Nhận yêu cầu chuyển tiền để chấm điểm |
| Produce | `fraud.decided` | Kết quả `APPROVED` / `REJECTED` |

Chỉ có các endpoint vận hành: `/actuator/health`, `/actuator/info`, `/actuator/prometheus`.

---

## 7. Partner Bank mock — WireMock (:9090)

Đây là API của **vendor bên ngoài** (đối tác ngân hàng), transfer-service gọi nội bộ;
không đi qua gateway và không dành cho client.

### `POST /partner/v1/transfers` — Chuyển tiền sang đối tác (thành công)

- **Request body** (do `PartnerBankClient` gửi; account number ở đây thực tế là account id):

```json
{
  "idempotencyKey": "7c9f...",
  "sourceAccountNo": "0b7d...",
  "destAccountNo": "9f3a...",
  "amount": 250000,
  "currency": "VND"
}
```

- **Response `200 OK`:**

```json
{ "status": "ACCEPTED", "reference": "A1B2C3D4E5F6" }
```

### `POST /partner-slow/v1/transfers` — Bản chậm để test timeout

- Giống endpoint trên nhưng trễ cố định **5 giây**, trong khi transfer-service có read timeout **3 giây**.
- Dùng để demo `PARTNER_TIMEOUT` và cơ chế ledger reversal:
  `BANKING_PARTNER_EXECUTE_PATH=/partner-slow/v1/transfers`.

---

## Ví dụ gọi nhanh toàn luồng

```bash
BASE=http://localhost:8080

# 1) Đăng ký (bỏ qua nếu đã có tài khoản)
curl -s -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.com","password":"matkhau123","fullName":"Demo User"}'

# 2) Đăng nhập, lấy token
TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.com","password":"matkhau123"}' | jq -r .accessToken)

# 3) Mở 2 tài khoản
SRC=$(curl -s -X POST "$BASE/accounts" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"currency":"VND"}' | jq -r .id)
DST=$(curl -s -X POST "$BASE/accounts" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"currency":"VND"}' | jq -r .id)

# 3b) Nạp tiền thử cho tài khoản nguồn (khi backend chạy bằng docker compose)
docker compose -f server/docker/docker-compose.yml exec -T postgres \
  psql -U postgres -d ledger_db -c "UPDATE account SET balance = 10000000 WHERE id = '$SRC';"

# 4) Chuyển tiền (Idempotency-Key bắt buộc)
curl -s -X POST "$BASE/transfers" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"sourceAccountId\":\"$SRC\",\"destAccountId\":\"$DST\",\"amount\":250000,\"currency\":\"VND\"}"

# 5) Kiểm tra trạng thái và thông báo
curl -s "$BASE/transfers" -H "Authorization: Bearer $TOKEN"
curl -s "$BASE/notifications" -H "Authorization: Bearer $TOKEN"
```

> Ghi chú: tài khoản mới có số dư 0. Để test luồng thành công, nạp tiền thử bằng lệnh trong
> `client/src/main.js` (`seedCommand`) hoặc dùng kịch bản test console.

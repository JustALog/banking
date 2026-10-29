# Danh sách API theo vendor / service

Tài liệu này liệt kê toàn bộ API HTTP hiện có trong repo, nhóm theo service (vendor).
Nguồn: các `*Controller.java`, `SecurityConfig.java`, `gateway-service/application.yml` và WireMock mappings.

## Tổng quan

| Vendor / Service | Cổng | Vai trò | Gọi qua gateway | Số API nghiệp vụ |
|---|---:|---|---|---:|
| `gateway-service` | 8080 | Cổng vào duy nhất, xác thực JWT, ép `Idempotency-Key` | — | 0 (chỉ định tuyến) |
| `auth-service` | 8081 | Đăng ký, đăng nhập, hồ sơ khách hàng | `/auth/**` | 3 |
| `ledger-service` | 8082 | Tài khoản, bút toán, chủ sở hữu số dư | `/accounts/**` | 5 |
| `transfer-service` | 8083 | Saga chuyển tiền, trạng thái giao dịch | `/transfers/**` | 3 |
| `fraud-service` | 8084 | Kiểm tra gian lận (hướng sự kiện) | Không có route | 0 |
| `notification-service` | 8085 | Thông báo đã lưu | `/notifications/**` | 1 |
| `wiremock` (đối tác) | 9090 | Mock partner bank, gọi nội bộ từ transfer-service | Không | 2 |

**Base URL khi dùng hệ thống:** `http://localhost:8080` (gateway).

**Xác thực:** mọi API (trừ `POST /auth/register`, `POST /auth/login` và actuator/swagger) đều cần
header `Authorization: Bearer <accessToken>`. Token lấy từ `POST /auth/login`.

**Định dạng lỗi:** RFC 7807 `ProblemDetail` với các trường `status`, `detail`, có thể có `errors[]`.
Riêng gateway trả `{"error": "..."}` khi thiếu `Idempotency-Key`.

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
  `{"error":"Missing or empty Idempotency-Key header"}`.
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

- **Response `201 Created`:**

```json
{
  "id": "3f1c...",
  "email": "customer@example.com",
  "fullName": "Nguyen Van A",
  "status": "ACTIVE",
  "createdAt": "2026-10-31T07:00:00Z"
}
```

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

- **Lỗi:** `401` sai thông tin đăng nhập, `423` tài khoản bị khóa.

### `GET /auth/me` — Hồ sơ hiện tại

- **Auth:** Bearer JWT
- **Response `200 OK`:** cùng cấu trúc `CustomerResponse` như trên.
- **Lỗi:** `401` thiếu/sai token, `404` không tìm thấy khách hàng.

---

## 3. Ledger Service — `ledger-service` (:8082)

Ledger là nơi duy nhất giữ số dư tài khoản.

### `POST /accounts` — Mở tài khoản

- **Auth:** Bearer JWT
- **Request body:**

```json
{ "currency": "VND" }
```

`currency` phải là 3 chữ in hoa (regex `[A-Z]{3}`).

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
- **Lỗi:** `400` currency không hợp lệ, `401` thiếu token.

### `GET /accounts` — Tài khoản của tôi

- **Auth:** Bearer JWT
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

- Sắp xếp mới nhất trước; `direction` là `DEBIT` hoặc `CREDIT`.
- **Lỗi:** `403`, `404`.

### `GET /accounts/internal/{id}` — Tra cứu nội bộ

- **Auth:** Bearer JWT (service-to-service, transfer-service chuyển tiếp token người dùng)
- **Response `200 OK`:** một `AccountResponse`
- **Quan trọng:** gateway luôn trả **404** cho path này; chỉ gọi được trực tiếp trong mạng nội bộ
  (ví dụ `http://ledger-service:8082`).

---

## 4. Transfer Service — `transfer-service` (:8083)

### `POST /transfers` — Tạo lệnh chuyển tiền

- **Auth:** Bearer JWT
- **Header bắt buộc:** `Idempotency-Key: <chuỗi duy nhất>` (gateway và service đều kiểm tra)
- **Request body:**

```json
{
  "sourceAccountId": "0b7d...",
  "destAccountId": "9f3a...",
  "amount": 250000,
  "currency": "VND"
}
```

- **Response `202 Accepted`** khi saga mới được nhận:

```json
{
  "id": "7c9f...",
  "sourceAccountId": "0b7d...",
  "destAccountId": "9f3a...",
  "amount": 250000,
  "currency": "VND",
  "status": "PENDING",
  "failureReason": null,
  "createdAt": "2026-10-31T07:01:00Z",
  "updatedAt": "2026-10-31T07:01:00Z"
}
```

- **Response `200 OK`** khi gửi lại cùng `Idempotency-Key`: trả về giao dịch cũ, không tạo saga mới.
- **Lỗi:** `400` thiếu header/body không hợp lệ, `401`, `422` tài khoản nguồn/đích không hợp lệ.

Trạng thái cuối cùng: `COMPLETED` hoặc `FAILED` kèm `failureReason`
(`FRAUD_REJECTED`, `INSUFFICIENT_FUNDS`, `PARTNER_TIMEOUT`, ...).

### `GET /transfers/{id}` — Chi tiết giao dịch

- **Auth:** Bearer JWT, phải là chủ giao dịch
- **Response `200 OK`:** một `TransferResponse`
- **Lỗi:** `401`, `404` (không tồn tại hoặc không thuộc khách hàng).

### `GET /transfers` — Giao dịch của tôi

- **Auth:** Bearer JWT
- **Response `200 OK`:** mảng `TransferResponse`.

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

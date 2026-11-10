# Banking Platform

[English](README.md) · **Tiếng Việt**

Nền tảng ngân hàng và thanh toán phân tán: sáu service Spring Boot sau một API gateway, saga chuyển tiền hướng sự kiện trên Kafka và website React. Chạy cục bộ bằng Docker Compose hoặc trên Kubernetes (kind).

Thiết kế nằm trong [Architect_diagram.drawio](Architect_diagram.drawio). Tài liệu HTTP API nằm trong [API_LIST.md](API_LIST.md) (tiếng Việt).

## Điểm nổi bật

- **Saga chuyển tiền với transactional outbox.** Trạng thái giao dịch và sự kiện `transfer.requested` được commit trong cùng một transaction DB. Debezium CDC đẩy sự kiện lên Kafka, nên không có dual write.
- **Ledger là nơi duy nhất sở hữu số dư.** Ghi sổ kép với khóa dòng theo thứ tự cố định, và tự động hoàn tác nếu ngân hàng đối tác lỗi sau khi đã ghi sổ.
- **Luật chống gian lận:** ngưỡng số tiền cộng với velocity theo cửa sổ trượt (số lượng và tổng giá trị). Mọi lần đánh giá đều được lưu.
- **Gateway** xác thực JWT và bắt buộc header `Idempotency-Key` khi chuyển tiền.
- **Khả năng chịu lỗi:** gọi đối tác có timeout 3 s và circuit breaker. Bản ghi Kafka lỗi được thử lại 3 lần rồi chuyển sang `transfer.dlq`.
- **Vận hành:** bản triển khai Kubernetes có Kafka và Postgres HA, backup PITR, metrics Prometheus/Grafana và log EFK với một trace id cho mỗi giao dịch.

## Cấu trúc thư mục

```
client/                  Website React 19 + React Router 7 + Vite (chỉ gọi gateway)
server/
  common-events/         Tên topic Kafka và event record (hợp đồng dùng chung)
  gateway-service/ :8080 Spring Cloud Gateway: JWT resource server, filter Idempotency-Key
  auth-service/    :8081 đăng ký/đăng nhập, cấp JWT RS256          (customer_db)
  ledger-service/  :8082 tài khoản, ghi sổ kép, hoàn tác           (ledger_db)
  transfer-service/:8083 điều phối saga + outbox                   (transfer_db)
  fraud-service/   :8084 luật ngưỡng + velocity, nhật ký kiểm toán (fraud_db)
  notification-service/ :8085 nhận kết quả, email/SMS giả lập      (notify_db)
  docker/                Compose: Postgres, Kafka (KRaft), Debezium Connect, WireMock đối tác, Kafka UI
  k8s/                   cụm kind, install.sh, Helm chart, values chart bên thứ ba, logging, backup
  scripts/               demo.sh (khởi chạy + smoke test), smoke.sh (kiểm tra end-to-end)
```

## Chạy nhanh (Docker Compose)

Yêu cầu JDK 21+, Docker với Compose v2, bash, curl, và python3 hoặc node (smoke script dùng).

```bash
cd server
bash scripts/demo.sh   # tạo khóa → compose up --build --wait → connector Debezium → smoke test
```

Lần đầu build sáu image và mất vài phút. Stack được giới hạn bộ nhớ khoảng 4 GB.
Các lần sau: `bash scripts/demo.sh --fast` dùng lại image và bỏ qua smoke test.

### Tài khoản thử nghiệm theo vai trò

`bash scripts/demo.sh` tạo các tài khoản này: script khởi động auth-service với `BANKING_SEED_DEMO_ACCOUNTS=true` (mặc định tắt trong compose), cờ này tạo mỗi vai trò một tài khoản (mật khẩu `Test@1234`). `demo.sh` đăng nhập từng tài khoản và in claim `roles`.

| Email | Vai trò (claim JWT) |
|---|---|
| `user@test.local` | `ROLE_USER` |
| `staff@test.local` | `ROLE_STAFF` |
| `admin@test.local` | `ROLE_ADMIN` |

Đăng nhập qua `POST /auth/login`. `/auth/register` luôn tạo `USER`. Hiện chưa endpoint nào kiểm tra `STAFF`/`ADMIN`; thêm `hasRole(...)` vào `SecurityConfig` của service để dùng. Chỉ dành cho demo: không bao giờ bật cờ này ở production.

| Thành phần | Địa chỉ |
|---|---|
| API (qua gateway) | http://localhost:8080 |
| Swagger UI mỗi service | http://localhost:808{1..5}/swagger-ui.html |
| Kafka UI (`--profile tools`) | http://localhost:8090 |
| Postgres (cổng host) | localhost:5433 |
| Kafka Connect REST | http://localhost:8086 |

```bash
docker compose -f docker/docker-compose.yml --profile tools up -d    # bật Kafka UI
docker compose -f docker/docker-compose.yml down                     # dừng (thêm -v để xóa dữ liệu)
docker stats --no-stream                                             # bộ nhớ từng container
```

Để phát triển một service từ IDE, chạy `docker compose stop <service>` rồi `mvn -pl <service> spring-boot:run`. Giá trị mặc định trong `application.yml` trỏ về localhost. Trỏ DB tới cổng đã publish, ví dụ `BANKING_DB_URL=jdbc:postgresql://localhost:5433/ledger_db`.

Test backend: `cd server && bash docker/keys/generate-keys.sh && mvn verify`. Đây là lệnh CI chạy ([backend-ci.yml](.github/workflows/backend-ci.yml)).

## Website

```bash
cd client
npm install
npm run dev    # http://localhost:5173; đặt GATEWAY_URL để đổi mặc định http://localhost:8080
npm test       # unit test định dạng/validate (node --test)
```

Hãy chạy backend trước. Thiết kế giao diện được mô tả trong [client/DESIGN.md](client/DESIGN.md).

- **Công khai:** trang giới thiệu, đăng nhập, đăng ký (mở sẵn một tài khoản VND đầu tiên).
- **Khách hàng (`/app`):** số dư theo loại tiền, tài khoản, sao kê, chuyển tiền 3 bước (nhập thông tin, xem lại, biên lai trực tiếp), lịch sử, thông báo, hồ sơ.
- **Quản trị (`/admin`, `ROLE_ADMIN`):** khách hàng (tìm kiếm, khóa/mở khóa), toàn bộ tài khoản (nạp tiền), toàn bộ giao dịch. Với Compose, đăng nhập bằng `admin@test.local` / `Test@1234` (xem [Tài khoản thử nghiệm theo vai trò](#tài-khoản-thử-nghiệm-theo-vai-trò)).

Phiên (JWT và hồ sơ) được lưu trong `sessionStorage`. Tải lại trang vẫn giữ đăng nhập, đóng tab thì đăng xuất.

## Luồng một giao dịch chuyển tiền

1. `POST /transfers` kèm `Idempotency-Key`. Gateway kiểm tra JWT và header.
2. Transfer-service xác thực hai tài khoản với ledger. Trong một transaction, nó ghi dòng `transfer` (PENDING) và một dòng outbox `transfer.requested`, rồi trả 202.
3. Debezium đọc outbox từ WAL và đẩy `transfer.requested` lên Kafka.
4. Fraud-service áp dụng luật, lưu kết quả đánh giá và phát `fraud.decided` (APPROVED hoặc REJECTED).
5. Ledger-service khóa hai tài khoản theo thứ tự UUID đã sắp xếp rồi ghi nợ và có. Ràng buộc duy nhất `(transfer_id, account_id, direction)` đảm bảo idempotent. Nó phát `ledger.posted`, hoặc `ledger.rejected` khi không đủ tiền hoặc tài khoản không tồn tại.
6. Transfer-service gọi ngân hàng đối tác. Thành công thì đánh dấu COMPLETED và phát `transfer.completed`. Timeout hoặc lỗi thì đánh dấu FAILED và phát `transfer.failed`.
7. Nếu lỗi xảy ra sau khi đã ghi sổ, ledger-service nhận `transfer.failed` và ghi bút toán đảo: hoàn tiền cho nguồn và ghi nợ đích.
8. Notification-service lưu thông báo và giả lập email/SMS cho giao dịch hoàn tất và thất bại.

### Kịch bản lỗi

| Kịch bản | Cách kích hoạt | Kết quả mong đợi |
|---|---|---|
| Thiếu key | không có `Idempotency-Key` ở `POST /transfers` | 400 từ gateway |
| Gửi lại | dùng lại cùng `Idempotency-Key` | cùng transfer id, 200, không chạy saga lần hai |
| Ngưỡng gian lận | số tiền vượt ngưỡng | FAILED / FRAUD_REJECTED, số dư không đổi |
| Velocity gian lận | hơn 5 giao dịch trong 60 s | FAILED / FRAUD_REJECTED từ giao dịch thứ 6. Chi tiết luật chỉ có trong `fraud_db.fraud_evaluation`. |
| Không đủ tiền | số tiền lớn hơn số dư | FAILED / INSUFFICIENT_FUNDS, số dư không đổi |
| Đối tác timeout | `BANKING_PARTNER_EXECUTE_PATH=/partner-slow/v1/transfers` (trễ 5 s so với timeout 3 s) | FAILED / PARTNER_TIMEOUT, sau đó ledger tự hoàn tác |
| Message lỗi | event sai định dạng trên topic đang nhận | thử lại 3 lần rồi chuyển sang `transfer.dlq` |

Để thử đối tác timeout:

```bash
BANKING_PARTNER_EXECUTE_PATH=/partner-slow/v1/transfers \
  docker compose -f docker/docker-compose.yml up -d transfer-service
```

## Quyết định thiết kế

- **Tiền** là `BIGINT` theo đơn vị nhỏ nhất. Không dùng số thực ở bất kỳ đâu.
- **Kafka:** producer dùng `acks=all` với idempotence. Consumer đọc `read_committed`.
- **Xác thực:** mọi service là OAuth2 resource server và kiểm tra bằng khóa công khai RS256.
- **Đồng thời:** ledger kết hợp khóa lạc quan `@Version` với khóa bi quan theo dòng khi ghi sổ.
- **Truy vết:** một trace id đi theo giao dịch qua HTTP, Kafka và bước outbox (cột `outbox_event.traceparent`, Debezium sao chép vào header Kafka). Trong Kibana, lọc bằng `app.traceId : "<id>"`. Các dòng ở DB khác nhau liên kết qua `transfer_id`.
- **Chưa triển khai:** giới hạn tốc độ (rate limiting) ở gateway, vì limiter của Spring Cloud Gateway cần Redis.

## Kubernetes (kind)

Yêu cầu docker, kind, kubectl và helm.

```bash
cd server
bash k8s/install.sh                  # profile đầy đủ, ~15 phút lần đầu, khoảng 9 GB RAM (tối đa 10 GB)
PROFILE=demo bash k8s/install.sh     # profile ít bộ nhớ, khoảng 5 GB
```

`install.sh` có thể chạy lại và in ra các URL cùng lệnh smoke test ở cuối. Không hỗ trợ đổi profile trên cụm đã có, vì Strimzi không thể thu nhỏ quorum KRaft. Hãy chạy `kind delete cluster --name banking` trước.

`PROFILE=demo` giữ nguyên kiến trúc nhưng ít bản sao hơn: 1 broker Kafka, 1 Postgres không có standby, không backup, không có EFK, Prometheus rút gọn. Phần ghi đè nằm ở `k8s/values/banking-demo.yaml` và `k8s/values/kube-prometheus-stack-demo.yaml`.

| Thành phần | Chạy gì |
|---|---|
| Ingress | ingress-nginx → gateway tại http://localhost |
| Services | mỗi service 1 replica. HPA đưa gateway lên 2 và fraud lên 3. PDB `maxUnavailable: 1`. Log JSON, root FS chỉ đọc, chạy non-root. |
| NetworkPolicy | Mỗi service chỉ nhận từ các bên gọi được liệt kê và Prometheus. Gateway chỉ nhận từ namespace ingress. |
| Kafka | Strimzi KRaft, 3 broker, RF=3, `min.insync.replicas=2`, topic khai báo bằng CR `KafkaTopic` |
| Postgres | CloudNativePG primary + standby, mỗi service một role và database, `wal_level=logical` |
| CDC | Debezium Connect cùng một job upsert connector outbox |
| Backup | Barman Cloud plugin: WAL liên tục và base backup hằng ngày lên SeaweedFS S3, giữ 7 ngày. Ví dụ PITR: `k8s/backup/restore-pitr.yaml` |
| Metrics | kube-prometheus-stack, ServiceMonitor cho từng service, dashboard Grafana "Banking Platform". `kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80` |
| Logs | Fluent Bit → ECK Elasticsearch → Kibana (index `banking-*`). `kubectl -n logging port-forward svc/banking-kb-kb-http 5601` |

### Giới hạn đã biết

- SeaweedFS thay cho MinIO và chạy ngay trong cụm. Phù hợp để demo nhưng không phải backup off-site thực sự.
- ingress-nginx đã ngừng phát triển ở upstream. Đối tượng Ingress là chuẩn, nên đổi controller chỉ là thay đổi values.
- **CDC khi Postgres failover** dựa vào failover slot của PG17+.
  - Slot `debezium` được tạo sẵn với `failover=true`, CNPG sao chép sang standby, heartbeat 10 s giữ slot tiến lên, và một sidecar janitor xóa slot cũ trên primary cũ.
  - Sau failover, standby mới cần khoảng 2-3 phút để đồng bộ lại slot. Failover lần hai trong khoảng này có thể làm mất dòng outbox.
  - Khôi phục an toàn vì mọi consumer đều idempotent. Trên `transfer_db`, phát lại request của các saga bị kẹt:

    ```sql
    INSERT INTO outbox_event (id, aggregatetype, aggregateid, type, payload, created_at, traceparent)
    SELECT gen_random_uuid(), o.aggregatetype, o.aggregateid, o.type, o.payload, now(), o.traceparent
    FROM outbox_event o JOIN transfer t ON o.payload->>'transferId' = t.id::text
    WHERE t.status = 'PENDING' AND o.aggregatetype = 'transfer.requested';
    ```

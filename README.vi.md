# Banking Platform — Nền tảng lõi, các Service & Kubernetes (Round 3)

[English](README.md) · **Tiếng Việt**

Nền tảng ngân hàng và thanh toán phân tán theo `Architect_diagram.drawio`.
- **Round 1**: Luồng chuyển tiền lõi: Auth/Customer → Transfer (saga + outbox + Debezium CDC) → Fraud (stub) → Ledger (chủ sở hữu duy nhất của số dư) → gọi Partner Bank (Resilience4j).
- **Round 2**: **API Gateway** (Spring Cloud Gateway, xác thực JWT, bắt buộc Idempotency-Key), **Notification Service** (`notify_db`, nhận sự kiện hoàn tất/thất bại, giả lập Email/SMS), **Fraud Service có lưu trữ và luật velocity thật** (`fraud_db`), và **bù trừ hoàn tác Ledger** khi ngân hàng đối tác lỗi sau khi đã ghi sổ.
- **Round 3**: **Kubernetes** (kind, Helm, Kafka KRaft 3 broker bằng Strimzi, CloudNativePG primary + standby, NGINX Ingress, HPA/PDB/NetworkPolicy), **Backup** (Barman Cloud plugin → S3, hằng ngày + WAL liên tục, PITR), **Quan sát** (kube-prometheus-stack + dashboard Grafana, Fluent Bit → ECK Elasticsearch/Kibana).
- **Test console**: giao diện Vite + TypeScript thuần trong `client/` để thử đăng nhập, tài khoản, chuyển tiền, thông báo, các kịch bản lỗi có sẵn và nhật ký API trực tiếp.

SPA React nằm trong `web/`; `client/` vẫn là test console TypeScript không dùng framework.

## Cấu trúc

```
client/                 test console trên trình duyệt (React 19 + Vite, JS thuần, :5173)
  DESIGN.md             token giao diện và ghi chú component
  src/main.jsx          điểm vào, định tuyến
  src/shell.jsx, ui.jsx, hooks.js   bố cục, component dùng chung, hook
  src/pages/            các trang public, user, transfer, admin
  src/gateway.js        wrapper fetch tới gateway, xử lý JWT
  src/format.js         định dạng tiền/ngày (có unit test)
  vite.config.js        dev server :5173, proxy các đường dẫn API tới gateway

web/                    website ngân hàng cho khách hàng + quản trị (React + TypeScript, :5174)
  src/types.ts          kiểu response API (phản chiếu các record *Response của service)
  src/gateway.ts        gọi gateway: header JWT, session storage, thông báo lỗi
  src/format.ts         định dạng tiền/ngày, văn bản trạng thái giao dịch và lý do thất bại
  src/components.tsx    shell, biên lai, hook dùng chung (useLoad, useSession)
  src/pages/            public.tsx, user.tsx, admin.tsx
  Dockerfile            build trên Node 14 → nginx (SPA fallback + proxy API)

server/
  common-events/        tên topic Kafka + event record (hợp đồng dùng chung)
  gateway-service/      :8080  Spring Cloud Gateway, JWT resource server, filter route
  auth-service/         :8081  đăng ký/đăng nhập, cấp JWT RS256 (customer_db)
  ledger-service/       :8082  REST tài khoản, ghi sổ kép, chủ sở hữu số dư, bù trừ hoàn tác (ledger_db)
  transfer-service/     :8083  điều phối saga + transactional outbox (transfer_db)
  fraud-service/        :8084  luật ngưỡng & velocity cửa sổ trượt, nhật ký kiểm toán (fraud_db)
  notification-service/ :8085  nhận sự kiện, lưu thông báo, giả lập email/SMS (notify_db)
  docker/               hạ tầng docker-compose: postgres (5 database), kafka (KRaft), debezium connect,
                        wiremock giả lập đối tác, kafka-ui (http://localhost:8090)
  scripts/smoke.sh      kiểm tra end-to-end: luồng thành công, idempotency, thông báo, định tuyến gateway
  Dockerfile            một công thức image cho mọi service (--build-arg MODULE=<service>)
  k8s/
    install.sh          cụm kind + operator + quan sát + image + chart `banking` (chạy lại được)
    helm/banking/       một chart: cả 6 service (lặp từ values.yaml), Kafka, Postgres, Debezium,
                        đối tác giả lập, backup, ServiceMonitor, dashboard Grafana
    values/             values cho chart bên thứ ba (kube-prometheus-stack, fluent-bit)
    logging/            ECK Elasticsearch + Kibana
    backup/             ví dụ khôi phục PITR
```

Danh sách API đầy đủ theo từng service (vendor), kèm ví dụ request/response: [API_LIST.md](API_LIST.md).

## Yêu cầu

- JDK 21+, Docker Desktop (hoặc docker compose v2 bất kỳ)
- Node.js `>=14.18.0` (CI kiểm thử 14.18 → 22), cùng npm, cho `web/` và `client/`
- bash + curl (smoke script dùng python3 hoặc node để đọc JSON, dùng cái nào cũng được)

## Chạy nhanh

Tất cả (hạ tầng + 6 service) chạy trong docker compose, giới hạn bộ nhớ (trần cứng ~4 GB):

```bash
cd server
bash scripts/demo.sh        # khóa → docker compose up --build --wait → connector Debezium → smoke test
```

Lần đầu build 6 image (vài phút); các lần sau dùng lại. `bash scripts/demo.sh --fast` bỏ qua bước build và smoke test, là cách nhanh nhất để dựng lại stack.

### Tài khoản thử nghiệm

`demo.sh` in kết quả đăng nhập bằng từng tài khoản dưới đây.

| Tài khoản | Vai trò | Được tạo thế nào |
|---|---|---|
| `admin@banking.local` / `admin12345` | `ROLE_ADMIN` | `AdminBootstrap` trong auth-service, từ `BANKING_ADMIN_EMAIL` / `BANKING_ADMIN_PASSWORD` trong docker compose. Bỏ trống thì không tạo admin. |
| `customer@banking.local` / `Customer12345` | `ROLE_USER` | `demo.sh` đăng ký qua `POST /auth/register` (bỏ qua nếu đã tồn tại). |

`/auth/register` luôn tạo `USER`; chỉ `AdminBootstrap` mới tạo được admin. Các thông tin đăng nhập này chỉ dành cho demo cục bộ, không dùng lại ở nơi nào thật.

Một số lệnh hữu ích:

```bash
docker stats --no-stream                                            # bộ nhớ từng container
docker compose -f docker/docker-compose.yml --profile tools up -d   # Kafka UI tại http://localhost:8090
BANKING_PARTNER_EXECUTE_PATH=/partner-slow/v1/transfers \
  docker compose -f docker/docker-compose.yml up -d transfer-service  # demo đối tác timeout + hoàn tác
docker compose -f docker/docker-compose.yml down                    # dừng (thêm -v để xóa dữ liệu)
```

Để phát triển một service từ IDE, chạy `docker compose stop <service>` rồi
`mvn -pl <service> spring-boot:run` — giá trị mặc định trong `application.yml` trỏ về localhost; Postgres được publish ở cổng host **5433**, nên cũng cần đặt ví dụ `BANKING_DB_URL=jdbc:postgresql://localhost:5433/ledger_db`.
Kafka Connect REST ở cổng host **8086**.

Swagger UI mỗi service: `http://localhost:808{1..5}/swagger-ui.html`.

Về test console trên trình duyệt, xem [Test console (client/)](#test-console-client).

## Tiền được chuyển và khôi phục thế nào

1. `POST /transfers` kèm `Idempotency-Key` → API Gateway xác thực JWT và đảm bảo có header `Idempotency-Key`.
2. Transfer-service xác thực hai tài khoản với ledger-service, rồi commit **một transaction nguyên tử**:
   dòng `transfer` (PENDING) + dòng `outbox_event` (`transfer.requested`). Trả về 202.
3. **Debezium Outbox Event Router** đọc WAL của `transfer_db` → phát `transfer.requested` (key = accountId nguồn).
4. **Fraud-service** đánh giá yêu cầu:
   - Kiểm tra ngưỡng số tiền của một giao dịch
   - Kiểm tra velocity tần suất (số giao dịch tối đa trong cửa sổ thời gian trượt)
   - Kiểm tra velocity khối lượng (tổng số tiền trong cửa sổ thời gian trượt)
   - Lưu toàn bộ đánh giá vào `fraud_db` và phát `fraud.decided` (`APPROVED` hoặc `REJECTED`).
5. **Ledger-service** nhận `fraud.decided`:
   - Khóa cả hai tài khoản (`SELECT … FOR UPDATE`, theo thứ tự UUID đã sắp xếp để tránh deadlock).
   - Ghi nợ/có với ràng buộc `UNIQUE(transfer_id, account_id, direction)`.
   - Phát `ledger.posted`. (Nếu không ghi được — không đủ tiền, tài khoản không tồn tại — phát `ledger.rejected`; transfer-service làm saga thất bại và ghi `transfer.failed` vào outbox.)
6. **Transfer-service** nhận `ledger.posted` → gọi đối tác giả lập với **read timeout HTTP 3 giây + Resilience4j CircuitBreaker**:
   - **Thành công**: giao dịch được đánh dấu COMPLETED → ghi `transfer.completed` vào outbox.
   - **Timeout / Lỗi**: giao dịch được đánh dấu FAILED → ghi `transfer.failed` vào outbox.
7. **Bù trừ (Hoàn tác Ledger)**:
   - Khi `transfer.failed` được phát sau khi đã ghi sổ, **ledger-service nhận sự kiện đó**.
   - Xác định các bút toán nợ và có ban đầu, khóa tài khoản, rồi thực hiện **bút toán đảo bù trừ**:
     hoàn tiền cho tài khoản nguồn (`CREDIT`) và ghi nợ tài khoản đích (`DEBIT`).
8. **Notification-service** nhận `transfer.completed` và `transfer.failed`:
   - Lưu bản ghi thông báo vào `notify_db`.
   - Giả lập gửi email/SMS cho khách hàng.

## Kiểm tra các đường lỗi

| Kịch bản | Cách làm | Kết quả mong đợi |
|---|---|---|
| Gateway thiếu Idempotency-Key | bỏ header `Idempotency-Key` ở `POST /transfers` | HTTP 400 Bad Request trả trực tiếp từ Gateway |
| Gửi lại idempotency | gửi lại cùng `Idempotency-Key` | cùng transfer id, HTTP 200, không tạo saga trùng |
| Từ chối do ngưỡng gian lận | số tiền chuyển > ngưỡng | FAILED / FRAUD_REJECTED, số dư không đổi |
| Từ chối do velocity gian lận | gửi > 5 giao dịch trong 60 s | FAILED / FRAUD_REJECTED từ giao dịch thứ 6; chi tiết luật (`VELOCITY_COUNT`, `TOO_MANY_TRANSFERS_IN_WINDOW: 6`) chỉ có trong `fraud_db.fraud_evaluation`, không hiển thị cho khách hàng |
| Không đủ tiền | chuyển nhiều hơn số dư | FAILED / INSUFFICIENT_FUNDS, số dư không đổi |
| Đối tác timeout + Hoàn tác | đặt `banking.partner.execute-path=/partner-slow/v1/transfers` (trễ 5 s so với read timeout 3 s) | FAILED / PARTNER_TIMEOUT; ledger-service nhận sự kiện lỗi và **tự động hoàn tác số dư đã ghi** |
| Message lỗi | event sai định dạng trên topic đang nhận | thử lại 3 lần → `transfer.dlq` |

## Ghi chú thiết kế

- **Tiền là `BIGINT` theo đơn vị nhỏ nhất** ở mọi nơi; không dùng số thực.
- Producer: `acks=all`, `enable.idempotence=true`; consumer đọc `read_committed`.
- Mọi service là OAuth2 Resource Server, kiểm tra bằng khóa công khai RS256.
- Ledger là chủ sở hữu duy nhất của số dư tài khoản; khóa lạc quan `@Version` cộng với khóa bi quan theo dòng khi ghi sổ.
- Mẫu transactional outbox tránh vấn đề dual-write.
- Gateway là điểm vào trung tâm: xác thực JWT và bắt buộc header `Idempotency-Key`. Chưa có rate limiting (limiter của Spring Cloud Gateway cần Redis).
- Mọi Kafka consumer thử lại một bản ghi lỗi 3 lần (cách nhau 1 s), rồi chuyển nó vào `transfer.dlq`.
- Một traceId đi theo giao dịch qua mọi service: `traceparent` chuẩn W3C qua HTTP và header Kafka
  (Spring Kafka observation), và qua bước outbox nhờ cột `outbox_event.traceparent`, được Debezium
  sao chép vào header Kafka. Kibana: lọc `app.traceId : "<id>"`. Các dòng ở DB khác nhau được liên kết
  bằng `transfer_id` (có trong transfer, ledger_entry, fraud_evaluation, notification).

## Kubernetes (kind)

Cần docker, kind, kubectl, helm. Ngân sách RAM cho cả node kind: **~9 GB ổn định, ≤10 GB** khi gateway/fraud scale tối đa (3 broker Kafka, Postgres primary+standby, Elasticsearch/Kibana, Prometheus), hoặc ~5 GB với `PROFILE=demo`. Mỗi pod đều có giới hạn bộ nhớ và các Spring service chạy với heap cố định 192 MB.

```bash
cd server
bash k8s/install.sh                  # profile đầy đủ: ~15 phút lần đầu; in URL và lệnh smoke test k8s
PROFILE=demo bash k8s/install.sh     # profile ít bộ nhớ, ~5 GB (xem bên dưới)
```

**`PROFILE=demo`** giữ nguyên kiến trúc (Strimzi, CNPG, Debezium, Ingress, HPA/PDB/NetworkPolicy,
Prometheus + dashboard Grafana) nhưng ít bản sao hơn: 1 broker Kafka (RF=1), 1 Postgres (không standby),
heap JVM nhỏ cố định, không backup (bỏ cert-manager/Barman/SeaweedFS), không EFK (dùng `kubectl logs`),
Prometheus rút gọn (không node-exporter/luật mặc định). Phần ghi đè nằm ở `k8s/values/banking-demo.yaml`
và `k8s/values/kube-prometheus-stack-demo.yaml`. Không hỗ trợ đổi profile trên cụm đã có
(Strimzi không thu nhỏ quorum KRaft tại chỗ được): hãy chạy `kind delete cluster --name banking` trước.

| Thành phần | Chạy gì | Ghi chú |
|---|---|---|
| Ingress | ingress-nginx → `gateway-service` | `http://localhost` (kind ánh xạ cổng host :80) |
| Services | mỗi service 1 replica; HPA theo CPU đưa gateway lên 2 và fraud lên 3 (các service khác giữ 1 để vừa ngân sách RAM), PDB `maxUnavailable: 1` | log JSON (profile `json`), root FS chỉ đọc, chạy non-root |
| NetworkPolicy | mỗi service chỉ nhận lưu lượng từ các bên gọi được liệt kê (`allowFrom` trong values) + Prometheus | gateway chỉ nhận từ namespace ingress-nginx |
| Kafka | Strimzi KRaft, 3 node vai trò kép, RF=3, `min.insync.replicas=2`, topic khai báo bằng CR `KafkaTopic` | Kafka Exporter cho consumer lag |
| Postgres | CNPG `banking-pg`, primary + standby; mỗi service một role + `<role>_db` (CR `Database`), mật khẩu ngẫu nhiên trong secret `pg-<role>` | `wal_level=logical` cho Debezium |
| CDC | Debezium Connect (Deployment thường) + Job sau khi cài để upsert connector outbox | mật khẩu DB đọc qua config provider `${env:...}` |
| Backup | Barman Cloud plugin: lưu trữ WAL liên tục + base backup hằng ngày (và một lần khi cài) lên SeaweedFS S3, giữ 7 ngày | PITR: `k8s/backup/restore-pitr.yaml` |
| Metrics | kube-prometheus-stack; ServiceMonitor cho từng service; dashboard Grafana "Banking Platform" (req/s, p95, 5xx, consumer lag, heap, DB pool) | `kubectl -n monitoring port-forward svc/monitoring-grafana 3000:80` |
| Logs | Fluent Bit (chỉ namespace `banking`) → ECK Elasticsearch → Kibana, index `banking-*` | `kubectl -n logging port-forward svc/banking-kb-kb-http 5601`, rồi tạo data view `banking-*` (trường thời gian `@timestamp`) |

Giới hạn đã biết:
- SeaweedFS thay cho MinIO (MinIO ngừng phát hành image) và nằm trong cùng cụm: phù hợp để demo, không phải backup off-site thực sự.
- CDC khi Postgres failover dựa vào failover slot của PG17+: slot `debezium` được tạo sẵn với
  `failover=true` (Debezium 2.7 không tự làm được), `synchronizeLogicalDecoding` của CNPG sao chép nó sang standby,
  heartbeat Debezium 10 s (bảng `debezium_heartbeat`) giữ cho nó luôn tiến lên để sẵn sàng đồng bộ, và một
  sidecar janitor xóa slot cũ mà primary cũ giữ lại khi nó tham gia lại. Ngay sau failover,
  standby mới cần ~2-3 phút để đồng bộ lại slot; failover lần hai trong khoảng này vẫn có thể
  làm mất dòng outbox. Cách khôi phục (an toàn: mọi consumer đều idempotent) là phát lại request của các saga bị kẹt:
  `INSERT INTO outbox_event (id, aggregatetype, aggregateid, type, payload, created_at, traceparent)
  SELECT gen_random_uuid(), o.aggregatetype, o.aggregateid, o.type, o.payload, now(), o.traceparent
  FROM outbox_event o JOIN transfer t ON o.payload->>'transferId' = t.id::text
  WHERE t.status = 'PENDING' AND o.aggregatetype = 'transfer.requested';` (trên `transfer_db`).
- ingress-nginx đã ngừng phát triển ở upstream (2026); đối tượng Ingress là chuẩn, nên đổi controller chỉ là thay đổi values.

## Website (web/)

Website ngân hàng dành cho khách hàng: React 19 + React Router 6 + TypeScript (strict) trên Vite 4, chỉ gọi gateway. Toolchain được ghim ở các phiên bản vẫn chạy được trên Node 14 (Vite 4.5, `@vitejs/plugin-react` 4, TypeScript 5.9, Vitest 0.34); lockfile là `lockfileVersion: 2` để npm 6 (đi kèm Node 14) và npm mới hơn đều đọc được.

- **Công khai:** trang giới thiệu, đăng nhập và đăng ký (tự mở tài khoản VND đầu tiên).
- **Khách hàng (`/app`):** tổng quan với tổng số dư theo loại tiền và thẻ tài khoản; danh sách tài khoản và mở tài khoản mới; chi tiết tài khoản kèm sao kê và mã tài khoản để chia sẻ khi nhận tiền; chuyển tiền có bước xem lại và `Idempotency-Key` riêng cho mỗi giao dịch; biên lai trực tiếp polling đến khi saga kết thúc; lịch sử giao dịch có lọc trạng thái; thông báo; hồ sơ.
- **Quản trị (`/admin`, chỉ `ROLE_ADMIN`):** khách hàng có tìm kiếm và khóa/mở khóa; toàn bộ tài khoản kèm chủ sở hữu và nạp tiền; toàn bộ giao dịch có lọc trạng thái.

```bash
cd web
npm install
npm run dev     # http://localhost:5174 (GATEWAY_URL ghi đè http://localhost:8080)
npm run typecheck   # tsc --noEmit (strict)
npm test            # unit test cho format.ts và gateway.ts (vitest)
npm run build       # kiểm tra kiểu, rồi tạo bundle tĩnh trong dist/
```

Triển khai dạng container (build trên Node 14, phục vụ bằng nginx, các đường dẫn API được proxy tới gateway):

```bash
docker compose -f server/docker/docker-compose.yml --profile web up -d --build web   # http://localhost:5174
# hoặc chạy riêng:
docker build -t banking/web web/ && docker run -p 5174:80 -e GATEWAY_URL=http://host.docker.internal:8080 banking/web
```

Phiên (JWT + hồ sơ) được lưu trong `sessionStorage`: tải lại trang vẫn giữ đăng nhập, đóng tab thì đăng xuất.

## Test console (client/)

Console nhẹ bằng Vite + TypeScript thuần để thử hệ thống qua gateway. Nó giữ JWT trong bộ nhớ, hiển thị mọi request/response trong nhật ký API trực tiếp, và có bốn tab cho mọi người dùng cùng ba tab quản trị:

- **Tài khoản** — mở tài khoản, xem số dư, xem bút toán sổ cái. Không có API nạp tiền; tab này in lệnh `psql` để nạp số dư thử.
- **Chuyển tiền** — gửi giao dịch kèm `Idempotency-Key`, rồi theo dõi saga polling đến `COMPLETED` hoặc `FAILED`.
- **Thông báo** — thông báo do notification-service lưu.
- **Quản trị: Khách hàng / Tài khoản / Giao dịch** — chỉ hiện với `ROLE_ADMIN`: liệt kê mọi khách hàng và khóa/mở khóa, liệt kê mọi tài khoản và nạp tiền vào, liệt kê mọi giao dịch. Đăng nhập bằng `admin@banking.local` / `admin12345` (đặt bởi `BANKING_ADMIN_EMAIL` / `BANKING_ADMIN_PASSWORD` trong docker compose; bỏ trống thì không tạo admin).
- **Kịch bản kiểm thử** — kiểm tra một cú nhấp: USER nhận 403 ở API quản trị, thiếu JWT, route nội bộ bị chặn, thiếu/lặp idempotency key, ngưỡng gian lận, không đủ tiền và bị từ chối do velocity. Ca đối tác timeout/hoàn tác làm thủ công vì cần khởi động lại service.

Yêu cầu: Node.js `>=14.18.0`, cùng npm. Hãy chạy backend trước (`bash server/scripts/demo.sh`), rồi:

```bash
cd client
npm install
npm run dev     # http://localhost:5173
```

Vite proxy `/auth`, `/accounts`, `/transfers` và `/notifications` tới gateway, nên trình duyệt không bao giờ gọi trực tiếp các service và không cần cấu hình CORS. Đổi đích bằng `GATEWAY_URL=http://host:8080 npm run dev`.

Console Vite + React để thử hệ thống qua gateway. Không dùng TypeScript, nên ở đây không có `npm run typecheck`. `npm test` chạy unit test Node cho `src/format.js`, và `npm run build` xuất file tĩnh vào `client/dist/`.

## CI/CD

`.github/workflows/ci.yml` chạy với mỗi pull request và mỗi lần push lên `main`:

| Job | Kiểm tra gì |
|---|---|
| `frontend` | `web/` và `client/` trên Node 14.18.0, 14.x, 16, 18, 20, 22: `npm ci` → `npm run typecheck` → `npm test` → `npm run build` → smoke `vite preview` (trang gốc + deep link SPA). `dist/` của Node 14 được giữ làm artifact. |
| `backend` | JDK 21, khóa RS256 dùng tạm, `mvn verify` (unit test mọi module + test context của gateway). |
| `helm` | `helm lint` chart `banking`, với values đầy đủ và `PROFILE=demo`. |
| `images` | Build 6 image service và image `web` (build stage dùng Node 14); chạy image web và kiểm tra `/healthz`, SPA fallback và proxy gateway. |

Khi push lên `main` hoặc tag `v*`, `images` cũng đẩy lên GHCR dưới dạng `ghcr.io/<owner>/banking/<image>` với các tag
`latest` (main), `sha-<short>`, tên nhánh và semver của tag. Đường dẫn khớp bố cục `<image.repository>/<service>` của chart,
nên cụm có thể triển khai bản build của CI bằng
`--set image.repository=ghcr.io/<owner>/banking --set image.tag=sha-<short>` (package GHCR mặc định là riêng tư:
hãy công khai chúng hoặc thêm image pull secret). Không có gì được tự động triển khai lên cụm.

## Lộ trình

1. React SPA: đã xong trong `web/`. `client/` hiện vẫn là test console.

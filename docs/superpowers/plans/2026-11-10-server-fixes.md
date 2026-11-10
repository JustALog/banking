# Server Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sửa 13 lỗi server tìm thấy khi đối chiếu client với server, mỗi lỗi một branch riêng, merge vào `main` theo thứ tự.

**Architecture:** Mỗi task gói gọn trong một service (cộng phần client đi kèm nếu hợp đồng API thay đổi). Task 1 chốt định dạng lỗi chung (`ProblemDetail` + `code`), các task sau dùng lại định dạng này. Migration Flyway mới cho mỗi thay đổi schema; không sửa migration cũ.

**Tech Stack:** Java 21 / Spring Boot 3 (MVC + WebFlux gateway), Spring Data JPA, Flyway, Kafka + Debezium outbox, JUnit 5 + Mockito + AssertJ; client React 19 + TypeScript, `tsx --test`.

**Spec:** Bản review trong hội thoại ngày 2026-11-10 (mục 1–13). Mỗi task dưới đây ghi rõ mục nó sửa.

## Global Constraints

- **Git (quy tắc của user):** mỗi task một branch theo vai trò/nhiệm vụ, tách từ `main` mới nhất; commit trên branch đó; test xanh rồi mới `git switch main && git merge --no-ff <branch>`. Không push nếu user chưa đồng ý.
- Trong một branch: commit server và commit client tách riêng.
- Tên branch theo prefix có sẵn của repo: `feat/`, `fix/`, `chore/`, `docs/`.
- Commit đứng tên tài khoản git của user (git config hiện tại: `JustALog`), **không** thêm dòng `Co-Authored-By` hay bất kỳ attribution nào của Claude.
- Migration mới đặt tên `V<n+1>__<snake_case>.sql`, không sửa file `V*` đã có.
- Lệnh test server: `cd server && mvn -q -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.
- Lệnh test client: `cd client && npm test && npm run typecheck`.
- Text hiển thị cho khách bằng tiếng Việt; `code` trong lỗi là UPPER_SNAKE tiếng Anh.

## Review Focus

- Gửi lại cùng Idempotency-Key với body giống hệt sau khi lần đầu đã thành công → phải ra 200 cùng transfer, không lỗi 409/500 (Task 2 có test).
- Admin bấm "Nạp" hai lần với cùng key khi mạng chập chờn → số dư chỉ tăng một lần (Task 3 có test).
- Transfer cũ không có cột `dest_account_no`/`dest_customer_id` (NULL) vẫn hiển thị và vẫn tải được danh sách (Task 9 có test với giá trị null).
- Người nhận mở chi tiết giao dịch tiền vào → 200, nhưng người lạ → vẫn 404 (Task 10 có test).
- `size` vượt 200 hoặc `page` âm → bị kẹp, không lỗi 500 (Task 11 có test).

---

### Task 0: Dọn working tree và đưa client TypeScript lên main

`main` chưa có hai commit TypeScript của `feat/client`, mà các task sau sửa `client/src/*.ts(x)`. Working tree đang có hai file sửa dở thuộc hai vai trò khác nhau.

- [ ] **Step 1:** Trên `feat/client`: `git add client/vite.config.ts && git commit -m "chore(client): disable production sourcemaps"`.
- [ ] **Step 2:** Đọc `git diff server/k8s/install.sh`, rồi `git stash push server/k8s/install.sh`, `git switch main`, `git switch -c chore/k8s-install`, `git stash pop`, commit với message mô tả đúng nội dung diff.
- [ ] **Step 3:** `cd client && npm test && npm run typecheck` trên `feat/client` → PASS.
- [ ] **Step 4:** `git switch main && git merge --no-ff feat/client && git merge --no-ff chore/k8s-install`.

---

### Task 1: Định dạng lỗi chung có `code` (mục 7) — branch `refactor/api-error-codes`

**Files:**
- Modify: `server/{auth,ledger,transfer}-service/src/main/java/.../web/ApiExceptionHandler.java`
- Modify: `server/transfer-service/.../saga/SagaOrchestratorService.java` (`AccountValidationException`, `validateAccounts`)
- Modify: `server/transfer-service/.../web/TransferController.java` (400 body rỗng khi thiếu key)
- Modify: `server/gateway-service/.../filter/IdempotencyKeyGatewayFilterFactory.java`
- Modify: `client/src/gateway.ts` (`messageFor`), `client/src/format.ts` (`validationText`, `REASONS`), `client/src/pages/transfer.tsx` (xử lý 422), `client/src/pages/public.tsx` (`useSubmit`)
- Test: `server/transfer-service/src/test/.../saga/SagaOrchestratorServiceTest.java`, `server/gateway-service/src/test/.../filter/IdempotencyKeyGatewayFilterFactoryTest.java`, `client/src/format.test.ts`

**Interfaces:**
- Produces: mọi lỗi 4xx có dạng ProblemDetail `{ status, detail, code }`; lỗi validation thêm `errors: [{ field: string, message: string }]`.
- Produces: `AccountValidationException(String code, String message)` với `getCode()`; các code: `SOURCE_ACCOUNT_INVALID` (không tìm thấy nguồn, hoặc không phải chủ), `ACCOUNT_NOT_FOUND` (đích), `CURRENCY_MISMATCH`, `SELF_TRANSFER_NOT_ALLOWED`.
- Produces: các code khác: `VALIDATION_FAILED`, `IDEMPOTENCY_KEY_MISSING`, `EMAIL_TAKEN`, `BAD_CREDENTIALS`, `CUSTOMER_LOCKED`, `NOT_FOUND`, `SELF_LOCK`.
- Produces (client): `messageFor(status, data)` ưu tiên `reasonText(data.code)` khi `REASONS` có code đó; `validationText(errors: {field,message}[])`.

- [ ] **Step 1: Test lỗi (transfer)** — trong `SagaOrchestratorServiceTest` thêm `destCurrencyMismatchHasCode`: stub dest khác currency, `assertThatThrownBy(...).isInstanceOf(AccountValidationException.class).extracting("code").isEqualTo("CURRENCY_MISMATCH")`; và `selfTransferHasCode` → `"SELF_TRANSFER_NOT_ALLOWED"`.
- [ ] **Step 2:** Chạy `-pl transfer-service -Dtest=SagaOrchestratorServiceTest` → FAIL (không có `code`).
- [ ] **Step 3:** Thêm `code` vào exception, gán code trong `validateAccounts`; chuyển kiểm tra `source == dest` lên **trước** khi fetch dest. Mỗi `ApiExceptionHandler` set `detail.setProperty("code", ...)`; handler validation đổi `errors` thành list `Map.of("field", f, "message", m)`. `TransferController` thiếu key → trả ProblemDetail 400 code `IDEMPOTENCY_KEY_MISSING`.
- [ ] **Step 4: Test gateway** — trong `IdempotencyKeyGatewayFilterFactoryTest`, assert body khi thiếu key chứa `"code":"IDEMPOTENCY_KEY_MISSING"` và `"status":400`; sửa chuỗi JSON trong filter cho khớp. Chạy `-pl gateway-service` → PASS.
- [ ] **Step 5:** Chạy test của auth, ledger, transfer → PASS. Commit `refactor(server): machine-readable error codes in ProblemDetail`.
- [ ] **Step 6: Test client** — trong `format.test.ts`: `validationText([{field:'password',message:'size'}])` → `'Mật khẩu cần từ 8 đến 100 ký tự.'`; `messageFor(422, {code:'CURRENCY_MISMATCH'})` → `'Hai tài khoản khác loại tiền.'`. Thêm vào `REASONS`: `SOURCE_ACCOUNT_INVALID: 'Tài khoản nguồn không hợp lệ.'`.
- [ ] **Step 7:** Sửa `transfer.tsx`: bỏ nhánh `err.status === 422 ? ...`, chỉ dùng `err.message`. Chạy test client → PASS. Commit `feat(client): read error codes from the API`.
- [ ] **Step 8:** Merge vào main.

---

### Task 2: Idempotency-Key theo từng khách + chống race (mục 1) — branch `fix/transfer-idempotency`

**Files:**
- Create: `server/transfer-service/src/main/resources/db/migration/V4__idempotency_per_customer.sql`
- Modify: `server/transfer-service/.../domain/Transfer.java` (bỏ `unique = true`), `TransferRepository.java`, `saga/SagaOrchestratorService.java`, `web/TransferController.java`, `web/ApiExceptionHandler.java`
- Test: `SagaOrchestratorServiceTest.java`, Create `server/transfer-service/src/test/.../web/TransferControllerTest.java`

**Interfaces:**
- Consumes: định dạng lỗi của Task 1.
- Produces: `Optional<Transfer> findByCustomerIdAndIdempotencyKey(UUID, String)` (thay `findByIdempotencyKey`); `IdempotencyConflictException` → 422 code `IDEMPOTENCY_KEY_REUSED`.

- [ ] **Step 1: Migration** — `ALTER TABLE transfer DROP CONSTRAINT transfer_idempotency_key_key; ALTER TABLE transfer ADD CONSTRAINT uq_transfer_customer_key UNIQUE (customer_id, idempotency_key);` (kiểm tra lại tên constraint bằng `\d transfer` trên DB dev trước khi ghi).
- [ ] **Step 2: Test lỗi** — `sameKeyFromOtherCustomerStartsNewSaga` (repo trả empty cho customer B → `created()` true); `sameKeyDifferentBodyIsRejected` (transfer cũ amount 250_000, gọi lại với 300_000 → ném `IdempotencyConflictException`); sửa test replay hiện có sang method repo mới.
- [ ] **Step 3:** Chạy → FAIL.
- [ ] **Step 4:** Trong `start`: tìm theo `(customerId, key)`; nếu có thì so source, dest, amount, currency: giống hệt → `StartResult(existing,false)`, khác → ném conflict.
- [ ] **Step 5: Test race** — `TransferControllerTest.duplicateKeyRaceReturnsExisting`: mock `saga.start` lần 1 ném `DataIntegrityViolationException`, lần 2 trả `StartResult(t,false)` → response 200 với `t.getId()`.
- [ ] **Step 6:** Trong `TransferController.create` bắt `DataIntegrityViolationException` và gọi lại `saga.start` một lần (lần gọi thứ hai đọc ra bản ghi đã commit).
- [ ] **Step 7:** Chạy test transfer → PASS. Commit `fix(transfer): scope idempotency keys per customer and reject body mismatch`. Merge.

---

### Task 3: Nạp tiền idempotent (mục 3) — branch `fix/ledger-deposit-idempotency`

**Files:**
- Modify: `server/gateway-service/src/main/resources/application.yml` (route mới trước `ledger-service`)
- Modify: `server/ledger-service/.../web/AdminAccountController.java`, `account/AccountService.java`, `entry/LedgerEntryRepository.java`
- Modify: `client/src/pages/admin.tsx` (`AdminAccounts`)
- Test: `server/gateway-service/src/test/.../InternalRouteBlockedTest.java`, Create `server/ledger-service/src/test/.../account/AccountServiceTest.java`

**Interfaces:**
- Produces: `POST /accounts/admin/{id}/deposit` bắt buộc header `Idempotency-Key` dạng UUID; `AccountService.deposit(UUID accountId, long amount, UUID reference): Account`; `boolean existsByTransferIdAndAccountId(UUID, UUID)`.

- [ ] **Step 1: Test gateway** — `depositGoesThroughIdempotencyRoute`: `firstMatchingRoute("/accounts/admin/123e4567-e89b-12d3-a456-426614174000/deposit")` == `"ledger-deposit"`; `/accounts` vẫn == `"ledger-service"`.
- [ ] **Step 2:** Thêm route `ledger-deposit` (`Path=/accounts/admin/*/deposit`, filter `IdempotencyKey`, cùng uri ledger) đặt ngay dưới `block-internal`. Chạy → PASS.
- [ ] **Step 3: Test ledger** — `AccountServiceTest.replayedDepositDoesNotCreditTwice`: `existsByTransferIdAndAccountId(ref, id)` trả true → balance không đổi, `entries.save` không được gọi; `firstDepositCredits` → balance tăng đúng `amount`.
- [ ] **Step 4:** `deposit` khoá dòng bằng `findByIdForUpdate`, rồi mới kiểm tra `exists...` (khoá có trước nên hai request song song không cùng lọt qua bước kiểm tra), sau đó lưu `LedgerEntry(reference, ...)`. Controller nhận `@RequestHeader("Idempotency-Key") UUID key`. Key sai định dạng → 400 (`MethodArgumentTypeMismatchException` → handler code `VALIDATION_FAILED`).
- [ ] **Step 5:** Chạy test ledger + gateway → PASS. Commit server.
- [ ] **Step 6: Client** — tạo `crypto.randomUUID()` khi `setPending(...)` (lưu trong `pending.key`), gửi `headers: { 'Idempotency-Key': pending.key }`. Typecheck → PASS. Commit client. Merge.

---

### Task 4: Ledger không lộ sự tồn tại tài khoản + sửa matcher chết (mục 4, 6) — branch `fix/ledger-access`

**Files:** `server/ledger-service/.../web/ApiExceptionHandler.java`, `account/AccountService.java`, `config/SecurityConfig.java`; Test: `AccountServiceTest.java`

- [ ] **Step 1: Test** — `foreignAccountLooksMissing`: `getOwnedAccount(id, otherCustomer)` ném `AccountNotFoundException`, và `getMessage()` không chứa UUID nào.
- [ ] **Step 2:** Chạy → FAIL. Sửa: không phải chủ → ném `AccountNotFoundException`; message cố định `"Account not found"`; xoá `NotAccountOwnerException` và handler 403 của nó (grep để chắc không còn nơi nào dùng).
- [ ] **Step 3:** `SecurityConfig`: đổi `"/internal/**"` thành `"/accounts/internal/**"`, sửa comment: chỉ gateway (route `block-internal`) và NetworkPolicy (`allowFrom: [gateway-service, transfer-service]`) mới chặn được người ngoài.
- [ ] **Step 4:** Chạy test → PASS. Commit, merge.

---

### Task 5: Khách bị khoá không chuyển tiền được (mục 2) — branch `fix/transfer-block-locked-customer`

**Files:**
- Create: `server/transfer-service/.../account/CustomerStatusClient.java`
- Modify: `saga/SagaOrchestratorService.java`, `transfer-service/src/main/resources/application.yml`, `server/auth-service/src/main/resources/application.yml` (`ttl: 15m`), `server/docker/docker-compose.yml` (env transfer), `server/k8s/helm/banking/values.yaml` (`transfer-service.env.BANKING_AUTH_URL`, `auth-service.allowFrom` thêm `transfer-service`)
- Modify: `client/src/pages/admin.tsx` (đổi "tối đa 60 phút" thành "tối đa 15 phút")
- Test: `SagaOrchestratorServiceTest.java`

**Interfaces:**
- Produces: `CustomerStatusClient.isActive(String bearerToken): boolean`, gọi `GET {banking.auth.base-url}/auth/me` và trả `"ACTIVE".equals(status)`; `base-url: ${BANKING_AUTH_URL:http://localhost:8081}`. Bị khoá → `AccountValidationException("CUSTOMER_LOCKED", ...)` → 422.
- Constructor `SagaOrchestratorService` thêm tham số `CustomerStatusClient` (cập nhật `setUp()` trong test).

- [ ] **Step 1: Test** — `lockedCustomerCannotStartTransfer`: `isActive` false → ném với code `CUSTOMER_LOCKED`, `transfers.save` không được gọi.
- [ ] **Step 2:** Chạy → FAIL. Implement theo mẫu `AccountValidationClient`; kiểm tra trạng thái ngay sau bước tìm transfer cũ theo key (replay vẫn trả kết quả cũ).
- [ ] **Step 3:** Thêm `CUSTOMER_LOCKED: 'Tài khoản đã bị khoá. Liên hệ ngân hàng để mở lại.'` vào `REASONS` của client và sửa câu hint trong admin.
- [ ] **Step 4:** Test transfer + client → PASS. `helm template server/k8s/helm/banking | grep -A3 BANKING_AUTH_URL` có giá trị. Commit server, commit client, merge.

---

### Task 6: Giới hạn số lần đăng nhập sai (mục 5) — branch `feat/auth-login-throttle`

**Files:** Create `server/auth-service/.../security/LoginThrottle.java`; Modify `customer/CustomerService.java` (hoặc `web/AuthController.java`), `web/ApiExceptionHandler.java`; Test: Create `server/auth-service/src/test/.../security/LoginThrottleTest.java`

**Interfaces:**
- Produces: `LoginThrottle(Clock clock)` với `boolean blocked(String email)`, `void fail(String email)`, `void reset(String email)`; 5 lần sai trong 15 phút → bị chặn đến hết cửa sổ. Bị chặn → 429 code `TOO_MANY_ATTEMPTS`. Khoá là email lowercase.
- Comment trong class: `// shortcut: per-replica in-memory counter, move to Redis/gateway RequestRateLimiter when auth runs >1 replica.`

- [ ] **Step 1: Test** — `fifthFailureBlocks`, `blockExpiresAfter15Minutes` (dùng `Clock` giả có thể tua), `successResets`.
- [ ] **Step 2:** Chạy → FAIL; implement bằng `ConcurrentHashMap<String, Deque<Instant>>`; gọi trong `login`: `blocked` → 429, sai → `fail`, đúng → `reset`.
- [ ] **Step 3:** Client: thêm `429: 'Bạn nhập sai quá nhiều lần. Thử lại sau 15 phút.'` vào `known` trong `useSubmit` (`public.tsx`).
- [ ] **Step 4:** Test → PASS. Commit từng phần, merge.

---

### Task 7: Register trả luôn phiên đăng nhập (mục 12) — branch `feat/auth-register-session`

**Files:** `server/auth-service/.../web/AuthController.java`; `client/src/pages/public.tsx`, `client/src/gateway.ts`

**Interfaces:** `POST /auth/register` → 201 với body `LoginResponse` (giống `/auth/login`). Client: export `startSession(data: Session)`, dùng chung cho `login`.

- [ ] **Step 1:** `register` gọi `jwtService.issueToken` và trả `LoginResponse`. Smoke: `server/scripts/smoke.sh` vẫn chạy được (sửa nếu script đọc body register).
- [ ] **Step 2:** Client `Register`: `const { data } = await api('POST','/auth/register',…); startSession(data);` rồi bỏ lời gọi `login`. Giữ `POST /accounts` (`.catch(() => {})` vẫn ổn, vì dashboard có sẵn form mở tài khoản khi chưa có tài khoản nào).
- [ ] **Step 3:** Typecheck + test → PASS. Commit, merge.

---

### Task 8: Chỉ cho phép VND/USD/EUR và tối đa 10 tài khoản (mục 13) — branch `fix/ledger-open-account-rules`

**Files:** `server/ledger-service/.../web/dto/OpenAccountRequest.java`, `account/AccountService.java`, `account/AccountRepository.java`, `web/ApiExceptionHandler.java`; Test `AccountServiceTest.java`

**Interfaces:** `@Pattern(regexp = "VND|USD|EUR")`; `long countByCustomerId(UUID)`; tài khoản thứ 11 → `AccountLimitException` → 409 code `ACCOUNT_LIMIT_REACHED`.

- [ ] **Step 1:** Test `eleventhAccountRejected` (count = 10 → ném). Chạy → FAIL. Implement → PASS.
- [ ] **Step 2:** Client `REASONS`: `ACCOUNT_LIMIT_REACHED: 'Bạn đã có tối đa 10 tài khoản.'`. Commit từng phần, merge.

---

### Task 9: Chuyển tiền bằng số tài khoản, trả kèm số tài khoản (mục 9) — branch `feat/transfer-by-account-no`

**Files:**
- Create: `server/transfer-service/src/main/resources/db/migration/V5__transfer_account_numbers.sql` — thêm `source_account_no VARCHAR(20)`, `dest_account_no VARCHAR(20)`, `dest_customer_id UUID` (nullable), index `(dest_customer_id, created_at DESC)`
- Modify: `server/ledger-service/.../web/AccountController.java` (`GET /accounts/internal/by-no/{accountNo}`)
- Modify: `server/transfer-service/.../account/AccountValidationClient.java` (`fetchByNo`), `domain/Transfer.java`, `web/dto/CreateTransferRequest.java`, `web/dto/TransferResponse.java`, `saga/SagaOrchestratorService.java`
- Modify: `server/common-events/.../TransferCompletedEvent.java` (thêm `UUID destCustomerId` ở cuối; giữ các constructor cũ, truyền null)
- Modify client: `src/types.ts`, `src/pages/transfer.tsx`, `src/pages/user.tsx` (`AccountDetail`, `TransferList`, `accountLabel`), `src/format.ts` (`transferProblem`)
- Test: `SagaOrchestratorServiceTest.java`, gateway `InternalRouteBlockedTest` (đường `/accounts/internal/by-no/VN0000000000` bị chặn), `client/src/format.test.ts`

**Interfaces:**
- Produces: `CreateTransferRequest(UUID sourceAccountId, UUID destAccountId /*nullable*/, String destAccountNo /*nullable, ^VN\d{10}$*/, long amount, String currency)`; chỉ được có đúng một trong hai trường đích, sai → 400 `VALIDATION_FAILED`.
- Produces: `TransferResponse` thêm `sourceAccountNo`, `destAccountNo` (null với bản ghi cũ); `Transfer.getDestCustomerId()`.
- Produces: `AccountValidationClient.fetchByNo(String accountNo, String bearer): LedgerAccount` (null nếu 404).
- Produces (client): `Transfer.sourceAccountNo?: string | null`, `destAccountNo?: string | null`; ô "Người khác" nhận số tài khoản; `transferProblem` kiểm tra `/^VN\d{10}$/` thay cho `UUID_RE`.

- [ ] **Step 1: Test** — `startByAccountNoResolvesDest` (stub `fetchByNo` → `LedgerAccount`, transfer lưu đúng `destAccountId`, `destAccountNo`, `destCustomerId`); `completedEventCarriesDestCustomer`; `legacyTransferWithNullAccountNoMapsToResponse`.
- [ ] **Step 2:** Chạy → FAIL. Implement endpoint ledger, client fetch, lưu ba cột lúc `start`, thêm `destCustomerId` vào event lúc COMPLETED. Ghi chú: các consumer cũ đọc JSON bỏ qua field lạ (kiểm tra `EventJson` có `FAIL_ON_UNKNOWN_PROPERTIES=false`; nếu không có thì thêm).
- [ ] **Step 3:** Test ledger, transfer, gateway, common-events → PASS. Commit server.
- [ ] **Step 4: Client** — test `transferProblem({mode:'other', from, dest:'VN0123456789', amount:1})` → `''`; `dest:'abc'` → `'Số tài khoản không đúng định dạng.'`. Gửi `destAccountNo` khi mode `other`, `destAccountId` khi mode `own`. `AccountDetail` hiển thị và cho copy `accountNo` thay vì `id`; nút copy trên Dashboard cũng vậy. `TransferList`/receipt ưu tiên `t.destAccountNo`, fallback `accountLabel`/`shortId`.
- [ ] **Step 5:** Test + typecheck → PASS. Commit client, merge.

---

### Task 10: Người nhận thấy giao dịch tiền vào (mục 10) — branch `feat/transfer-incoming`

**Files:** `server/transfer-service/.../domain/TransferRepository.java`, `saga/SagaOrchestratorService.java` (`listFor`, `getOwned`), `web/dto/TransferResponse.java`, `web/TransferController.java`; client `src/types.ts`, `src/pages/user.tsx` (`TransferList`, `AccountDetail` link bút toán), `src/pages/transfer.tsx` (`TransactionDetail` ẩn "Chuyển tiền mới" với IN)

**Interfaces:**
- Consumes: `Transfer.getDestCustomerId()` (Task 9).
- Produces: `List<Transfer> findByCustomerIdOrDestCustomerIdOrderByCreatedAtDesc(UUID, UUID)` (Task 11 thêm tham số `Pageable`); `TransferResponse.direction`: `"OUT"` nếu `customerId == me`, ngược lại `"IN"` (`TransferResponse.from(Transfer, UUID viewer)`; endpoint admin truyền null → `"OUT"`).
- Người nhận chỉ thấy transfer `COMPLETED` (lọc trong service), vì với họ lệnh PENDING/FAILED chưa từng là tiền.

- [ ] **Step 1: Test** — `recipientCanReadCompletedIncoming` (getOwned bởi destCustomer, status COMPLETED → OK); `strangerStillGets404`; `recipientCannotSeePendingIncoming` → 404.
- [ ] **Step 2:** Chạy → FAIL; implement → PASS. Commit server.
- [ ] **Step 3: Client** — `Transfer.direction?: 'IN' | 'OUT'`; `TransferList` với IN hiển thị "Từ {sourceAccountNo}" và dấu `+`; bút toán trong sao kê link sang `/app/transactions/{transferId}` khi `direction==='CREDIT'`. Typecheck → PASS. Commit, merge.

---

### Task 11: Phân trang + tìm kiếm cho admin (mục 11) — branch `feat/server-pagination`

**Files:** các repository và controller list của auth (customers), ledger (accounts admin, entries), transfer (mine, admin), notification (mine); `application.yml` của 4 service (`spring.data.web.pageable.default-page-size: 50`, `max-page-size: 200`); client `src/hooks.ts`, các trang list.

**Interfaces:**
- Produces: mọi endpoint list nhận `?page=0&size=50` và **vẫn trả JSON array** (không bọc trong `Page`), để client cũ không vỡ. Repository dùng `List<T> findBy…(…, Pageable)`, thay cho các method `findTop200…`.
- Produces: `GET /auth/admin/customers?q=` (lọc email hoặc fullName, không phân biệt hoa thường); `GET /accounts/admin?q=` (lọc accountNo).
- Produces (client): `usePaged<T>(path: string, size = 50): { items: T[]; more: boolean; loadMore(): Promise<void>; loading; error; reload }`, trong đó `more = lastPage.length === size`.

- [ ] **Step 1: Test** — với mỗi service một test service-level kiểm tra repository được gọi với `PageRequest.of(0, 50, …)`; ví dụ auth `searchFiltersByEmailOrName`. Test kẹp: gọi controller với `size=1000` qua `@WebMvcTest` của transfer → `Pageable.getPageSize()==200`.
- [ ] **Step 2:** Implement, chạy test cả 4 service → PASS. Commit server.
- [ ] **Step 3: Client** — test `usePaged` không cần thiết (logic một dòng); thêm nút "Tải thêm" (ẩn khi `!more`) ở Lịch sử, Sao kê, Thông báo và 3 bảng admin; ô tìm kiếm admin gửi `q` (debounce 300ms) thay vì lọc trên client; xoá `LIMIT_NOTE`. Typecheck → PASS. Commit, merge.

---

### Task 12: Thông báo tiếng Việt, gửi cả người nhận (mục 8) — branch `feat/notification-vi-and-recipient`

**Files:** `server/notification-service/.../service/NotificationService.java`, `listener/TransferEventsListener.java`; client `src/pages/user.tsx` (bỏ comment và workaround `includes('@')` nếu không còn cần); Test `NotificationServiceTest.java`

**Interfaces:**
- Consumes: `TransferCompletedEvent.destCustomerId` (Task 9).
- Produces: `notifyTransferReceived(UUID destCustomerId, UUID transferId, long amount, String currency)`; bỏ qua khi `destCustomerId == null` hoặc khi trùng người gửi (chuyển giữa hai tài khoản của chính mình).
- Copy cố định (các đoạn `%s` được thay bằng số tiền đã định dạng `vi-VN` và mã giao dịch):
  - Thành công: tiêu đề `Chuyển tiền thành công`, nội dung `Lệnh chuyển %s %s đã hoàn tất. Mã giao dịch %s.`
  - Thất bại: tiêu đề `Chuyển tiền không thành công`, nội dung `Lệnh chuyển (mã %s) không thực hiện được: %s` — lý do lấy từ bảng tiếng Việt giống `REASONS` của client (copy 7 dòng sang server; mã lạ thì giữ nguyên mã).
  - Nhận tiền: tiêu đề `Bạn vừa nhận tiền`, nội dung `Tài khoản của bạn vừa nhận %s %s. Mã giao dịch %s.`
- Không đổi `recipient` (dịch vụ gửi vẫn là mock); comment `// shortcut: recipient is the customer id; store email when a real EMAIL/SMS sender is added.`

- [ ] **Step 1: Test** — `completedNotifiesRecipientToo` (2 bản ghi với 2 customerId khác nhau); `selfTransferNotifiesOnce`; `failedUsesVietnameseReason` (content chứa `Số dư tài khoản nguồn không đủ.`); `redeliveryStillDeduplicates` (unique index `(transfer_id, title)` vẫn đúng vì ba tiêu đề khác nhau).
- [ ] **Step 2:** Chạy → FAIL; implement → PASS. Thông báo cũ trong DB vẫn là tiếng Anh, không migrate. Commit, merge.

---

### Task 13: Kiểm thử end-to-end

- [ ] **Step 1:** `cd server && mvn -q test` → toàn bộ PASS.
- [ ] **Step 2:** `docker compose -f server/docker/docker-compose.yml up -d --build`, rồi chạy `server/scripts/smoke.sh` và `server/scripts/demo.sh` → không lỗi (sửa script nếu nó gửi `destAccountId` theo cách cũ hoặc đọc body register cũ, commit trên branch `chore/scripts-api-v2`).
- [ ] **Step 3:** `cd client && npm run dev`, đi tay luồng: đăng ký → admin nạp tiền (bấm hai lần) → chuyển bằng số `VN…` → người nhận thấy "Bạn vừa nhận tiền" và giao dịch IN → admin khoá → khách chuyển tiền nhận "Tài khoản đã bị khoá".

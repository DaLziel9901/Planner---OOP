# Hướng dẫn demo Planner

## Kết quả đã ghi nhận

Trong đợt nghiệm thu 2026-11-28, người dùng chạy 277 ca regression và bốn ca PostgreSQL riêng, đều thành công. `PlannerApplicationTests` và `TaskWebSmokeTest` đạt 11/11 trong regression. Người dùng đã chạy kịch bản trình duyệt trên `feature/auth`, gồm Groq thật với `openai/gpt-oss-20b`, sửa draft, xác nhận lưu theo owner và kiểm restart. Lượt kiểm trên bản ghép `feature/ui` vẫn mở. [Bằng chứng và giới hạn](COMPLETION_REPORT.md)

## Quy trình kiểm trên môi trường thử

Các lệnh dưới đây mô tả cách tái lập kiểm chứng. Với một checkout đã có catalog 003 trên database thử, dùng nhóm regression hoặc chạy ứng dụng. Phần foundation, rehearsal và bootstrap dành cho đích thử trống theo điều kiện từng lượt.


Chạy nhóm test cô lập để kiểm tra đăng ký, đăng nhập, CSRF và AI draft với provider giả.

```powershell
.\mvnw.cmd '-Dtest=RegistrationServiceTest,AuthSecurityIntegrationTest,OpenAiCompatiblePlanProviderTest,PlanDraftServiceTest,AiPlanControllerTest' test
```

Trong terminal đã có đủ sáu biến DB, chạy checkpoint foundation riêng trên `planner_auth_test`. Cần role thử có CONNECT trên đích thử, USAGE/CREATE trên public và không có CONNECT hiệu lực vào `planner`. Harness kiểm quyền trước SQL ghi. Cần đích thử chưa có `public.app_user`.

```powershell
.\mvnw.cmd '-Dtest=AuthDatabaseFoundationTest' '-Dsurefire.failIfNoSpecifiedTests=true' test
if ($LASTEXITCODE -ne 0) { throw 'AuthDatabaseFoundationTest failed' }
```

Ca rehearsal migration cần đích thử chưa có bốn bảng Planner. Chạy riêng sau foundation, khi không có suite ghi khác. DDL và fixture được rollback.

```powershell
.\mvnw.cmd '-Dtest=AuthMigrationTest,OwnershipRepositoryIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=true' test
if ($LASTEXITCODE -ne 0) { throw 'Planner PostgreSQL rehearsal failed' }
```

Sau khi foundation và rehearsal có kết quả thật, khởi tạo schema cho các suite vận hành. Lệnh này ghi schema 003 lên đích thử trống. Harness từ chối nếu đã có quan hệ Planner trong `public`. Các suite hồi quy sau đó tạo tài khoản và dữ liệu fixture theo từng ca.

```powershell
.\mvnw.cmd '-Dtest=PlannerSchemaBootstrapTest' '-Dplanner.test.bootstrap=true' test
if ($LASTEXITCODE -ne 0) { throw 'PlannerSchemaBootstrapTest failed' }
.\mvnw.cmd '-Dtest=*,!AuthDatabaseFoundationTest,!AuthMigrationTest,!OwnershipRepositoryIntegrationTest,!PlannerSchemaBootstrapTest' test
if ($LASTEXITCODE -ne 0) { throw 'Planner PostgreSQL regression failed' }
```

Ba ca chạy trước bootstrap cần schema trống và tự rollback. Lượt cuối chạy phần còn lại của full suite trên schema 003 đã khởi tạo, gồm đăng ký đồng thời, smoke HTTP hai tài khoản, preview/edit/confirm AI với provider test và rollback khi ghi lá thất bại giữa chừng. Khi nghiệm thu, đối chiếu báo cáo của cả hai giai đoạn và kiểm các ca SQL bắt buộc có số skipped bằng 0.

## Kịch bản browser trên catalog 003

Đặt `DB_*` trong terminal trỏ đến catalog 003 đã chuẩn bị. Cấu hình `AI_BASE_URL` (base HTTPS), `AI_MODEL` và `AI_API_KEY` trong môi trường riêng để gọi provider thật. Chỉ dùng dữ liệu thử khi demo.

```powershell
.\mvnw.cmd spring-boot:run
```

Mở `http://localhost:8080/`. Giữ hai phiên browser riêng để kiểm ownership.

1. Đăng ký hai tài khoản. Kiểm username trùng và đăng nhập sai mật khẩu bị từ chối.
2. Dùng tài khoản A tạo Plan, SubPlan, Task và SubTask. Đánh dấu hoàn thành một lá rồi kiểm tiến độ.
3. Mở tài khoản B ở một phiên riêng. Kiểm danh sách không chứa dữ liệu A và URL dữ liệu A trả 404.
4. Trong phiên A, mở `/ai/plans/new`, nhập mục tiêu, trình độ, thời hạn và ghi chú. Xem bản nháp và xác minh Plan chưa có trong danh sách.
5. Chỉnh bản nháp JSON. Kiểm danh sách Plan trước xác nhận, rồi xác nhận và mở cây đã lưu. Kiểm Task có SubTask, tiến độ ban đầu và tài khoản B không truy cập được.
6. Đăng xuất và mở lại URL riêng tư để kiểm tra chuyển hướng.
7. Restart ứng dụng, đăng nhập lại và kiểm dữ liệu cùng tiến độ của các cây đã lưu.

`TaskWebSmokeTest` kiểm HTTP trên cổng ngẫu nhiên với PostgreSQL thật và provider giả. Kết quả gọi provider thật đến từ lượt browser. Khi ghép UI, chạy lại các bước liên quan đến layout, token, điều hướng, progress và vòng đời draft theo [hợp đồng UI](UI_BACKEND_CONTRACT.md).

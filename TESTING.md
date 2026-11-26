# Kiểm thử Planner: model, CRUD, giao diện và PostgreSQL

## Task độc lập — T011–T014, 16/11/2026

Các suite mới: `TaskRepositoryTest` (PostgreSQL thật, rollback fixture),
`TaskServiceTest` (validation, khóa/đọc lại, proxy giao dịch với transaction manager giả),
`TaskControllerTest` (HTTP mô phỏng, không render), `TaskViewTest` (Thymeleaf thật, service giả).
`TaskWebSmokeTest` khởi động Spring Boot trên cổng ngẫu nhiên, gọi HTTP thật bằng Java HttpClient,
dùng service/proxy/JDBC thật và cleanup chỉ ID Task do test tạo; có thêm kiểm tra rollback dữ liệu thật.
Smoke này không chạy JavaScript hoặc hộp thoại trình duyệt.

```powershell
.\mvnw.cmd '-Dtest=TaskRepositoryTest,TaskServiceTest,TaskControllerTest,TaskViewTest,TaskWebSmokeTest' test
```

Repository/smoke opt-in qua TEST_DB_URL và chỉ dùng database `planner` chứa dữ liệu thử.
Vì smoke tải ứng dụng, cần đặt cả DB_* và TEST_DB_* cùng URL/user/password trước chạy
theo hướng dẫn bên dưới. Nếu thiếu TEST_DB_URL thì các test SQL/smoke bị skipped, không phải PASS.
Mật khẩu chỉ cung cấp qua môi trường; không ghi vào repo hoặc lệnh lưu lại.
Xem [tóm tắt nghiệm thu](#nghiệm-thu-mvp-t043).

### Mở rộng nơi chứa — T015–T017

Cập nhật T015–T017, 16/11/2026: các suite Task ở trên kiểm tra cả ba nơi chứa,
lọc trực tiếp, FK nullable và bất biến khi sửa, khóa Plan → Task và đọc lại.
TaskWebSmokeTest kiểm tra thêm liên kết Plan/SubPlan, form chọn cha, phân loại danh sách,
forged FK/createdAt, đích chuyển hướng khi xóa và rollback cho Task trong cây.
Không suy rộng các test này thành kiểm chứng hai transaction đồng thời (T036).

## CRUD SubTask — T018–T024, 16/11/2026

`SubTaskRepositoryTest` kiểm SQL thật, FK bắt buộc, ngày dự kiến NULL, thứ tự,
cascade và UPDATE nội dung giữ task_id/done/completed_at/created_at; có ma trận
service/JDBC cho Task độc lập, trực tiếp Plan và SubPlan. Bộ test CRUD của đợt này
dùng fixture SQL để kiểm tra việc giữ trạng thái hoàn thành khi sửa nội dung.
`SubTaskServiceTest` kiểm validation và khóa/đọc lại; SubTaskService dùng
`TaskService.lockForWrite` trong giao dịch hiện có (MANDATORY) để tái sử dụng Plan → Task.
`SubTaskControllerTest` kiểm whitelist, giữ form (kể cả cha thiếu), 404, PRG/flash và GET không xóa.
`SubTaskViewTest` render ba template; `TaskViewTest` kiểm danh sách/link lá trên chi tiết Task.

```powershell
.\mvnw.cmd '-Dtest=TaskRepositoryTest,SubTaskRepositoryTest,TaskServiceTest,SubTaskServiceTest,TaskControllerTest,TaskViewTest,SubTaskControllerTest,SubTaskViewTest,TaskWebSmokeTest' test
```

TaskWebSmokeTest mở HTTP thật để kiểm CRUD/cascade ba nơi chứa, forged FK/trạng thái,
giữ SubTask khi sửa Task, và rollback thay đổi/xóa với Spring proxy/JDBC thật.
Vẫn cần DB_* và TEST_DB_* cùng planner thử; dùng cleanup đúng ID fixture, không TRUNCATE/DROP.
Không coi HTTP/render là kiểm chứng JavaScript confirm, cạnh tranh đồng thời hoặc tiến độ.

## Các suite Plan/SubPlan hiện có

Không sửa production code hoặc thêm dependency để chạy các test này.
Các test mới nằm trong package tương ứng dưới `src/test/java/com/example/planner/`.

| File | Cách kiểm tra | Chứng minh được gì |
| --- | --- | --- |
| `service/PlanServiceTest.java` | JUnit + Mockito giả lập repository | Kiểm tra tên/ngày trước khi ghi, dữ liệu tùy chọn, trả ID, giữ createdAt, 404, không che lỗi database |
| `controller/PlanControllerTest.java` | MockMvc mô phỏng HTTP + service giả | URL, tên view/model, giữ form và lỗi, ngày nhập sai, ID từ URL, chuyển hướng, flash message, 404, xóa bằng POST |
| `controller/PlanViewTest.java` | MockMvc + Thymeleaf render thật, service giả | Cả ba template Plan, giữ dữ liệu sai, escape nội dung, liên kết SubPlan, form xác nhận xóa |
| `repository/PlanRepositoryTest.java` | JDBC trên PostgreSQL thật, chỉ khi bật bằng biến môi trường | SQL CRUD, ánh xạ NULL/ngày/thời điểm, ID tự sinh, tên trùng, tham số SQL, cascade, chạy schema lặp lại |

Mockito tạo đối tượng thay thế dependency và cho phép quy định kết quả trả về.
Ví dụ `when(repository.insert(...)).thenReturn(12L)` không thực hiện INSERT thật.
`verify(...)` kiểm tra phương thức dependency có được gọi không.
MockMvc gửi yêu cầu HTTP mô phỏng đến controller, không cần mở cổng web.
Trong PlanControllerTest, view resolver chỉ giả lập chuyển tiếp, không render
Thymeleaf. PlanViewTest và SubPlanControllerTest có render Thymeleaf thật nhưng
vẫn dùng service giả. Cả hai cách không chạy JavaScript/hộp thoại xác nhận và
không thay thế kiểm thử luồng trình duyệt hoặc SQL thật.

SubPlanServiceTest kiểm tra tầng 5/6, vòng lặp, độ sâu cả nhánh và cảnh báo ngày;
SubPlanRepositoryTest có 4 test SQL riêng, cũng chỉ bật khi có TEST_DB_URL.
Xem [SUBPLAN.md](SUBPLAN.md) để biết quy tắc cây.

## Chạy test không cần PostgreSQL

Dùng JDK 17, mở bảng Testing trong VS Code, chạy `PlanServiceTest` và
`PlanControllerTest`. Hoặc trong Terminal PowerShell:

```powershell
.\mvnw.cmd "-Dtest=PlanServiceTest,PlanControllerTest" test
```

Nếu dùng Maven cài riêng, thay `.\mvnw.cmd` bằng `mvn`.
Các unit test model cũ cũng chạy độc lập với database.
Không dùng `mvn test` để chỉ chạy hai lớp này: lệnh đó còn chạy
`PlannerApplicationTests`, lớp tải Spring context cần kết nối database thật.

## Chạy SQL thật trên PostgreSQL thử nghiệm

1. Chuẩn bị database `planner` chỉ chứa dữ liệu thử theo [DATABASE.md](DATABASE.md). MVP dùng chung đích này cho ứng dụng và test.
2. Tài khoản phải có quyền tạo bảng. Không cần tự chạy schema trước.
3. Đặt biến trong chính Terminal dùng chạy Maven:

   ```powershell
   $env:DB_URL = 'jdbc:postgresql://localhost:5432/planner'
   $env:DB_USERNAME = 'postgres'
   $env:DB_PASSWORD = 'THAY_BANG_MAT_KHAU_CUA_BAN'
   $env:TEST_DB_URL = $env:DB_URL
   $env:TEST_DB_USERNAME = $env:DB_USERNAME
   $env:TEST_DB_PASSWORD = $env:DB_PASSWORD
   .\mvnw.cmd test
   ```

Lệnh trên chạy toàn suite. Để chỉ chạy một lớp, dùng `.\mvnw.cmd '-Dtest=PlanRepositoryTest' test`.
Trên macOS/Linux, đặt các biến tương ứng và dùng `./mvnw test`. Xem số lỗi và test bỏ qua trong báo cáo Maven/Surefire.
Nếu chạy bằng Testing trong VS Code, cần cung cấp những biến này cho môi trường
chạy test; biến đặt trong Terminal không tự truyền vào extension đã mở.
Không commit mật khẩu vào repository.

Nếu chưa đặt `TEST_DB_URL`, các suite PostgreSQL và smoke cần opt-in sẽ bị **Skipped**.
Kiểm tra mục `Skipped` trong tổng kết Maven hoặc báo cáo dưới `target/surefire-reports/`.
Nếu đặt URL nhưng database không tồn tại hoặc sai mật khẩu, test báo lỗi thật.
Test chỉ nhận URL PostgreSQL có tên database chính xác `planner`, không nhận mọi tên tùy ý và không yêu cầu hậu tố `_test`. Đây là guard tên đích; việc database chỉ chứa dữ liệu thử dựa trên xác nhận người dùng, không phải guard tự chứng minh.

Các test repository chạy `schema.sql` và fixture trong giao dịch rồi rollback và đóng kết nối.
Bảng tạo mới trong giao dịch cũng rollback. Smoke và integration dùng fixture riêng
và dọn theo ID do test tạo.
Sequence ID có thể vẫn tăng; không yêu cầu ID liên tiếp hay bắt đầu từ 1.
Không có TRUNCATE hoặc DROP; kiểm tra cascade chỉ xóa Plan do chính test vừa tạo.

## Đọc một test mẫu

Mở `PlanRepositoryTest.insertReadUpdateDeleteAndPreserveCreationTime`:

1. Tạo Plan chỉ có tên, gọi `insert`, kiểm tra ID dương.
2. Đọc lại, kiểm tra goal/ngày là NULL và có createdAt.
3. Sửa tên, goal, ngày, đọc lại và kiểm tra createdAt không đổi.
4. Xóa, kiểm tra không tìm thấy; sửa/xóa lại trả false.

Để xem ví dụ không có DB, mở `PlanServiceTest.invalidInputNeverReachesInsert`:
test cố tạo dữ liệu sai, mong có IllegalArgumentException và xác nhận repository
không được gọi. Đây là mẫu cho các thành viên kiểm tra quy tắc của phần mình.

## Giới hạn

Các suite Plan/SubPlan lịch sử ở trên bao phủ CRUD thuộc tính. `PlanTreeIntegrationTest` nạp cây đã lưu qua service Spring thật và kiểm tra snapshot/lỗi đọc SQL. `TreeConcurrencyTest` dùng hai giao dịch PostgreSQL để kiểm tra các thao tác ghi cạnh tranh.

Chỉ khi nhóm SQL thật được chạy thành công mới được kết luận repository đã được
kiểm chứng trên PostgreSQL; unit test với đối tượng giả không đủ cho kết luận đó.

## Nghiệm thu MVP T043

Ngày 24/11/2026, toàn suite Maven trên JDK 17 và PostgreSQL thử đạt **228 test, 0 failure, 0 error, 0 skipped**. Đây là kết quả lịch sử.
Edge chạy luồng CRUD, kiểm tra giữ form lỗi, tầng 5/6, hộp thoại xóa, completion/undo và tiến độ.
Hai lần khởi động lại giữ nguyên bản ghi đã lưu. Đợt nghiệm thu cũng kiểm tra bản ứng dụng T001 chạy CRUD Plan/SubPlan trên cùng schema thử và đối chiếu các hàng đã lưu. Xem [quy trình kiểm chứng dữ liệu](DATABASE.md#kiểm-tra-khởi-động-hai-lần-và-giữ-dữ-liệu).

AI sinh mẫu kế hoạch, đăng ký/đăng nhập và bảo vệ dữ liệu là yêu cầu sản phẩm chưa triển khai trong MVP.

## Kiểm chứng loader/tiến độ ngày 17/11/2026 — T030–T034

PlanTreeServiceTest kiểm lắp cây, validation, reload counts, scope và giao dịch proxy;
ProgressPresentationTest kiểm empty/unavailable và làm tròn theo số lá.
ProgressReadWebTest kiểm snapshot metadata, HTTP 404/500/200 unavailable, không cây một phần.
TaskWebSmokeTest kiểm HTTP thật + PostgreSQL: năm tầng, counts 1/4, 1/3, 2/3,
Task độc lập không góp Plan và đọc lại sau CRUD. Toàn suite 207 test xanh, không skipped.

Loader mở giao dịch mới read-only REPEATABLE_READ cho mỗi entry; fallback xảy ra
sau khi thoát proxy. Bằng chứng hai connection được bổ sung ở T035–T036 ngày 23/11/2026.
Smoke của đợt này dùng fixture SQL cho done/completed_at.
MockMvc/render/HTTP không chứng minh JavaScript hoặc thao tác trình duyệt.

## Kiểm chứng đổi trạng thái SubTask ngày 22/11/2026

Smoke T029 dùng POST thật và PostgreSQL để kiểm tra đánh dấu, giữ timestamp khi
đánh dấu lặp hoặc sửa nội dung, xóa timestamp khi bỏ đánh dấu, rollback và trang
nguồn đọc lại trạng thái sau chuyển hướng.

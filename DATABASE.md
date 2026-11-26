# PostgreSQL cho Planner

Giữ nguyên Java 17 và Spring Boot hiện có. `spring-boot-starter-jdbc` tự cấu hình
`DataSource` và `JdbcTemplate`; PostgreSQL driver dùng lúc chạy. Không dùng JPA/Hibernate.

## Chuẩn bị và chạy

1. Khởi động PostgreSQL. Trong pgAdmin, kết nối server bằng tài khoản PostgreSQL của bạn.
2. Nhấn phải **Databases → Create → Database**, đặt tên `planner`, chọn Save.
3. Không cần chạy SQL tạo bảng thủ công. Database PostgreSQL phải tồn tại trước;
   ứng dụng tự chạy `src/main/resources/schema.sql` khi khởi động để tạo bảng còn thiếu.
4. Trong IntelliJ **Run → Edit Configurations → Environment variables**, đặt:
   - `DB_PASSWORD`: mật khẩu PostgreSQL thật của bạn.
   - `DB_USERNAME`: mặc định `postgres`, đổi nếu dùng tài khoản khác.
   - `DB_URL`: mặc định hiện tại `jdbc:postgresql://localhost:5432/planner`; đặt `jdbc:postgresql://localhost:5432/planner` nếu dùng database ở bước 2.
5. Chọn JDK 17 cho project và Maven. Chạy `PlannerApplication` từ IDE hoặc dùng Maven Wrapper trong PowerShell:

   ```powershell
   $env:DB_URL = 'jdbc:postgresql://localhost:5432/planner'
   $env:DB_PASSWORD = 'mat-khau-cua-ban'
   .\mvnw.cmd spring-boot:run
   ```

`spring.sql.init.mode=always` bật chạy schema khi khởi động, kể cả trên PostgreSQL.
Script tạo theo thứ tự `plan`, `sub_plan`, `task`, `sub_task`, dùng
`CREATE TABLE IF NOT EXISTS` để không lỗi chỉ vì bảng cùng tên đã tồn tại.
Không xóa/tạo lại bảng và không thêm dữ liệu mẫu. Không bật
`spring.sql.init.continue-on-error=true`; lỗi kết nối hoặc SQL khác vẫn phải được sửa.

`IF NOT EXISTS` không kiểm tra hay cập nhật cấu trúc bảng đã tồn tại. Nếu đổi
cột hoặc ràng buộc, cần lệnh cập nhật riêng như `ALTER TABLE`; bảng cũ không
tương thích vẫn có thể làm khởi động hoặc truy vấn thất bại.

Với cấu hình mới, test `contextLoads` cũng chạy khởi tạo SQL nên cần PostgreSQL
và thông tin kết nối hợp lệ. Các unit test model không tải Spring context nên
không cần DB. Context tải thành công không chứng minh cấu trúc bảng cũ đúng thiết kế.

Nếu `mvnw.cmd` báo `Cannot index into a null array` trước khi Maven chạy,
có thể chọn Maven đã cài trong IntelliJ và chạy goal `test`; không cần sửa
wrapper để bổ sung database. Đảm bảo Maven dùng JDK 17 trở lên.

## Kiểm tra khởi động hai lần và giữ dữ liệu

Dùng `planner` của bạn cho cả ứng dụng và kiểm thử MVP. Database này chỉ chứa dữ liệu thử.

1. Dùng database thử `planner` đã chuẩn bị ở trên. Có thể giữ dữ liệu thử đang có.
2. Đặt `DB_URL=jdbc:postgresql://localhost:5432/planner` trong cấu hình
   Run đang dùng (VS Code: `.vscode/launch.json`; IntelliJ: Run Configuration).
   Đặt `DB_USERNAME` và `DB_PASSWORD` đúng tài khoản có quyền tạo bảng.
3. Chạy `PlannerApplication`, chờ log `Started PlannerApplication`.
4. Mở Query Tool của **planner**, chạy:

   ```sql
   SELECT table_name
   FROM information_schema.tables
   WHERE table_schema = 'public'
     AND table_type = 'BASE TABLE'
     AND table_name IN ('plan', 'sub_plan', 'task', 'sub_task')
   ORDER BY table_name;
   ```

   Kết quả mong đợi: đủ 4 bảng. Đây là kết quả bạn cần xác nhận trên máy mình.
5. Dừng ứng dụng và khởi động lại với cùng DB_URL. Kết quả mong đợi:
   khởi động thành công, không lỗi `relation ... already exists`.
6. Để kiểm tra giữ dữ liệu, tự chạy lệnh sau trong Query Tool của database thử nghiệm
   (đây là dữ liệu kiểm tra do bạn chủ động thêm, không được thêm vào schema ứng dụng):

   ```sql
   INSERT INTO plan (title, goal)
   VALUES ('Kiem tra giu du lieu', 'Thu khoi dong lai')
   RETURNING id, title, goal, created_at;
   ```

   Bảo đảm INSERT đã commit (bật Auto commit của Query Tool, hoặc commit giao dịch
   của bạn). Ghi lại ID trả về. Dừng và khởi động lại ứng dụng, rồi chạy:

   ```sql
   SELECT id, title, goal, created_at
   FROM plan
   WHERE id = 123; -- Thay 123 bằng ID vừa ghi lại.
   ```

   Bản ghi với ID và nội dung vừa ghi lại phải còn nguyên. Có thể làm tương tự với
   dữ liệu các bảng khác. Không cần và không nên xóa bảng để kiểm tra khởi động lại.
7. Giữ cùng DB_URL trỏ `planner` khi tiếp tục phát triển; chỉ dọn các ID dữ liệu do chính kịch bản kiểm tra tạo nếu cần. Không xóa dữ liệu khác.

Ngày 24/11/2026, nghiệm thu MVP kiểm tra hai tiến trình khởi động lại trên cùng database `planner` thử. Đối chiếu trực tiếp bằng SQL cho thấy ID, quan hệ, nội dung, `created_at` và trạng thái/timestamp hoàn thành vẫn giữ nguyên. Bản chụp T001 chạy từ thư mục riêng, CRUD Plan/SubPlan vẫn hoạt động và dữ liệu Task/SubTask giữ nguyên. Những ID đã xóa vẫn vắng mặt sau rollback ứng dụng. Xem [tóm tắt nghiệm thu](TESTING.md#nghiệm-thu-mvp-t043).

## Xem dữ liệu trong pgAdmin

Refresh **Databases → planner → Schemas → public → Tables**. Có 4 bảng:
`plan`, `sub_plan`, `task`, `sub_task`. Nhấn phải bảng → **View/Edit Data → All Rows**.
Database mới không có dữ liệu mẫu. Tạo dữ liệu thử qua giao diện CRUD của cả bốn loại đối tượng, bắt đầu ở `/plans` hoặc `/tasks`.
Hoặc mở Query Tool và chạy:

```sql
SELECT * FROM plan ORDER BY id;
SELECT * FROM sub_plan ORDER BY order_index, id;
SELECT * FROM task ORDER BY order_index, id;
SELECT * FROM sub_task ORDER BY order_index, id;
```

## Phạm vi thiết kế

Đối chiếu [schema.sql](src/main/resources/schema.sql): có 4 bảng,
khóa ngoại và xóa cascade, SubPlan cha/con cùng Plan; Task có cha Plan,
cha SubPlan hoặc độc lập (cả hai khóa cha là NULL). Nơi chứa Task chỉ chọn khi tạo.
SubTask luôn thuộc một Task. Ngày có thể NULL và `done`/`completed_at` phải nhất quán. Không lưu progress,
level hoặc week_number. Các bảng nằm trong schema mặc định `public` trên
cấu hình PostgreSQL thông thường.

SubPlanService đã kiểm tra vòng lặp và tối đa 5 tầng kể cả con/cháu khi chuyển
nhánh, dùng giao dịch/khóa Plan và cảnh báo ngày SubPlan ngoài khoảng cha.
SQL chỉ chặn tự tham chiếu trực tiếp, không thay thế kiểm tra cây của service.
Giao dịch đổi trạng thái SubTask khóa theo Task cha rồi đọc lại lá. Câu UPDATE
completion chỉ ghi `done` và `completed_at`. Đánh dấu lặp giữ timestamp hiện có
và bỏ đánh dấu đặt timestamp thành NULL. UPDATE nội dung giữ cả hai trường.

Tiến độ được tính từ các SubTask lá trong snapshot đọc của ứng dụng. Xem [luồng Plan](PLAN.md) và [luồng SubPlan](SUBPLAN.md).

Schema chưa có tài khoản/chủ sở hữu; đăng ký/đăng nhập và bảo vệ dữ liệu là bắt buộc
nhưng chưa triển khai. Phải chốt xử lý dữ liệu cũ và phân tích migration/rollback
trước thay đổi tương ứng; xem [phạm vi MVP](README.md#tài-liệu-nghiệp-vụ-và-phạm-vi).

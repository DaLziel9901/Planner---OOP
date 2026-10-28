# PostgreSQL cho Planner

Giữ nguyên Java 17 và Spring Boot hiện có. `spring-boot-starter-jdbc` tự cấu hình
`DataSource` và `JdbcTemplate`; PostgreSQL driver dùng lúc chạy. Không dùng JPA/Hibernate.

## Chuẩn bị và chạy

1. Khởi động PostgreSQL. Trong pgAdmin, kết nối server bằng tài khoản PostgreSQL của bạn.
2. Nhấn phải **Databases → Create → Database**, đặt tên `planner`, chọn Save.
3. Chọn database `planner` → **Tools → Query Tool**. Mở file
   `src/main/resources/schema.sql` và Execute toàn bộ một lần trên database mới.
   Script có transaction để tránh tạo dở các bảng nếu có lỗi; không chạy lại trên
   database đã có các bảng này. Không xóa bảng cũ nếu đang có dữ liệu cần giữ.
4. Trong IntelliJ **Run → Edit Configurations → Environment variables**, đặt:
   - `DB_PASSWORD`: mật khẩu PostgreSQL thật của bạn.
   - `DB_USERNAME`: mặc định `postgres`, đổi nếu dùng tài khoản khác.
   - `DB_URL`: mặc định `jdbc:postgresql://localhost:5432/planner`, đổi nếu server/cổng/database khác.
5. Chọn JDK 17 hoặc mới hơn cho project và Maven, rồi chạy `PlannerApplication`
   (package `com.example.planner`) như trước. Hoặc trong PowerShell với JDK phù hợp:

   ```powershell
   $env:DB_PASSWORD = 'mat-khau-cua-ban'
   .\mvnw.cmd test
   .\mvnw.cmd spring-boot:run
   ```

`spring.sql.init.mode=never` nghĩa là ứng dụng không tự tạo hay sửa bảng khi khởi động.
Database và bảng phải được tạo trước khi truy vấn dữ liệu. Test `contextLoads`
chỉ tải Spring context, không thực hiện truy vấn nên không chứng minh kết nối
PostgreSQL, SQL tạo bảng hay các ràng buộc hoạt động.

Nếu `mvnw.cmd` báo `Cannot index into a null array` trước khi Maven chạy,
có thể chọn Maven đã cài trong IntelliJ và chạy goal `test`; không cần sửa
wrapper để bổ sung database. Đảm bảo Maven dùng JDK 17 trở lên.

## Xem dữ liệu trong pgAdmin

Refresh **Databases → planner → Schemas → public → Tables**. Có 4 bảng:
`plan`, `sub_plan`, `task`, `sub_task`. Nhấn phải bảng → **View/Edit Data → All Rows**.
Ban đầu các bảng trống vì chưa thêm dữ liệu mẫu hay chức năng CRUD.
Hoặc mở Query Tool và chạy:

```sql
SELECT * FROM plan ORDER BY id;
SELECT * FROM sub_plan ORDER BY order_index, id;
SELECT * FROM task ORDER BY order_index, id;
SELECT * FROM sub_task ORDER BY order_index, id;
```

## Phạm vi thiết kế

`schema.sql` giữ nguyên 4 bảng và các ràng buộc ở mục 6 của Instruction:
khóa ngoại và xóa cascade, SubPlan cha/con cùng Plan, ba nơi chứa Task,
ngày có thể NULL, trạng thái done/completed_at nhất quán. Không lưu progress,
level hoặc week_number. Các bảng nằm trong schema mặc định `public` trên
cấu hình PostgreSQL thông thường.

Service Java sau này phải kiểm tra vòng lặp, tối đa 5 tầng kể cả khi chuyển nhánh,
cảnh báo ngày con ngoài ngày cha và giữ completed_at khi tick lại.
SQL hiện chỉ chặn tự tham chiếu trực tiếp của SubPlan; không thay thế các kiểm tra service đó.

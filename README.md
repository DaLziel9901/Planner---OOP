# Planner

Dự án Java Spring Boot cho ứng dụng lập kế hoạch mục tiêu phân cấp.

Luồng CRUD Plan có trang tại `http://localhost:8080/plans`, liên kết tới SubPlan.
Xem [PLAN.md](PLAN.md) và [SUBPLAN.md](SUBPLAN.md) để biết các file, cách chạy và kiểm thử.

Từ đợt T011–T014 ngày 16/11/2026, CRUD Task độc lập có tại
`http://localhost:8080/tasks`: tạo, xem, sửa và xóa mà không cần Plan/SubPlan.
Tên được trùng, mô tả/ngày được trống, thứ tự mặc định 0; xóa có xác nhận.
Từ T015–T017 ngày 16/11/2026, Task cũng được tạo trực tiếp dưới Plan hoặc SubPlan;
trang cha có liên kết xem/thêm Task trực tiếp. `/tasks` vẫn chỉ liệt kê Task độc lập;
`/tasks?planId=ID` và `/tasks?subPlanId=ID` lọc Task trực tiếp của từng cha.
Nơi chứa chỉ chọn lúc tạo, sửa nội dung không chuyển Task.
Từ T018–T024 ngày 16/11/2026, trang Task có CRUD SubTask và liên kết tới
`/subtasks?taskId=ID`. SubTask bắt buộc thuộc một Task, ngày dự kiến tùy chọn;
sửa không chuyển Task hoặc thay trạng thái hoàn thành, xóa Task cascade các lá.
Có thể đánh dấu hoặc bỏ đánh dấu hoàn thành ở chi tiết SubTask, danh sách SubTask
và chi tiết Task. Đánh dấu lặp giữ thời điểm hoàn thành đầu tiên, bỏ đánh dấu xóa thời điểm đó.
Từ T030–T034 ngày 17/11/2026,
loader đọc cây Plan/SubPlan/Task/SubTask trong snapshot read-only và hiển thị tiến độ
theo số SubTask lá đã hoàn thành/tổng lá. Cây rỗng hiện “Chưa có công việc”;
lỗi đọc cây hiện tiến độ chưa khả dụng, không trả cây một phần.
Khi có lá, tiến độ là số SubTask hoàn thành chia tổng số SubTask lá, nhân 100%.
Task rỗng không làm tăng mẫu số. Tỷ lệ chỉ làm tròn ở kết quả cuối, tối đa hai chữ số thập phân.
Sau khi đổi trạng thái, trang đang thao tác hiển thị dữ liệu mới khi chuyển hướng.
Các trang khác cập nhật khi mở lại.

## Tài liệu nghiệp vụ và phạm vi

Baseline MVP T043 gồm CRUD Plan/SubPlan/Task/SubTask, completion tại SubTask
và tiến độ tổng hợp từ dữ liệu đã lưu. Xem [hướng dẫn kiểm thử và kết quả nghiệm thu](TESTING.md).

SubPlan trực tiếp dưới Plan là tầng 1. Cây có tối đa 5 tầng SubPlan, cha phải cùng Plan
và việc đổi cha phải kiểm tra vòng lặp cùng độ sâu của toàn nhánh. Tên của cả bốn loại
được trùng, dài 1–255 ký tự và có ít nhất một ký tự khác khoảng trắng. Mô tả, mục tiêu
và từng mốc ngày đều có thể để trống. Nếu có đủ hai mốc thì ngày bắt đầu không sau
ngày kết thúc. Ngày SubPlan ngoài khoảng cha chỉ tạo cảnh báo. Task và SubTask vẫn
lưu khi ngày ngoài khoảng cha, không cảnh báo hay tự chỉnh ngày.

AI sinh mẫu kế hoạch từ yêu cầu ngôn ngữ tự nhiên và đăng ký/đăng nhập cùng bảo vệ
tài khoản, dữ liệu là **bắt buộc**, xác nhận ngày 10/11/2026, **chưa triển khai**.
Source hiện tại chưa có mô hình chủ sở hữu và kiểm soát truy cập dữ liệu riêng.

## 1. Yêu cầu

- JDK 17
- PostgreSQL. Có thể dùng pgAdmin để tạo database và xem dữ liệu.
- Git.
- IDE tùy chọn, chẳng hạn IntelliJ IDEA hoặc VS Code hỗ trợ Java.

Repo cần có `.mvn/`, `mvnw`, `mvnw.cmd` và `pom.xml` để dùng Maven Wrapper, không bắt buộc cài Maven riêng. Lần chạy đầu cần Internet để tải Maven/dependency.

## 2. Clone repo
```powershell
git clone --branch db REPO_URL planner
cd planner
```

Thay `REPO_URL` bằng URL repository của nhóm. Kiểm tra `java -version` và chọn JDK 17 cho terminal hoặc IDE.

## 3. Tạo database bằng pgAdmin

1. Khởi động PostgreSQL, mở pgAdmin và kết nối server bằng tài khoản PostgreSQL của bạn.
2. Nhấn phải Databases → Create → Database, đặt tên `planner`, chọn Save.
3. Cấu hình kết nối ở mục 4 rồi khởi chạy ứng dụng theo mục 5.
4. Ứng dụng tự chạy `src/main/resources/schema.sql` với
   `spring.sql.init.mode=always`, tạo những bảng còn thiếu theo thứ tự
   `plan` → `sub_plan` → `task` → `sub_task`.

Database PostgreSQL phải được tạo trước; không cần chạy schema thủ công trong pgAdmin.
Các bảng đã tồn tại và dữ liệu được giữ nguyên nhờ `CREATE TABLE IF NOT EXISTS`.
Ứng dụng không xóa bảng, không thêm dữ liệu mẫu và không bỏ qua lỗi SQL khác.

`IF NOT EXISTS` chỉ bỏ qua việc tạo bảng cùng tên, không kiểm tra hoặc cập nhật
cấu trúc bảng đã tồn tại. Khi đổi cột/ràng buộc, phải có lệnh cập nhật riêng
(ví dụ `ALTER TABLE`). Bảng cũ không tương thích vẫn có thể gây lỗi khi tạo bảng
liên quan hoặc khi truy vấn. Xem [DATABASE.md](DATABASE.md) để kiểm tra hai lần
khởi động và dữ liệu được giữ nguyên.

## 4. Cấu hình kết nối

Mỗi người dùng database và mật khẩu trên máy mình. Hướng dẫn này chọn database
`planner`: đây là database dùng chung cho ứng dụng và kiểm thử, được người dùng xác nhận chỉ chứa dữ liệu thử ngày 2026-11-10. URL mặc định là `jdbc:postgresql://localhost:5432/planner`; database phải tồn tại. Không tạo database test thứ hai.
`src/main/resources/application.properties` đọc các biến môi trường:

| Biến | Giá trị mặc định | Cách dùng |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/planner` | Mặc định cấu hình hiện tại; đặt rõ nếu dùng database khác |
| `DB_USERNAME` | `postgres` | Đổi nếu dùng tài khoản khác |
| `DB_PASSWORD` | Không có | Đặt mật khẩu của tài khoản PostgreSQL |

Trong Terminal PowerShell:

```powershell
$env:DB_PASSWORD = 'THAY_BANG_MAT_KHAU_CUA_BAN'
# Chỉ cần đặt thêm hai biến dưới đây nếu khác giá trị mặc định:
$env:DB_USERNAME = 'postgres'
$env:DB_URL = 'jdbc:postgresql://localhost:5432/planner'
```

Các biến chỉ có hiệu lực trong Terminal hiện tại và chương trình chạy từ đó.
Không ghi mật khẩu thật vào file được commit. Dự án chưa có cơ chế tự đọc `.env`.

## 5. Khởi chạy và kiểm thử

Từ thư mục gốc, sau khi đặt biến môi trường và khởi động PostgreSQL:

```powershell
.\mvnw.cmd spring-boot:run
```

Mở `http://localhost:8080/plans` hoặc `http://localhost:8080/tasks`. Trang gốc `/` có thể trả 404.
Dừng ứng dụng bằng Ctrl+C. Chạy `.\mvnw.cmd test` để chạy suite Maven và đặt thêm
`TEST_DB_*` theo [TESTING.md](TESTING.md) để bật các test PostgreSQL và smoke.
Trên macOS/Linux, dùng `./mvnw` với các biến môi trường tương ứng.

### Chạy qua VS Code (tùy chọn)

Tạo `.vscode/launch.json` tại gốc repository:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "PlannerApplication",
      "request": "launch",
      "mainClass": "com.example.planner.PlannerApplication",
      "env": {
        "DB_URL": "jdbc:postgresql://localhost:5432/planner",
        "DB_USERNAME": "postgres",
        "DB_PASSWORD": "THAY_BANG_MAT_KHAU_CUA_BAN"
      }
    }
  ]
}
```

Thay các giá trị theo máy bạn rồi lưu. Mở **Run and Debug**, chọn
**PlannerApplication** → `F5`. Nếu file đã có cấu hình, thêm khối vào mảng
`configurations` thay vì ghi đè toàn bộ file. Thư mục `.vscode/` được bỏ qua bởi `.gitignore`.

Khởi động thành công khi log có `using Java 17`, `Tomcat started on port 8080`
và `Started PlannerApplication`. Địa chỉ ứng dụng là `http://localhost:8080`.
Trang gốc `/` có thể trả về 404 vì chưa có mapping trang chủ. Mở `/plans`
để dùng CRUD Plan và từ đó chuyển sang `/subplans`.

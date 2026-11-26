# Luồng Plan

Plan là gốc của cây và có thể chứa Task trực tiếp cùng SubPlan tầng 1.
`PlanTreeService` đọc cây theo lô trong giao dịch read-only `REPEATABLE_READ`.
Danh sách và chi tiết Plan hiển thị tiến độ theo số SubTask lá đã hoàn thành trên tổng số lá.
Cây rỗng hiện “Chưa có công việc”. Khi chỉ phần đọc cây gặp lỗi, trang giữ thông tin
chính đã đọc được và hiện “Chưa có tiến độ khả dụng”. Đổi trạng thái hoàn thành qua các trang Task/SubTask.

## File và trách nhiệm

| File dưới `src/main/` | Trách nhiệm |
| --- | --- |
| `java/com/example/planner/repository/PlanRepository.java` | SQL CRUD, ánh xạ cột, nhận ID PostgreSQL sinh; không tự nạp con |
| `java/com/example/planner/service/PlanService.java` | Kiểm tra dữ liệu, xử lý không tìm thấy, gọi repository |
| `java/com/example/planner/controller/PlanController.java` | URL, nhận chuỗi form, giữ dữ liệu sai, chuyển hướng sau ghi |
| `resources/templates/plans/list.html` | Danh sách, trạng thái rỗng, liên kết thêm/sửa/chi tiết và SubPlan |
| `resources/templates/plans/form.html` | Form chung thêm/sửa, giá trị đã nhập, thông báo lỗi |
| `resources/templates/plans/detail.html` | Thuộc tính Plan, thời điểm tạo, liên kết SubPlan và xác nhận xóa |

Các template dùng `th:text`/`th:value` để escape nội dung người dùng, không render
goal/title thành HTML tùy ý. Ngày nhập bằng text để giữ được cả chuỗi ngày sai
khi server trả form. Java và PostgreSQL vẫn kiểm tra dữ liệu, không chỉ dựa vào HTML.

## URL

| HTTP | URL | Thao tác |
| --- | --- | --- |
| GET | `/plans` | Danh sách |
| GET | `/plans/new` | Form thêm |
| POST | `/plans` | Tạo và chuyển tới `/plans/{id}` |
| GET | `/plans/{id}` | Chi tiết thuộc tính |
| GET | `/plans/{id}/edit` | Form sửa |
| POST | `/plans/{id}/edit` | Sửa và chuyển tới chi tiết |
| POST | `/plans/{id}/delete` | Xóa và chuyển về danh sách |

Form gửi `title`, `goal`, `startDate`, `endDate`. Không nhận ID hoặc createdAt làm
giá trị cần cập nhật. Tên có 1–255 ký tự, không chỉ khoảng trắng; được trùng tên.
Goal/ngày được trống; đủ hai ngày thì startDate <= endDate. UPDATE không đổi
created_at. Xóa dùng ON DELETE CASCADE, không viết lại việc xóa từng bảng.
ID không tồn tại trả HTTP 404; chưa thêm template lỗi 404 riêng.

## Chạy thử

1. Chuẩn bị PostgreSQL và đặt `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` theo [README.md](README.md).
2. Từ thư mục gốc chạy `.\mvnw.cmd spring-boot:run`, rồi mở `http://localhost:8080/plans`.
3. Thêm chỉ có tên; thử thêm Plan khác cùng tên. Cả hai phải có ID khác nhau.
4. Thêm goal và ngày, mở chi tiết, sau đó sửa; kiểm tra created_at giữ nguyên trong pgAdmin.
5. Để tên chỉ có khoảng trắng hoặc nhập ngày sai: form phải giữ giá trị và hiện lỗi.
   Trình duyệt có thể chặn ô tên rỗng trước khi gửi; test JUnit kiểm tra thêm phía server.
6. Từ chi tiết chọn Thêm SubPlan hoặc Xem các SubPlan để tiếp tục luồng phân cấp.
7. Trên database thử nghiệm, xóa Plan có cây con: hủy hộp thoại phải giữ dữ liệu;
   xác nhận thì toàn bộ nhánh bị xóa. Không thử xóa trên dữ liệu quan trọng.
8. Khởi động lại ứng dụng, kiểm tra các bản ghi chưa xóa vẫn còn.

## Test không cần giao diện trình duyệt

Các file dưới `src/test/java/com/example/planner/`:

- `controller/PlanViewTest.java`: render Thymeleaf thật với service giả, kiểm tra
  cả ba trang, lỗi form, escape nội dung, liên kết, thông báo và form POST xác nhận xóa.
- `controller/PlanControllerTest.java`: MockMvc kiểm tra URL, POST, ID, chuyển hướng, 404.
- `service/PlanServiceTest.java`: quy tắc nhập liệu và điều phối với repository giả.
- `repository/PlanRepositoryTest.java`: SQL thật khi đã cấu hình TEST_DB_URL;
  xem TESTING.md để dùng chung database planner dữ liệu thử và rollback sau test.

```powershell
.\mvnw.cmd "-Dtest=PlanViewTest,PlanControllerTest,PlanServiceTest" test
```

Dùng JDK 17; có thể thay wrapper bằng `mvn`. MockMvc render không chạy JavaScript,
nên không chứng minh hộp thoại xác nhận hoạt động trên trình duyệt thật.
SQL test mới chứng minh câu SQL; test dùng service/repository giả không thay thế SQL test.

Xem [TESTING.md](TESTING.md) để chạy toàn suite và đọc kết quả nghiệm thu MVP T043.

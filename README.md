# Planner

Planner là ứng dụng quản lý kế hoạch phân cấp với Java 17, Spring Boot, Thymeleaf và PostgreSQL. Người dùng quản lý cây Plan, SubPlan, Task và SubTask, đánh dấu hoàn thành ở SubTask và xem tiến độ tổng hợp theo số lá.

## Trạng thái nhánh feature/auth

Bản backend có đăng ký, đăng nhập, đăng xuất, phân quyền theo chủ sở hữu và luồng AI tạo bản nháp, chỉnh sửa, xác nhận lưu cả cây. Nghiệm thu ghi nhận **277/277 ca regression** và **4/4 ca PostgreSQL chạy riêng**, đều không có failure, error hoặc skip. Người dùng đã kiểm tra CRUD, tiến độ, cách ly hai tài khoản, persistence sau restart và luồng AI với Groq thật, model `openai/gpt-oss-20b`.

Hồ sơ 003 có 24 completed, 15 partial và 1 open. Hồ sơ 004 có 6 completed, 1 partial và 0 open. Các mục partial gồm kiểm chứng bổ sung cho phiên/ownership/CSRF, tài liệu và xác nhận hợp đồng. Browser acceptance trên bản ghép `feature/ui` vẫn mở. Giao diện AI hiện chỉnh bản nháp qua textarea JSON. [Báo cáo nghiệm thu](COMPLETION_REPORT.md)

## Chạy trên môi trường thử

1. Dùng JDK 17 và Maven Wrapper đi kèm. PostgreSQL phải có database thử `planner_auth_test`, role riêng và catalog 003 đã chuẩn bị. Ứng dụng xác minh đích và catalog khi khởi động. SQL init tự động đang tắt. Xem [quy trình cho đích thử trống và regression](DEMO_GUIDE.md).
2. Đặt `DB_URL`, `DB_USERNAME` và `DB_PASSWORD` trong môi trường terminal hoặc cấu hình chạy cục bộ. Với test PostgreSQL, đặt thêm `TEST_DB_URL`, `TEST_DB_USERNAME` và `TEST_DB_PASSWORD` cùng đích và role thử.
3. Để dùng provider thật, đặt `AI_BASE_URL` là base HTTPS, `AI_MODEL` và `AI_API_KEY`. Để thử schema phẳng với Groq, dùng base `https://api.groq.com/openai/v1`, model `openai/gpt-oss-120b` và `AI_FLAT_SCHEMA=true`. Cờ này chọn Strict Mode với schema phẳng. Khi tắt cờ, `AI_STRICT_SCHEMA=true` chọn schema đệ quy, còn mặc định là `json_object`. Giữ credential ngoài Git.
4. Chạy `./mvnw spring-boot:run` trên macOS/Linux hoặc lệnh PowerShell dưới đây. Mở `http://localhost:8080/`. Khách được chuyển tới `/login`. Đăng ký tại `/register`. Sau login, vào `/plans` hoặc tạo bản nháp tại `/ai/plans/new`.

```powershell
.\mvnw.cmd spring-boot:run
```

## Quy tắc nghiệp vụ

- SubPlan trực tiếp dưới Plan là tầng 1. Cây có tối đa 5 tầng SubPlan. Khi đổi cha, backend kiểm cùng Plan, vòng lặp và độ sâu của cả nhánh.
- Task có thể độc lập hoặc nằm trực tiếp dưới một Plan hay SubPlan. Nơi chứa giữ nguyên khi sửa. SubTask thuộc một Task và là loại duy nhất có thao tác đánh dấu hoàn thành.
- Ngày có thể trống. Khi đủ hai mốc, ngày bắt đầu phải không sau ngày kết thúc. Ngày SubPlan ngoài khoảng cha tạo cảnh báo và vẫn lưu được. Task và SubTask giữ ngày người dùng nhập.
- Tiến độ đếm SubTask hoàn thành trên tổng số SubTask lá. Cây rỗng và snapshot chưa đọc đủ có trạng thái riêng.

Xem [kiến trúc](ARCHITECTURE.md), [hợp đồng giao diện](UI_BACKEND_CONTRACT.md) và [hướng dẫn demo](DEMO_GUIDE.md) cho nhánh này. [PLAN.md](PLAN.md), [SUBPLAN.md](SUBPLAN.md), [DATABASE.md](DATABASE.md) và [TESTING.md](TESTING.md) ghi hành vi và quy trình lịch sử của MVP T043. Các bước dùng database `planner` hoặc SQL init tự động trong tài liệu MVP áp dụng cho checkout MVP.

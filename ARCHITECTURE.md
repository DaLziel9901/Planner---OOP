# Kiến trúc Planner

Planner dùng Java 17, Spring Boot MVC, Spring Security, Thymeleaf, Spring JDBC và PostgreSQL. Controller quản lý HTTP và model view. Service quản lý nghiệp vụ và transaction. Repository dùng SQL có tham số. Các lớp miền triển khai `PlanNode` để xử lý cây Plan, SubPlan, Task và SubTask.

## CRUD và tiến độ

`PlanService`, `SubPlanService`, `TaskService` và `SubTaskService` kiểm tra cấu trúc trước ghi. Khóa Plan điều phối ghi cây. Ghi Task và SubTask theo thứ tự Plan rồi Task khi Task có Plan gốc. Ghi Task độc lập dùng khóa Task. `PlanTreeService` đọc snapshot theo owner trong transaction read-only `REPEATABLE_READ`, ghép cây và tính tiến độ theo lá. `ProgressPresentation` cung cấp `AVAILABLE`, `EMPTY` và `UNAVAILABLE`. Các đường đọc metadata khi snapshot lỗi tiếp tục dùng cùng phạm vi owner.

## Tài khoản, ownership và schema

`RegistrationService` chuẩn hóa username, kiểm mật khẩu nguyên văn và hash BCrypt trước khi gọi `UserRepository`. UNIQUE của PostgreSQL phân xử username trùng. `PlannerUserDetailsService` tạo `PlannerPrincipal` với ID tài khoản. `SecurityConfig` quản lý form login, chống session fixation, logout và CSRF. Timeout không hoạt động được cấu hình 30 phút. `CurrentUserModel` cung cấp username cho view.

Schema 003 có năm bảng nghiệp vụ. Plan và Task độc lập mang owner trực tiếp. SubPlan, Task có cha và SubTask kế thừa owner từ gốc. Controller lấy owner từ principal và truyền qua service tới SQL cho list, cha, ghi, khóa, completion và đọc cây. Tài nguyên ngoài phạm vi dùng cùng đường xử lý với tài nguyên thiếu. Rehearsal migration giữ các hàng MVP và để owner cũ trống.

`DatabaseTargetGuard` kiểm danh tính mỗi kết nối và catalog trước startup. Runtime dùng `planner_auth_test` đã có catalog 003, với SQL init tự động tắt. `AuthTestDatabase` cung cấp đường chạy bootstrap và rehearsal có guard trên đích thử. Foundation, rehearsal, ownership JDBC và bootstrap đã có kết quả PostgreSQL.

## Bản nháp AI

`OpenAiCompatiblePlanProvider` gửi prompt và bốn trường người dùng nhập qua HTTPS, với timeout, giới hạn phản hồi và không retry tự động. `PlanDraftService` xác thực JSON, ngày, kích thước và cấu trúc. `AiPlanController` giữ một bản nháp trong mỗi phiên, kèm owner và UUID. Preview hoặc edit thành công cấp UUID mới. Khi xác nhận, `GeneratedPlanService` kiểm lại JSON rồi gọi các phương thức chèn có owner trong một transaction. Phiên tiêu thụ bản nháp sau khi lời gọi lưu thành công.

Regression đã kiểm luồng HTTP với provider giả và rollback trên PostgreSQL. Browser acceptance trên `feature/auth` xác nhận luồng với Groq thật và persistence sau restart. Các template hiện tại là điểm tham chiếu để tích hợp layout và tài sản tĩnh của `feature/ui`. [Bằng chứng và phần còn lại](COMPLETION_REPORT.md) · [Hợp đồng UI](UI_BACKEND_CONTRACT.md)

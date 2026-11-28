# Planner · Bài tập lớn OOP

Planner giúp lập kế hoạch theo cây **Mục tiêu → Mục tiêu nhỏ → Task → Sub Task**. Bạn có thể tạo kế hoạch thủ công hoặc dùng AI để tạo bản nháp, xem và chỉnh sửa trước khi lưu.

## Chức năng và công nghệ

- Đăng ký, đăng nhập, đăng xuất. Mỗi tài khoản chỉ truy cập dữ liệu của mình.
- Tạo, xem, sửa và xóa mục trong cây. Task cũng có thể đứng độc lập. Đánh dấu hoàn thành ở Sub Task và xem tiến độ tổng hợp theo số Sub Task.
- Xem lịch, thống kê và nhắc nhở. Tạo bản nháp AI với Groq, chỉnh sửa rồi xác nhận lưu.
- Java 17, Spring Boot 4.1.1, Spring MVC, Spring Security, Spring JDBC, Thymeleaf, PostgreSQL và Maven Wrapper. Mô hình `PlanNode` dùng Composite Pattern.

## Cài đặt trên Windows

1. Giải nén toàn bộ ZIP. Cài **JDK 17**, kiểm tra `javac -version` và đặt `JAVA_HOME` nếu terminal chưa nhận JDK. Cài **PostgreSQL** và khởi động dịch vụ. Có thể dùng pgAdmin để chuẩn bị đích. Lần chạy Maven đầu tiên cần Internet để tải Maven và dependency.
2. Trong pgAdmin, dưới server PostgreSQL, mở **Login/Group Roles → Create → Login/Group Role**. Đặt tên `planner_app`, nhập mật khẩu riêng ở **Definition**, bật **Can login** và giữ **Superuser** tắt ở **Privileges**. Dùng tài khoản quản trị PostgreSQL để tạo role/database trong pgAdmin.
3. Chọn **Databases → Create → Database**, đặt tên **`planner_release`** và chọn owner **`planner_app`**. Tạo database mới, để schema `public` rỗng và dùng search path mặc định của role mới. Các bảng được tạo tự động ở bước tiếp theo.
4. Mở PowerShell tại thư mục chứa `pom.xml` và chạy:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\run.ps1
```

`setup.ps1` hỏi host, port, role và mật khẩu PostgreSQL. Nhấn Enter để dùng host `localhost`, port `5432` và role `planner_app`. Bootstrap xác minh đích và quyền kết nối, rồi tạo bảng tài khoản và bốn bảng Planner trong một transaction. Nếu schema đã có catalog Planner phù hợp, setup chỉ xác nhận lại. Nếu schema có đối tượng nhưng chưa tương thích, setup dừng. `run.ps1` chỉ khởi động ứng dụng, không chạy bootstrap.

Khi Spring Boot báo đã khởi động, mở **http://localhost:8080/**. Nhấn Ctrl+C để dừng. Chạy lại setup nếu đổi kết nối hoặc muốn thêm/thay API key.

### Cấu hình Groq

Khi setup hỏi **Groq API key**, nhập key của bạn hoặc nhấn Enter để bỏ qua AI. Tạo kế hoạch thủ công vẫn dùng được khi chưa có key. Setup tự dùng các giá trị sau:

| Biến | Giá trị mặc định trong setup |
| --- | --- |
| `AI_BASE_URL` | `https://api.groq.com/openai/v1` |
| `AI_MODEL` | `openai/gpt-oss-120b` |
| `AI_STRICT_SCHEMA` | `true` |
| `AI_FLAT_SCHEMA` | `true` |

Cấu hình được lưu ở `%LOCALAPPDATA%\Planner\settings.json`. Mật khẩu và API key được mã hóa bằng cơ chế bảo vệ của Windows cho tài khoản đang dùng. Chạy setup và run bằng cùng tài khoản. Mẫu `planner.config.example.json` chỉ chứa giá trị công khai.

## Sử dụng

1. Mở trang **Đăng ký**, tạo tài khoản Planner rồi **Đăng nhập**. Dùng tài khoản PostgreSQL riêng ở bước cài đặt và tài khoản Planner của bạn trên trình duyệt.
2. Chọn **+ Thêm mục tiêu**, nhập tên và lưu. Bạn có thể thêm mục tiêu nhỏ hoặc Task dưới mục tiêu, sau đó thêm Sub Task và đánh dấu hoàn thành để cập nhật tiến độ.
3. Để dùng AI, chọn **Tạo kế hoạch bằng AI**, nhập mục tiêu, trình độ, số ngày và ghi chú nếu cần. Xem cây, chỉnh bản nháp nếu muốn rồi chọn **Lưu kế hoạch**. Chỉ bước xác nhận mới ghi cây vào database.

## Lỗi thường gặp

| Hiện tượng | Cách xử lý |
| --- | --- |
| Báo thiếu JDK hoặc sai phiên bản | Kiểm tra `javac -version` và `JAVA_HOME` trong terminal mới. Bộ cài kiểm JDK 17. |
| Maven Wrapper chưa tải được | Kiểm tra Internet và proxy. Nếu Wrapper vẫn lỗi, dùng Maven cài sẵn như hướng dẫn chạy thủ công. |
| PostgreSQL báo không kết nối được hoặc sai mật khẩu | Kiểm tra dịch vụ, host, port, role có LOGIN và mật khẩu. |
| Báo chưa có catalog 003 | Chạy lại setup trên `planner_release` mới. Với schema cũ hoặc không tương thích, giữ dữ liệu và nhờ quản trị viên chuẩn bị đích phù hợp. |
| Port 8080 đang bận | Dừng ứng dụng khác đang giữ cổng hoặc chạy thủ công với `$env:SERVER_PORT = '8081'` rồi mở cổng đó. |
| AI chưa cấu hình hoặc Groq trả lỗi | Chạy lại setup với key hợp lệ. Nếu còn lỗi, xem dòng `AI provider HTTP error` trong terminal. |
| Windows chặn script | Dùng hai lệnh PowerShell ở trên, với tùy chọn chỉ áp dụng cho tiến trình chạy script. |

## Chạy thủ công

Chuẩn bị role và database `planner_release` như trên. Từ PowerShell tại thư mục source, cấu hình biến cho tiến trình hiện tại. Ví dụ sau dùng host và role mặc định:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/planner_release'
$env:DB_USERNAME = 'planner_app'
$env:DB_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Mật khẩu PostgreSQL' -AsSecureString)).Password

# Chạy trên database mới để tạo schema
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--planner-bootstrap'
if ($LASTEXITCODE -ne 0) { throw 'Khởi tạo database thất bại' }

# Tùy chọn AI. Bỏ qua khối này nếu chỉ dùng Planner thủ công
$env:AI_BASE_URL = 'https://api.groq.com/openai/v1'
$env:AI_MODEL = 'openai/gpt-oss-120b'
$env:AI_STRICT_SCHEMA = 'true'
$env:AI_FLAT_SCHEMA = 'true'
$env:AI_API_KEY = [Net.NetworkCredential]::new('', (Read-Host 'Groq API key' -AsSecureString)).Password

.\mvnw.cmd spring-boot:run
```

Nếu Wrapper không chạy, dùng `mvn` từ Maven đã cài để thực thi hai lệnh Spring Boot. Đóng terminal sau khi kết thúc để giải phóng các biến chứa credential.

## Kiểm thử và đóng gói

Hồi quy trên môi trường đã chuẩn bị **riêng** cho test dùng `planner_auth_test`. Đặt `DB_*` và `TEST_DB_*` trỏ cùng database và role thử, rồi chạy lệnh dưới đây. Database này phải đã có catalog test 003 và chỉ chứa dữ liệu thử. Không chạy bốn checkpoint độc lập trên database đã chứa dữ liệu.

```powershell
.\mvnw.cmd '-Dtest=*,!AuthDatabaseFoundationTest,!AuthMigrationTest,!OwnershipRepositoryIntegrationTest,!PlannerSchemaBootstrapTest' test
```

Tạo ZIP từ working tree cuối cùng. Script dùng danh sách cho phép và có thể chạy từ source đã giải nén:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Pack-Planner.ps1
```

File mặc định nằm ở `target/Planner-source.zip`. Nếu file đã tồn tại, dùng `-OutputPath` để chọn tên mới.

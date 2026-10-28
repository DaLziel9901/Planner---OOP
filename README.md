# Planner

Dự án Java Spring Boot cho ứng dụng lập kế hoạch mục tiêu phân cấp.

## 1. Requirement

- JDK 17
- PostgreSQL và pgAdmin4, phiên bản hiện tại đang dùng: PostgreSQL 18.6.4
- Git.
- IntelliJ IDEA hoặc VS Code hỗ trợ Java.

Repo cần có `.mvn/`, `mvnw`, `mvnw.cmd` và `pom.xml` để dùng Maven Wrapper, không bắt buộc cài Maven riêng. Lần chạy đầu cần Internet để tải Maven/dependency.

## 2. Clone repo
```powershell
git clone REPO_URL planner
cd planner
```

Nếu đang dùng Java khác, thay đường dẫn bằng thư mục JDK 17 trên máy cá nhân

## 3. Tạo database bằng pgAdmin

1. Khởi động PostgreSQL, mở pgAdmin và kết nối server bằng tài khoản PostgreSQL của bạn.
2. Nhấn phải Databases → Create → Database, đặt tên `planner`, chọn Save.
3. Chọn database vừa tạo → Tools → Query Tool.
4. Chọn Open File, mở `src/main/resources/schema.sql` trong repository vừa clone.
5. Không bôi đen riêng một đoạn; chạy toàn bộ script bằng **Execute Script/F5**.
6. Kiểm tra tab **Messages** không có lỗi và transaction kết thúc bằng `COMMIT`.

Chỉ chạy script một lần trên database chưa có các bảng này. Ứng dụng đặt
`spring.sql.init.mode=never`, nên không tự tạo database hay bảng khi khởi động.
Không xóa các bảng đã có dữ liệu để chạy lại script.

## 4. Cấu hình kết nối

Mỗi người dùng database và mật khẩu trên máy mình.
`src/main/resources/application.properties` đọc các biến môi trường:

| Biến | Giá trị mặc định | Cách dùng |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/planner` | Đổi nếu khác host, cổng hoặc tên database |
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

## 5. Khởi chạy

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
Hiện chưa có controller/giao diện nên trang gốc có thể trả về 404;
cảnh báo thiếu `classpath:/templates/` cũng phù hợp với trạng thái hiện tại.

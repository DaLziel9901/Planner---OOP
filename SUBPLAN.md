# Luồng SubPlan

SubPlan thuộc một Plan. SubPlan trực tiếp dưới Plan là tầng 1. Mỗi nhánh có tối đa 5 tầng SubPlan.
Có thể chuyển cha trong cùng Plan nếu toàn nhánh sau chuyển vẫn hợp lệ.

Trang SubPlan đọc cây qua `PlanTreeService`. Tiến độ chỉ đếm SubTask lá thuộc nhánh đang xem,
bao gồm các lá của Task trực tiếp và Task dưới các SubPlan con. Cảnh báo ngày dùng dữ liệu
cùng snapshot khi cây được đọc đầy đủ. Cây rỗng và lỗi đọc cây có trạng thái hiển thị riêng.

## Vai trò từng file

| File | Trách nhiệm |
| --- | --- |
| `src/main/java/com/example/planner/repository/SubPlanRepository.java` | SQL có tham số, ánh xạ đủ cột, INSERT trả ID, đọc tất cả tầng theo Plan, UPDATE giữ created_at/plan_id, DELETE cascade |
| `src/main/java/com/example/planner/service/SubPlanService.java` | Kiểm tra dữ liệu, Plan/cha tồn tại, cùng Plan, vòng lặp, giới hạn 5 tầng, cảnh báo ngày, điều phối giao dịch |
| `src/main/java/com/example/planner/controller/SubPlanController.java` | Nhận form, phân tích số/ngày, giữ dữ liệu sai, chuyển hướng sau ghi thành công, dữ liệu cho Thymeleaf |
| `src/main/resources/templates/subplans/list.html` | Chọn Plan rồi xem mọi SubPlan của Plan, có liên kết thêm/sửa/chi tiết |
| `src/main/resources/templates/subplans/form.html` | Thêm/sửa tên, mô tả, ngày, thứ tự và cha trong cùng Plan |
| `src/main/resources/templates/subplans/detail.html` | Thông tin, cảnh báo ngày, con trực tiếp, xác nhận xóa cả nhánh |

### Repository

- `findByPlanId`: trả mọi tầng, sắp theo `order_index`, rồi `id`; không chỉ tầng 1.
- `findById`: Optional rỗng nếu không có.
- `insert`: để DB sinh ID và created_at.
- `update`: kiểm tra số dòng tác động, không đổi Plan hay thời điểm tạo.
- `deleteById`: một câu DELETE; PostgreSQL tự xóa cây con qua khóa ngoại.
- `lockPlan`: SELECT FOR UPDATE, chỉ dùng trong giao dịch để tuần tự hóa thay đổi
  cấu trúc SubPlan cùng Plan qua service này.

### Service

- `getPlan`, `findByPlanId`, `getById`: đọc dữ liệu; ID không tồn tại trả HTTP 404.
- `create`, `update`, `delete`: các thao tác ghi có `@Transactional`.
- `validateHierarchy` (private): với mỗi SubPlan, lần theo cha lên gốc; tập `visited`
  phát hiện vòng lặp, biến `depth` kiểm tra tối đa 5 tầng. Không lưu level trong DB.
  Khi sửa cha, kiểm tra cây giả định sau sửa để tính cả độ sâu con/cháu của nhánh chuyển.
- `getDirectChildren`: chỉ trả SubPlan con trực tiếp. `PlanTreeService` chịu trách nhiệm nạp cây tiến độ.
- `getDateWarnings`: so ngày với cha và SubPlan con trực tiếp; chỉ cảnh báo, không chặn lưu.
- `InputException`: lỗi nhập liệu có tên trường. Controller chỉ bắt loại này;
  lỗi SQL/kết nối không bị che thành lỗi nhập sai.

Service cho phép chuyển cha trong **cùng Plan**; chưa hỗ trợ chuyển cả nhánh sang
Plan khác. Thay ngày cha không bắt buộc dời ngày con. Có thể trùng tên và trùng thời gian.
Ngày Task/SubTask có thể để trống. Ngày ngoài khoảng cha vẫn lưu được mà không có cảnh báo hay tự điều chỉnh.

### Vì sao khóa Plan và dùng giao dịch?

Kiểm tra cây rồi ghi phải nằm cùng giao dịch. Hai yêu cầu đổi cha đồng thời qua
service cùng lấy khóa Plan, nên không thể cùng kiểm tra trên cấu trúc cũ rồi tạo
vòng lặp/độ sâu sai. Các đường ghi cấu trúc khác trong tương lai cũng cần tuân theo
quy ước khóa này. SQL chạy trực tiếp ngoài service vẫn có thể tạo dữ liệu sai về
độ sâu/vòng lặp; schema không tự áp đặt hai quy tắc đó.

## URL và form

| HTTP | URL | Công dụng |
| --- | --- | --- |
| GET | `/subplans` | Chọn Plan |
| GET | `/subplans?planId=1` | Danh sách theo Plan |
| GET | `/subplans/new?planId=1` | Form tầng 1 |
| GET | `/subplans/new?planId=1&parentSubPlanId=2` | Form có cha chọn sẵn |
| POST | `/subplans` | Tạo mới |
| GET | `/subplans/{id}` | Chi tiết |
| GET | `/subplans/{id}/edit` | Form sửa |
| POST | `/subplans/{id}/edit` | Cập nhật |
| POST | `/subplans/{id}/delete` | Xóa sau xác nhận trên giao diện |

Tên trường: `planId`, `parentSubPlanId`, `title`, `description`, `startDate`,
`endDate`, `orderIndex`. ID sửa/xóa lấy từ URL. Khi sửa, Plan lấy từ DB, bỏ qua
planId người dùng tự gửi. Dùng ô ngày dạng text để giữ cả chuỗi ngày sai khi trả form.
Danh sách cha có thể chứa nhánh con; service từ chối nếu chọn gây vòng lặp/quá sâu.

## Chạy thử trên trình duyệt

1. Chuẩn bị PostgreSQL theo [README.md](README.md), rồi chạy `.\mvnw.cmd spring-boot:run`.
2. Cần có ít nhất một Plan đã lưu; tạo qua `/plans/new`. Hoặc trên database thử
   nghiệm, bạn có thể chủ động tạo Plan bằng Query Tool:

   ```sql
   INSERT INTO plan(title) VALUES ('Thu SubPlan') RETURNING id;
   ```

   Commit giao dịch nếu Auto commit đang tắt. Đây không phải dữ liệu được ứng dụng tự thêm.
3. Mở `http://localhost:8080/subplans`, chọn Plan, bấm Xem rồi Thêm SubPlan.
4. Thử thêm chỉ có tên, sửa mô tả/ngày/thứ tự, thêm con đến tầng 5.
5. Thử thêm tầng 6, chọn chính nhánh con làm cha, đặt ngày bắt đầu sau kết thúc:
   phải báo lỗi và giữ form. Tên trùng vẫn hợp lệ.
6. Đặt ngày ngoài khoảng cha: lưu được, trang chi tiết có cảnh báo.
7. Thử đổi cha một nhánh có con/cháu xuống sâu: tổng số tầng vẫn phải <= 5.
8. Xóa trên database thử nghiệm: hộp thoại cảnh báo toàn bộ nhánh; hủy thì không gửi POST.
   Xác nhận thì các SubPlan/Task/SubTask bên dưới bị xóa; nhánh anh em vẫn còn.
9. Khởi động lại để kiểm tra bản ghi đã commit vẫn còn. Không dùng dữ liệu quan trọng thử xóa.

## Kiểm thử tự động

- `SubPlanServiceTest`: nghiệp vụ với repository giả, không DB.
- `SubPlanControllerTest`: MockMvc và Thymeleaf **render thật** với service giả;
  kiểm tra template, escape nội dung người dùng, form lỗi, chuyển hướng, 404, POST xóa.
  Không phải kiểm thử JavaScript/hộp thoại xác nhận trên trình duyệt thật.
- `SubPlanRepositoryTest`: SQL thật; dữ liệu/schema trong giao dịch rollback sau test.
  Gọi service trực tiếp trong giao dịch JDBC của test; không mô phỏng hai người ghi đồng thời.

Chạy test không DB (JDK 17):

```powershell
.\mvnw.cmd "-Dtest=SubPlanServiceTest,SubPlanControllerTest" test
```

Chạy SQL trên database `planner` dùng chung với ứng dụng, đã được xác nhận chỉ chứa dữ liệu thử:

```powershell
$env:TEST_DB_URL = 'jdbc:postgresql://localhost:5432/planner'
$env:TEST_DB_USERNAME = 'postgres'
$env:TEST_DB_PASSWORD = 'MAT_KHAU_CUA_BAN'
.\mvnw.cmd "-Dtest=SubPlanRepositoryTest" test
```

Thiếu TEST_DB_URL thì test SQL bị bỏ qua, không tính là đã kiểm chứng.
Không cần ghi mật khẩu vào code. Có thể dùng `mvn` thay wrapper nếu máy đã cài Maven.

Xem [TESTING.md](TESTING.md) để chạy toàn suite và đọc kết quả nghiệm thu MVP T043.

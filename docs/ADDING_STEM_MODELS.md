# Thêm mô hình tách stem

MediaTool 1.3.1 tách metadata model khỏi engine suy luận. Mục tiêu là thử checkpoint mới mà không thêm các nhánh kiểu `if (modelName == ...)` vào downloader, service hoặc giao diện.

## Điểm mở rộng

- `StemModelContract.kt`: kiểu dữ liệu thuần Kotlin mô tả model.
- `StemModelRegistry.kt`: catalog model đã được kiểm chứng và thứ tự mặc định theo chế độ.
- `ModelDownloader.kt`: tải tiếp, ghim kích thước/SHA-256 và cài artifact vào vùng riêng.
- `AudioSeparator.kt`: backend waveform ONNX dùng descriptor để dựng tensor, chuẩn hóa, overlap-add và ánh xạ source.
- `StemViewModel`/`StemService`: truyền `modelId`, không suy luận model từ cài đặt có thể thay đổi giữa tác vụ.

Giao diện chọn model đọc trực tiếp từ registry. Khi catalog có thêm model cùng chế độ, lựa chọn mới xuất hiện mà không cần sửa luồng tải hoặc chạy service.

## Contract bắt buộc

Mỗi `StemModelDescriptor` phải khai báo và kiểm tra:

1. ID ổn định, tên hiển thị, chế độ 2 hoặc 4 stem.
2. URL theo revision bất biến, tên cache theo family, kích thước byte và SHA-256.
3. Sample rate, số kênh và đúng số frame cố định của graph.
4. Tên/layout tensor đầu vào, tên/layout tensor đầu ra và số source.
5. Ánh xạ source cho vocals, music và các stem tùy chọn.
6. Có hoặc không có global mean/std; không được tự suy đoán theo tên model.
7. Chunk, overlap, edge fade và boundary padding đã đối chiếu với inference tham chiếu.
8. Danh sách execution provider được phép; CPU luôn bắt buộc làm fallback.
9. Ngưỡng RAM tổng/RAM trống, giấy phép và URL dự án.

Model chỉ được đưa vào registry sau khi có một vector kiểm thử ngắn xác nhận shape, thứ tự stem, sample rate, độ dài output và sai số so với pipeline gốc.

## Quy trình thêm checkpoint waveform ONNX

1. Ghim revision của kho model, không dùng URL `main` có thể thay đổi.
2. Lấy kích thước và SHA-256 từ artifact thực tế; tải thử và băm độc lập trong CI tin cậy.
3. Xác minh graph là một tệp hay có external data. Downloader hiện hỗ trợ artifact ONNX một tệp; model external-data cần installer nhiều artifact trước khi thêm vào catalog.
4. Kiểm tra operator với ONNX Runtime Android/CPU. NNAPI và XNNPACK là tùy chọn thử nghiệm, luôn phải có đường fallback CPU.
5. Thêm descriptor và unit test contract vào `StemModelRegistryTest`.
6. Chạy unit test, lint, build `internal`, sau đó thử trên ít nhất hai thiết bị arm64 có RAM khác nhau.
7. So sánh một bộ bài cố định về SDR cảm nhận, lỗi ranh giới, thời gian, nhiệt độ và peak RAM trước khi đổi model mặc định.

## Hướng nhập model ở phiên bản sau

Backend hiện đã đủ dữ liệu để một catalog nhập ngoài tạo cùng `StemModelDescriptor`. Phiên bản nhập model nên dùng một gói gồm ONNX và manifest đã ký/băm, cài bằng SAF vào thư mục models, rồi chỉ đăng ký sau khi:

- schema manifest hợp lệ và backend/layout nằm trong allowlist;
- tất cả artifact đúng kích thước và SHA-256;
- tensor contract qua phiên chạy kiểm tra ngắn;
- ID không ghi đè descriptor tích hợp;
- giấy phép/nguồn được lưu cùng model.

Không nên cho người dùng chọn một tệp `.onnx` trần rồi đoán shape hoặc thứ tự source. Một graph vẫn có thể chạy nhưng trả nhầm vocals/music, dùng sai chuẩn hóa hoặc vượt bộ nhớ thiết bị.

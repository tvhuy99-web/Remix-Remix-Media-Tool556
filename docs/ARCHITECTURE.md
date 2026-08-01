# Kiến trúc MediaTool 1.3.1

## Tổng quan

MediaTool là ứng dụng Android một module dùng Jetpack Compose. Tệp đầu vào được chọn qua Storage Access Framework; kết quả được tạo trong vùng cache riêng rồi người dùng chủ động lưu hoặc chia sẻ.

```text
Compose screens
    │
    ├── MediaEngine ───────────── FFmpegKit
    ├── RecordingManager ──────── RecordingService + WavRecorder
    ├── StemViewModel ─────────── StemModelRegistry + ModelDownloader
    ├── StemService ───────────── Model descriptor + preflight
    ├── AudioSeparator ────────── ONNX Runtime + FFmpegKit
    ├── TaskStateStore ────────── SharedPreferences theo namespace tác vụ
    ├── DiagnosticLogger ──────── JSONL xoay vòng + crash capture
    ├── DiagnosticReportManager ─ ZIP summary/log đã che dữ liệu
    ├── SettingsManager ───────── SharedPreferences
    └── FileExportManager ─────── SAF + FileProvider
```

## Tệp và quyền truy cập

1. Người dùng chọn tệp bằng `ActivityResultContracts.OpenDocument` hoặc `OpenMultipleDocuments`.
2. Ứng dụng giữ quyền đọc URI khi nhà cung cấp hỗ trợ persistable permission.
3. FFmpegKit đọc URI qua tham số SAF hoặc `MediaEngine` sao chép vào cache import khi cần.
4. Kết quả được tạo trong `cache/results` hoặc `cache/recordings`.
5. Người dùng lưu bằng `CreateDocument` hoặc chia sẻ qua `FileProvider`.
6. Cache cũ được dọn theo tuổi tệp nhưng giữ các output đang được `TaskStateStore` tham chiếu.

## Xử lý FFmpeg

`MediaEngine` biến callback FFmpeg thành `Flow`, lưu session đang chạy và hủy đúng session khi coroutine bị hủy. Các màn hình xác minh output tồn tại, có dữ liệu và xóa output dở nếu quy trình không hoàn tất.

Các helper Kotlin thuần xử lý:

- Parse timeline nghiêm ngặt.
- Tính pan stereo và fade an toàn.
- Phân phối thời lượng slideshow.
- Tính tiến độ từ thời gian FFmpeg.

## Ghi âm

### Microphone

`RecordingService` lên foreground trước, sau đó `RecordingManager` khởi tạo `MediaRecorder` và ghi AAC/M4A.

### Âm thanh hệ thống

1. Activity nhận quyền MediaProjection.
2. Foreground service được khởi động.
3. Service lấy token MediaProjection và đăng ký callback.
4. `WavRecorder` dùng Playback Capture để ghi PCM.
5. Khi dừng, recorder hoàn tất dữ liệu, ghi RIFF/WAV header và `fsync` trước khi UI nhận kết quả.
6. Nếu hệ thống thu hồi projection, callback kết thúc phiên và giải phóng tài nguyên.

## Tách nhạc AI

### Tải model

- Mỗi descriptor ghim revision, kích thước, SHA-256, tensor contract, chuẩn hóa, chunking và source mapping.
- Tải dở nằm ở tệp `.part` và hỗ trợ HTTP Range.
- Phản hồi resume phải có `Content-Range` khớp chính xác.
- Coroutine cancellation hủy trực tiếp OkHttp call.
- Chỉ revision cũ trong cùng family bị dọn; model family khác được giữ để so sánh.

### Xử lý

- `StemService` chạy foreground và lưu trạng thái bền theo task ID.
- Preflight kiểm tra duration, dung lượng cache, RAM tổng và RAM khả dụng theo descriptor.
- `AudioSeparator` giải mã PCM float32, chỉ chuẩn hóa khi descriptor yêu cầu, chạy ONNX theo tensor layout đã khai báo rồi ánh xạ source.
- Mel-Band RoFormer dùng chunk 352.800 frame, bước 176.400 frame, edge fade 35.280 frame và reflect-padding 176.400 frame ở hai biên của bài dài.
- Vùng overlap dùng hai trọng số đã chuẩn hóa về tổng 1; output vẫn ở float32 đến bước encode cuối.
- NNAPI/XNNPACK có fallback CPU khi không tạo được session. XNNPACK sở hữu thread pool riêng; ORT intra-op bằng 1 và tắt spinning.
- Nút Hủy gọi cả `FFmpegKit.cancel(sessionId)` và `RunOptions.setTerminate(true)`.
- Output chỉ được commit khi tất cả stem hợp lệ; nếu một bước lỗi, mọi output bán thành phẩm bị xóa.

## Trạng thái bền

`TaskStateStore` lưu riêng từng loại tác vụ, hiện gồm `recording` và `stem`. Khi process mở lại:

- Tác vụ đang `RUNNING` được chuyển thành `INTERRUPTED`.
- Output thành công chỉ được khôi phục nếu file còn tồn tại và có dữ liệu.
- Ghi âm không ghi đè trạng thái stem và ngược lại.
- `startedAt` của cùng task ID được giữ nguyên qua mọi lần cập nhật tiến độ.

## Chẩn đoán và quyền riêng tư

- `MediaToolApplication` khởi tạo logger và chuyển tiếp mọi uncaught exception cho handler hệ thống sau khi ghi crash đồng bộ.
- Sự kiện thường được ghi bằng worker một luồng với hàng đợi giới hạn; log không chạy I/O trên main thread và không được phép làm nghẽn pipeline.
- Logger xoay tệp JSONL 2 MiB, giữ tối đa 5 tệp cũ/7 ngày và gắn task/session ID xuyên suốt downloader, service, FFmpeg, ONNX, recorder và TTS.
- `DiagnosticRedactor` che URI, đường dẫn, URL, tên media, metadata và thông tin xác thực trước cả tệp log lẫn Logcat.
- Gói ZIP do người dùng chủ động tạo gồm summary, log snapshot nhất quán và lịch sử `ApplicationExitInfo` trên Android 11+; không chứa media/model.

## Giao diện

- Mọi màn hình công cụ dùng `ToolScaffold`.
- Kết quả dùng `ResultFileActions` với hành động **Lưu** và **Chia sẻ**.
- Các URI và cấu hình quan trọng dùng state có thể lưu qua lần tạo lại Activity.
- Các tác vụ dài chạy trong service hoặc ViewModel thay vì gắn hoàn toàn vào vòng đời composable.

## Build và phát hành

- `debug`: ký debug, không tối ưu.
- `internal`: kế thừa cấu hình tối ưu release nhưng ký debug để cài thử.
- `release`: minify/resource shrink và bắt buộc keystore riêng.
- APK/AAB chỉ đóng gói `arm64-v8a`, là ABI có binary FFmpegKit maintained trên Maven.
- CI build debug/internal, kiểm tra native ARM64 và build instrumentation APK; smoke test chạy trên thiết bị arm64.

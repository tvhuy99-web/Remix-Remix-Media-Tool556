# Changelog

## 1.3.2 - 2026-08-02

### Sửa lỗi FFmpegKit trên thiết bị

- Thêm rõ dependency `com.arthenica:smart-exception-java:0.2.1`, sửa lỗi `Failed resolution of: Lcom/arthenica/smartexception/java/Exceptions;` khi bắt đầu tách stem hoặc chạy công cụ FFmpeg.
- Giữ các lớp Smart Exception trong bản `internal`/`release` khi R8 tối ưu.
- CI kiểm tra trực tiếp descriptor FFmpegKit và Smart Exception trong `classes*.dex` của từng APK để ngăn lỗi đóng gói tái diễn.

## 1.3.1 - 2026-08-01

### Nhật ký chẩn đoán và báo lỗi

- Thêm logger JSONL theo session/component/event, worker riêng, hàng đợi giới hạn, xoay 2 MiB và lưu tối đa 5 tệp cũ/7 ngày.
- Ghi chi tiết tải/hash model, preflight RAM/storage, provider ONNX thực tế, tensor shape, thời gian/heap từng chunk, phase/return code FFmpeg, ghi âm và TTS.
- Thêm crash handler toàn cục; gói summary còn có lịch sử `ANR`, `CRASH_NATIVE`, `LOW_MEMORY` và lý do process thoát trên Android 11+.
- Thêm nút tạo/lưu/gửi ZIP chẩn đoán ở Cài đặt và ngay sau lỗi tách stem.
- Che URI, đường dẫn, URL, tên tệp media, metadata, email/token và không còn ghi mẫu waveform hay FFmpeg command nguyên bản.

### Sửa lỗi sau rà soát cuối

- Không fallback CPU sau `OutOfMemoryError`; chỉ fallback khi provider/session báo lỗi có thể phục hồi.
- Đưa kiểm tra tensor input ra khỏi vòng lặp chunk, bổ sung đóng stream có kiểm tra lỗi và không để lỗi `close()` che lỗi inference gốc.
- Chuyển lỗi thật của thread ghi WAV lên trạng thái tác vụ và bảo vệ trường hợp foreground service không khởi động được.
- TTS phân biệt callback hợp lệ/cũ cả ở nhánh lỗi, nên callback thế hệ cũ không thể ghi đè trạng thái câu mới.
- Giữ nguyên `startedAt` khi cùng task cập nhật tiến độ thay vì vô tình đặt lại thời gian bắt đầu.

## 1.3.0 - 2026-08-01

### Mel-Band RoFormer 2-stem

- Tích hợp bản Mel-Band RoFormer vocals ONNX theo revision bất biến, kích thước và SHA-256 đã ghim.
- Dùng đúng tensor contract waveform stereo 44,1 kHz: 8 giây, source 0 vocals và source 1 instrumental.
- Bỏ global mean/std đối với Mel-Band; giữ chuẩn hóa này chỉ cho descriptor Demucs cũ.
- Dùng overlap 50% với cửa sổ tuyến tính/counter được chuẩn hóa, cộng với reflect-padding 4 giây ở hai biên cho bài dài.
- Chuyển PCM tạm của stem sang float32 để tránh clip/quantize trước lần encode cuối.

### Kiến trúc model mở rộng

- Thêm model contract và registry dùng chung cho UI, downloader, preflight, service và inference.
- Truyền model ID bất biến vào foreground service để thay đổi cài đặt không đổi model giữa tác vụ.
- Thêm lựa chọn model theo chế độ; catalog mới tự xuất hiện trong giao diện.
- Downloader giữ resume, Content-Range và SHA-256 cho từng family model.
- Thêm tài liệu contract/quy trình để tích hợp hoặc nhập model có manifest trong phiên bản sau.

### Tài nguyên và ONNX Runtime

- Preflight dùng yêu cầu RAM riêng của model; Mel-Band yêu cầu thiết bị RAM 8 GB trở lên và khoảng 2,5 GiB khả dụng.
- Cho phép large heap để giảm nguy cơ kill khi khởi tạo graph gần 1 GB.
- Nếu NNAPI/XNNPACK không tạo được session, tự đóng tài nguyên và fallback CPU.
- Khi dùng XNNPACK, tắt ORT intra-op spinning, giữ ORT intra-op bằng 1 và để XNNPACK sở hữu số luồng đã chọn.

## 1.2.1 - 2026-08-01

### Bản vá ổn định sau rà soát

- Tăng `versionCode` lên 4 và `versionName` lên 1.2.1.
- Giới hạn artifact ở `arm64-v8a` và kiểm tra đủ native library FFmpeg trong APK.
- Đóng mọi đường rò rỉ `MediaRecorder`/`AudioRecord`; hoàn tất WAV ngoài main thread.
- Thay crossfade stem bằng cửa sổ bổ sung không tăng biên độ và tách thread pool XNNPACK/ORT.
- Chuyển xác minh SHA-256 model và preflight media sang IO.
- Dùng loudness normalization EBU R128, không áp dụng hiệu ứng ẩn ở chế độ đổi định dạng.
- Dùng chung parser timeline; bỏ fade khi không xác định đủ duration nguồn.
- Dựng slideshow theo mốc tuyệt đối và chia ảnh tự động trong phần timeline còn trống.
- TTS dùng hàng đợi có generation/utterance ID để không cắt câu hoặc khôi phục volume sớm.
- Bổ sung unit/core smoke test cho overlap, ONNX threading, slideshow timeline và hàng đợi TTS.

## 1.2.0 - 2026-08-01

### Build và phát hành

- Tăng `versionCode` lên 3 và `versionName` lên 1.2.0.
- Thêm build type `internal` ký debug nhưng dùng cấu hình tối ưu gần release.
- Bản release bắt buộc keystore riêng, không còn fallback sang debug key.
- Giới hạn APK/AAB ở arm64-v8a theo binary FFmpegKit maintained hiện có.
- Bổ sung quy tắc R8 cho ONNX Runtime, FFmpegKit và OkHttp.
- Nâng CI để build debug/internal, kiểm tra APK và chạy instrumentation smoke test.

### Android và foreground service

- Thêm TTS service query trong Manifest.
- Thêm quyền và foreground service type `mediaProcessing` cho tác vụ AI.
- MediaProjection đăng ký callback, unregister và giải phóng đúng vòng đời.
- Thêm xử lý foreground service timeout cho Android mới.
- Yêu cầu quyền thông báo trước các tác vụ dài trên Android 13 trở lên.

### Tách nhạc AI

- Ghim model theo commit, dung lượng và SHA-256.
- Tải model hỗ trợ resume, xác minh `Content-Range`, hủy trực tiếp OkHttp call và dọn model cũ.
- Thêm preflight RAM, dung lượng tạm và giới hạn duration.
- Hủy thật FFmpeg session và ONNX inference.
- Sửa vòng đời `SessionOptions`, tensor, result, stream và file descriptor.
- Sửa phần đuôi chunk, kiểm tra tensor shape và xóa transaction output khi thất bại.
- Lưu và khôi phục trạng thái/result của tác vụ stem.

### Ghi âm

- Hoàn thiện WAV header sau khi thread ghi kết thúc, thêm `fsync` và giới hạn RIFF 4 GB.
- Thêm trạng thái khởi tạo/hoàn tất và ngăn ghi đè bản ghi chưa lưu.
- Lưu và khôi phục bản ghi thành công sau khi process được tạo lại.

### Công cụ media và giao diện

- File picker chính chuyển sang `OpenDocument` và giữ quyền URI lâu dài.
- Cấu hình và danh sách tệp quan trọng sống qua lần tạo lại Activity.
- Bộ trộn chỉ lặp nhạc nền, bỏ `aloop` bộ đệm cực lớn và tính fade/pan an toàn.
- Tiến độ FFmpeg dựa trên thời gian xử lý thật.
- Parse timecode/timeline nghiêm ngặt, không âm thầm biến dữ liệu sai thành 0.
- Xóa output dở của cắt, nối, trộn, slideshow, hiệu ứng và stem.
- Đồng nhất hành động Lưu/Chia sẻ và đóng nhiều ảnh thành ZIP.
- Thêm màn hình xem thông báo giấy phép bên thứ ba.

### Kiểm thử

- Thêm unit test cho WAV header, subtitle parser, slideshow timing, audio math, timeline, Content-Range và preflight AI.
- Thêm instrumentation smoke test cho FileProvider và namespace trạng thái tác vụ.
- Thêm bộ core smoke chạy bằng Kotlin/JVM không cần Android SDK.

## 1.1.0

- Tái cấu trúc nền tảng ban đầu, chuyển dependency native sang Maven, thống nhất giao diện công cụ và bổ sung Gradle bootstrap.

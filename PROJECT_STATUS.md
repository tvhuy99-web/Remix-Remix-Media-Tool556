# Trạng thái bàn giao MediaTool 1.3.1

Ngày rà soát: 2026-08-01.

## Đã triển khai trong mã nguồn

- Package và application ID thống nhất tại `com.aistudio.mediatool`.
- FFmpegKit và ONNX Runtime được lấy từ Maven, không còn AAR rỗng hoặc cơ chế tải binary trong `settings.gradle`.
- Ba build type rõ ràng: `debug`, `internal` và `release`; release bắt buộc keystore thật.
- Artifact phân phối được giới hạn ở `arm64-v8a`, phù hợp binary FFmpegKit maintained trên Maven.
- TTS được khai báo trong phần `<queries>` của Manifest.
- MediaProjection đăng ký callback và giải phóng khi hệ thống thu hồi quyền.
- Dịch vụ AI dùng foreground service type `mediaProcessing` trên Android phù hợp và xử lý timeout.
- Hủy tác vụ AI gửi lệnh hủy tới cả FFmpeg session và ONNX `RunOptions`.
- Tải model hỗ trợ resume, kiểm tra `Content-Range`, dung lượng, SHA-256, mạng và dung lượng trống.
- Preflight AI ước lượng tệp tạm, RAM và giới hạn thời lượng trước khi chạy.
- Ghi WAV có giới hạn RIFF, hoàn tất header, `fsync` và trạng thái `Finalizing`.
- Trạng thái ghi âm và tách nhạc được lưu riêng, có thể khôi phục kết quả sau khi Activity/process được tạo lại.
- File picker dùng `OpenDocument` và giữ quyền đọc URI lâu dài ở các công cụ chính.
- Cắt, nối, trộn, slideshow và hiệu ứng dọn tệp đầu ra dở khi thất bại hoặc bị hủy.
- Bộ trộn không còn dùng vòng lặp audio với bộ đệm vô hạn và không lặp nhầm âm thanh gốc.
- Tiến độ FFmpeg dựa trên thời gian xử lý thay vì giá trị giả cố định.
- Parser phụ đề, timecode, timeline, WAV header, Content-Range, redaction và ước lượng AI có unit test.
- Có instrumentation smoke test cho FileProvider và namespace trạng thái tác vụ.
- Thông báo giấy phép bên thứ ba được đóng trong assets và có thể mở từ màn hình Cài đặt.
- Mel-Band RoFormer 2-stem là model mặc định, được ghim theo revision/kích thước/SHA-256 và dùng đúng source mapping.
- Model contract/registry tách tensor, chuẩn hóa, chunking, tài nguyên và giấy phép khỏi engine suy luận.
- Stem dùng PCM float32, overlap/counter chuẩn hóa và reflect-padding theo pipeline tham chiếu.
- Có nhật ký JSONL xoay vòng theo session/phase/chunk, crash capture toàn cục và gói ZIP chẩn đoán xuất từ ứng dụng.
- Nhật ký che URI/đường dẫn/tên media/metadata/token, không ghi waveform hoặc command FFmpeg nguyên bản.
- Summary chẩn đoán có RAM/storage/heap, cấu hình model/provider, trạng thái task và lịch sử lý do process thoát trên Android 11+.
- Fallback provider không còn bắt `OutOfMemoryError`; lỗi chốt WAV và callback TTS cũ được chuyển/loại đúng trạng thái.

## Kiểm tra có thể chạy không cần Android SDK

- Parse Android Manifest và toàn bộ XML resource.
- Parse version catalog TOML và workflow YAML.
- Kiểm tra file rỗng, AAR cục bộ, package mẫu và quyền lưu trữ nguy hiểm.
- Kiểm tra ngoặc/comment/string Kotlin ở mức tĩnh.
- Biên dịch và chạy bộ kiểm tra Kotlin thuần bằng `kotlinc`.
- Biên dịch chọn lọc logger/report, downloader, FFmpeg engine, stem separator, recording/WAV và task store bằng stub Android/ONNX/FFmpeg cục bộ.
- Kiểm tra Gradle bootstrap bằng một distribution giả lập qua HTTP cục bộ.
- Kiểm tra cấu trúc JAR wrapper và checksum Gradle.

## Giới hạn xác minh hiện tại

Môi trường tái cấu trúc không có Android SDK và không tải được toàn bộ dependency Android, nên chưa chạy được build Android thật tại đây. Những bước sau được cấu hình trong GitHub Actions và phải chạy ít nhất một lần trước khi phát hành:

- `lintDebug` và `lintRelease`.
- `testDebugUnitTest`.
- `assembleDebug`, `assembleInternal` và bản release ký thật.
- `connectedDebugAndroidTest` trên thiết bị/device farm arm64.
- Cài APK lên thiết bị arm64 thực tế.
- Kiểm thử MediaProjection, TTS tiếng Việt, codec FFmpeg và ONNX trên nhiều ROM.
- Tạo ZIP chẩn đoán sau lỗi Java, native crash giả lập/ANR và xác nhận không có URI/tên/metadata media thật.
- Xác nhận bản `libc++_shared.so` được chọn hoạt động với cả FFmpegKit và ONNX Runtime.
- Tải checkpoint Mel-Band gần 1 GB và chạy parity test trên thiết bị arm64 RAM 8/12 GB; môi trường hiện tại không chứa model này.
- Đo peak RSS, thời gian/chunk, nhiệt độ và chất lượng ở CPU, NNAPI, XNNPACK trước khi khuyến nghị accelerator khác CPU.

Dự án được bàn giao ở trạng thái **source build-ready có kiểm tra tĩnh và test lõi**, không phải lời khẳng định APK đã được biên dịch hoặc chạy trên mọi thiết bị trong môi trường này.

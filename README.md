# MediaTool 1.3.1

MediaTool là ứng dụng Android xử lý âm thanh và video trực tiếp trên thiết bị. Dự án dùng Jetpack Compose, FFmpegKit maintained, ONNX Runtime và Storage Access Framework.

## Chức năng chính

- Ghi microphone hoặc âm thanh đang phát trên Android 10 trở lên.
- Cắt, nối và trộn audio/video.
- Tạo video từ ảnh và âm thanh.
- Đọc phụ đề bằng Text-to-Speech và trích xuất audio thuyết minh.
- Nén, đổi định dạng, tách audio/video, trích xuất ảnh và áp dụng hiệu ứng âm thanh.
- Tách vocals/nhạc nền bằng Mel-Band RoFormer 2-stem; giữ tùy chọn Demucs 4-stem cũ, tất cả chạy trên thiết bị.
- Tạo gói nhật ký chẩn đoán đã che dữ liệu để báo lỗi model, FFmpeg, ghi âm, TTS, crash hoặc thiếu tài nguyên.
- Lưu và chia sẻ kết quả bằng bộ chọn tệp chuẩn của Android, không ghi đè tệp gốc.

## Yêu cầu build

- Android Studio hỗ trợ Android Gradle Plugin 8.13.2.
- JDK 17.
- Android SDK Platform 36.
- Kết nối Internet trong lần đồng bộ đầu để tải Gradle và dependency Maven.

Không cần `.env`, API key, AAR thủ công hoặc Gradle cài toàn hệ thống.

## Build APK cài thử

### Android Studio

1. Giải nén dự án.
2. Mở thư mục gốc `MediaTool` bằng Android Studio.
3. Chọn JDK 17 cho Gradle.
4. Cài Android SDK 36 nếu Android Studio yêu cầu.
5. Chờ Gradle Sync hoàn tất.
6. Chọn build variant `debug` hoặc `internal`, sau đó chọn **Build > Build APK(s)**.

### Dòng lệnh

macOS hoặc Linux:

```bash
chmod +x gradlew
./gradlew clean lintDebug testDebugUnitTest assembleDebug assembleInternal
```

Windows:

```bat
gradlew.bat clean lintDebug testDebugUnitTest assembleDebug assembleInternal
```

Các artifact hiện chỉ đóng gói `arm64-v8a`, là ABI có binary FFmpegKit maintained được phát hành qua Maven. Vị trí APK thông thường:

```text
app/build/outputs/apk/debug/
app/build/outputs/apk/internal/
```

- `debug`: không minify, phù hợp gỡ lỗi.
- `internal`: dùng cấu hình tối ưu gần release nhưng ký bằng debug key, phù hợp cài thử.
- `release`: chỉ dành cho bản ký chính thức.

## Ký APK release

Bản release **không tự động dùng debug key**.

1. Sao chép `keystore.properties.example` thành `keystore.properties`.
2. Điền đúng đường dẫn và thông tin keystore.
3. Chạy:

```bash
./gradlew clean lintRelease testDebugUnitTest assembleRelease
```

Nếu thiếu `keystore.properties`, tác vụ đóng gói release sẽ dừng với thông báo rõ ràng. Tệp keystore và thông tin khóa đã được `.gitignore` loại khỏi kho mã nguồn.

## Model tách nhạc AI

Model không nằm trong APK. Mặc định 2-stem dùng bản chuyển đổi Mel-Band RoFormer waveform ONNX. Lần đầu dùng, ứng dụng tải model vào vùng riêng, hỗ trợ tiếp tục từ tệp `.part` và chỉ sử dụng sau khi xác minh:

- Dung lượng: `953,292,899` byte.
- SHA-256: `64a4f3bee48fbe7d971b23875adc924ed004c3533f49672592641dddc0f6f561`.
- Revision nguồn: `60cb6b4b97e41b42f7ff16c2e386f47a8cc7e50a`.
- Tensor: input `mix [1,2,352800]`, output `sources [1,2,2,352800]`; source 0 là vocals, source 1 là instrumental.

Pipeline dùng cửa sổ 8 giây, overlap 50%, reflect-padding ở hai biên và PCM float32 trung gian. Trước khi chạy, ứng dụng kiểm tra thời lượng, dung lượng trống, RAM tổng và RAM khả dụng. Mel-Band RoFormer nhắm tới điện thoại cận cao cấp/cao cấp; yêu cầu RAM 8 GB trở lên và khoảng 2,5 GB RAM trống.

Model, tensor contract, chuẩn hóa, chunking và ánh xạ source nằm trong registry chung. Xem `docs/ADDING_STEM_MODELS.md` để thêm checkpoint hoặc xây luồng nhập model thử nghiệm sau này.

## Báo lỗi bằng gói chẩn đoán

Vào **Cài đặt > Nhật ký chẩn đoán > Tạo gói nhật ký**, sau đó chọn **Gửi ZIP** hoặc **Lưu ZIP**. Khi tách stem thất bại, cùng thao tác này xuất hiện ngay dưới thông báo lỗi.

ZIP chứa summary thiết bị/tác vụ, lịch sử lý do process thoát và log JSONL theo session/phase/chunk. Nó không chứa media hay model; URI, đường dẫn, tên tệp media, metadata và token được che trước khi ghi. Xem `docs/DIAGNOSTICS.md` để biết schema và cách đọc.

## Vì sao ZIP nguồn nhỏ nhưng APK lớn?

Kho nguồn không chứa cache Gradle, dependency native, APK, model AI hoặc thư mục build. Gradle tải FFmpegKit và ONNX Runtime trong lần build đầu; APK arm64 vẫn có thể lớn vì hai bộ native library này.

## Gradle bootstrap

Dự án kèm `gradle-wrapper.jar` bootstrap tối giản. Nó tải Gradle 8.13, kiểm tra SHA-256, khóa cache, chặn đường dẫn ZIP vượt thư mục và chuyển tiếp tham số cho Gradle đã giải nén.

Đây không phải JAR wrapper tiêu chuẩn do lệnh `gradle wrapper` tạo. Sau khi có Gradle 8.13 trên máy, có thể thay bằng wrapper chính thức bằng:

```bash
gradle wrapper --gradle-version 8.13 --distribution-type bin
```

Giữ nguyên `distributionSha256Sum` trong `gradle/wrapper/gradle-wrapper.properties`.

## Kiểm tra đi kèm

```bash
python3 scripts/verify_project.py
./scripts/run_core_smoke.sh
python3 scripts/test_wrapper_bootstrap.py
```

GitHub Actions còn chạy:

- `lintDebug`.
- `testDebugUnitTest`.
- `assembleDebug` và `assembleInternal`.
- Kiểm tra APK chỉ chứa `arm64-v8a` và đủ native library FFmpeg.
- Build instrumentation APK; bài test chạy thật phải thực hiện trên thiết bị arm64.

## Tài liệu

- `PROJECT_STATUS.md`: trạng thái xác minh và giới hạn còn lại.
- `CHANGELOG.md`: thay đổi của bản 1.3.1.
- `docs/ARCHITECTURE.md`: kiến trúc và luồng dữ liệu.
- `docs/ADDING_STEM_MODELS.md`: contract và quy trình thêm/nhập model.
- `docs/MEL_BAND_ROFORMER_INTEGRATION.md`: artifact, nguồn tham chiếu và checklist benchmark.
- `docs/DIAGNOSTICS.md`: schema log, event code, lưu giữ và bảo vệ riêng tư.
- `docs/RELEASE_CHECKLIST.md`: checklist trước khi phát hành.
- `THIRD_PARTY_NOTICES.md`: thông báo thành phần bên thứ ba.

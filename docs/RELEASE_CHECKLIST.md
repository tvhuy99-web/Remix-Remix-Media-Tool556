# Checklist phát hành MediaTool

## Build

- [ ] Gradle Sync bằng JDK 17 và Android SDK 36 thành công.
- [ ] `python3 scripts/verify_project.py` đạt.
- [ ] `./scripts/run_core_smoke.sh` đạt.
- [ ] `./gradlew clean lintDebug testDebugUnitTest assembleDebug assembleInternal` đạt.
- [ ] `./gradlew connectedDebugAndroidTest` đạt trên thiết bị arm64 thật hoặc device farm arm64.
- [ ] Tạo `keystore.properties` ngoài Git và build `assembleRelease` thành công.
- [ ] Chạy `python3 scripts/inspect_apks.py` với toàn bộ APK đầu ra.

## Thiết bị thật

- [ ] Cài bản arm64-v8a trên ít nhất một máy Android 10.
- [ ] Cài trên Android 13 và kiểm tra quyền thông báo.
- [ ] Cài trên Android 15/16 và kiểm tra `mediaProcessing` timeout.
- [ ] Ghi microphone: bắt đầu, tạm dừng, tiếp tục, dừng, lưu và chia sẻ.
- [ ] Ghi playback capture và thử hệ thống thu hồi MediaProjection.
- [ ] TTS tiếng Việt, tua video, tạm dừng và khôi phục âm lượng ducking.
- [ ] Gây một lỗi TTS cũ sau seek và xác nhận callback thế hệ cũ không đổi trạng thái/volume của câu mới.
- [ ] Cắt, nối, trộn, slideshow và hiệu ứng với file ngắn/dài, file không có audio và file hỏng.
- [ ] Tải model mới, hủy giữa chừng, tiếp tục tải và kiểm tra SHA-256.
- [ ] Tách 2 stem và 4 stem; hủy giữa inference; kiểm tra output dở đã bị xóa.
- [ ] Tải đủ Mel-Band RoFormer và xác nhận tệp `953292899` byte có SHA-256 `64a4f3bee48fbe7d971b23875adc924ed004c3533f49672592641dddc0f6f561`.
- [ ] So sánh output Mel-Band với pipeline PyTorch tham chiếu trên bộ bài cố định; xác nhận source 0 là vocals, source 1 là instrumental.
- [ ] Kiểm tra bài dưới 8 giây, đúng 8 giây và dài hơn 8 giây; số sample đầu ra phải bằng đầu vào và không có click ở ranh giới 4 giây.
- [ ] Đo thời gian tạo session, peak RSS và throttling trên máy RAM 8 GB và 12 GB.
- [ ] Chạy CPU, NNAPI và XNNPACK; xác nhận provider lỗi sẽ fallback CPU thay vì làm hỏng tác vụ.
- [ ] Giả lập thiếu RAM và xác nhận `OutOfMemoryError` không kích hoạt lần tạo session CPU thứ hai.
- [ ] Tạo gói chẩn đoán từ Cài đặt và từ lỗi stem; mở ZIP, parse mọi dòng JSONL và kiểm tra `summary.json`.
- [ ] Dùng media có tên/artist/album riêng tư; xác nhận ZIP không chứa URI, đường dẫn, tên tệp, metadata hoặc mẫu waveform.
- [ ] Sau crash/ANR thử nghiệm trên Android 11+, xác nhận `recent_process_exits` ghi đúng lý do.
- [ ] Xoay màn hình trong lúc chọn/cấu hình và sau khi có kết quả.
- [ ] Font scale lớn, dark mode và màn hình rộng 320 dp.

## Native và dung lượng

- [ ] APK/AAB chỉ chứa `arm64-v8a`, đủ thư viện FFmpeg và có đúng một `libc++_shared.so`.
- [ ] Smoke test FFmpeg và ONNX cùng chạy trong một phiên ứng dụng.
- [ ] Xem kích thước artifact arm64 và cân nhắc App Bundle trước khi phát hành.
- [ ] R8 không loại lớp JNI/reflection cần thiết.

## Pháp lý và phát hành

- [ ] Kiểm tra lại giấy phép package FFmpegKit và codec thực tế trong APK.
- [ ] Giữ `THIRD_PARTY_NOTICES.md` và asset notice trong bản phát hành.
- [ ] Xác nhận giấy phép model được phép phân phối/tải theo cách dự kiến.
- [ ] Không đưa `keystore.properties`, `.jks`, `.keystore`, `local.properties` hoặc model vào Git.
- [ ] Tăng `versionCode` và cập nhật `CHANGELOG.md`.
- [ ] Lưu SHA-256 của APK/AAB phát hành.

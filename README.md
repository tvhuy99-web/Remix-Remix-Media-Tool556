# MediaTool

Ứng dụng Android xử lý audio/video trực tiếp trên thiết bị.

## Tách Audio AI

Model 2 stem:

- UVR MDX-Net Voc FT.
- Demucs.

Model 4 stem:

- Demucs.

Model được tải riêng và kiểm tra dung lượng cùng SHA-256 trước khi chạy.

UVR ưu tiên LiteRT GPU và fallback LiteRT CPU/XNNPACK. Demucs ưu tiên XNNPACK và fallback CPU. CPU/XNNPACK dùng đúng số luồng đã chọn: 1, 2, 4 hoặc 8.

## Build

Yêu cầu JDK 17, Android SDK 36 và thiết bị `arm64-v8a`.

```bash
chmod +x gradlew
./gradlew lintDebug testDebugUnitTest assembleDebug
```

## Kiểm tra

Chạy toàn bộ kiểm tra cục bộ bằng một lệnh:

```bash
./scripts/check_local.sh
```

`verify_project.py` chỉ kiểm tra cấu trúc và metadata có thể phân tích chắc chắn. Việc biên dịch, lint và unit test được Gradle thực thi thật, không suy đoán bằng cách tìm chuỗi trong mã nguồn hoặc workflow.

## Nhật ký

Trong Cài đặt, dùng **Xóa nhật ký** trước khi thử và **Tạo ZIP** sau khi chạy.

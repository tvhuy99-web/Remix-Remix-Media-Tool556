# Trạng thái MediaTool

## AI model

- UVR MDX-Net Voc FT: 2 stem, LiteRT GPU, fallback CPU/XNNPACK.
- Demucs: 2 hoặc 4 stem, XNNPACK, fallback CPU.
- Số luồng CPU/XNNPACK: 1, 2, 4 hoặc 8 theo cài đặt.
- Model được tải riêng, hỗ trợ tiếp tục tải và kiểm tra SHA-256.

## An toàn

- Xử lý PCM float32.
- Kiểm tra RAM và dung lượng trước khi chạy.
- Hủy FFmpeg và inference khi người dùng dừng.
- Xóa output dở khi lỗi.
- Nhật ký JSONL có xoay vòng, xóa log và xuất ZIP.

## Kiểm thử

- Build APK debug.
- Kiểm tra chữ ký và native library.
- Lint và unit test.
- FFT 6144, overlap-add, model contract và downloader có test.

PR thử nghiệm chưa merge trước khi benchmark thiết bị thật đạt yêu cầu.

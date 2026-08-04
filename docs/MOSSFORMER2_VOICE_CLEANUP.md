# Tích hợp MossFormer2_SE_48K

## Phạm vi

Ứng dụng cung cấp một tính năng duy nhất **Làm sạch giọng**. Model chạy ngoại tuyến trên thiết bị bằng ONNX Runtime CPU. Không có mức nhẹ, vừa hoặc mạnh trong phiên bản đầu.

## Model đã ghim

- Model gốc: `modelscope/ClearerVoice-Studio`, `MossFormer2_SE_48K`.
- Bản xuất ONNX: `TigreGotico/audiosronnx-mossformer2`.
- Revision: `0d91401f480ab971bb26daa108771c5fc9c8cfeb`.
- Tệp: `mossformer2_48k.onnx`.
- Dung lượng chính xác: `229126935` byte.
- SHA-256: `0904ff3b74bdc089854612096edbe5a2fcfada489241972ba69e0c3ccb24304a`.
- Giấy phép: Apache-2.0.

Model không nằm trong APK. Người dùng tải model từ màn hình tính năng; downloader hỗ trợ tiếp tục tải và kiểm tra dung lượng cùng SHA-256 trước khi cho phép suy luận.

## Contract DSP

- Sample rate: 48.000 Hz, mono.
- Đoạn suy luận: 192.000 mẫu, tương đương 4 giây.
- Stride: 144.000 mẫu, tương đương 3 giây.
- Bỏ mép: 24.000 mẫu, tương đương 0,5 giây mỗi phía thích hợp.
- Frontend: Kaldi-style fbank 60 mel, frame 40 ms, shift 8 ms, pre-emphasis 0,97, Hamming đối xứng, FFT 2.048.
- Feature model: 60 mel + delta + delta-delta, shape `[1, 496, 180]`.
- Mask model: shape `[1, 496, 961]`.
- STFT mask: FFT 1.920, hop 384, Hamming đối xứng, không center.
- iSTFT: overlap-add và chia envelope bình phương cửa sổ.

## Pipeline file dài

1. FFmpeg giải mã audio/video thành PCM float32 mono 48 kHz.
2. Đọc từng đoạn 4 giây từ file tạm, không nạp toàn bộ nội dung vào RAM.
3. Tạo fbank và hai bậc delta.
4. Chạy ONNX Runtime CPU.
5. Áp mask vào phổ phức và iSTFT.
6. Ghép phần trung tâm của từng đoạn theo contract chính thức.
7. Ghi PCM kết quả theo luồng.
8. Chuẩn hóa -16 LUFS, true peak -1 dB và mã hóa theo định dạng trong Cài đặt.

## Bảo vệ vận hành

- Foreground service loại `mediaProcessing`.
- Partial wake lock trong khi xử lý, tối đa 6 giờ.
- Kiểm tra thời lượng, dung lượng tạm và RAM trống trước khi chạy.
- Hủy FFmpeg và ONNX RunOptions khi người dùng hủy.
- Tự dọn file tạm khi thành công, thất bại hoặc bị hủy.
- Lưu trạng thái tác vụ và khôi phục kết quả sau khi Activity được tạo lại.
- Ghi diagnostics theo từng đoạn, RTF inference và peak PSS.

## Giới hạn cần đo trên thiết bị thật

- ONNX graph khoảng 229 MB và có thể dùng gần 500 MB RSS hoặc hơn tùy runtime/SoC.
- Bản đầu dùng CPU để tránh sai khác provider và operator.
- Đầu ra là audio mono kể cả khi đầu vào là video hoặc stereo.
- Tốc độ, nhiệt và chất lượng tiếng Việt phải được đánh giá bằng APK CI trên thiết bị đích trước khi hợp nhất.

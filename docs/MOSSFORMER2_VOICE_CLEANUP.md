# Tích hợp MossFormer2_SE_48K

## Phạm vi

Ứng dụng cung cấp một tính năng **Làm sạch giọng** chạy ngoại tuyến bằng ONNX Runtime CPU. MossFormer2 là model duy nhất; các điều khiển trên giao diện chỉ thay đổi âm lượng đầu ra và limiter, không thay đổi mask AI.

## Model đã ghim

- Model gốc: `modelscope/ClearerVoice-Studio`, `MossFormer2_SE_48K`.
- Bản xuất ONNX: `TigreGotico/audiosronnx-mossformer2`.
- Revision: `0d91401f480ab971bb26daa108771c5fc9c8cfeb`.
- Tệp: `mossformer2_48k.onnx`.
- Dung lượng chính xác: `229126935` byte.
- SHA-256: `0904ff3b74bdc089854612096edbe5a2fcfada489241972ba69e0c3ccb24304a`.
- Giấy phép: Apache-2.0.

Model không nằm trong APK. Downloader hỗ trợ tiếp tục tải và chỉ cho phép suy luận sau khi dung lượng cùng SHA-256 khớp descriptor.

## Contract DSP

- Sample rate: 48.000 Hz, mono.
- Đoạn suy luận: 192.000 mẫu, tương đương 4 giây.
- Stride: 144.000 mẫu, tương đương 3 giây.
- Bỏ mép: 24.000 mẫu, tương đương 0,5 giây.
- Frontend: Kaldi-style fbank 60 mel, frame 40 ms, shift 8 ms, pre-emphasis 0,97, Hamming đối xứng, FFT 2.048.
- Feature model: 60 mel + delta + delta-delta, shape `[1, 496, 180]`.
- Mask model: shape `[1, 496, 961]`.
- STFT mask: FFT 1.920, hop 384, Hamming đối xứng, không center.
- iSTFT: overlap-add và chia envelope bình phương cửa sổ.

## Pipeline file dài

1. FFmpeg giải mã audio hoặc video thành PCM float32 mono 48 kHz.
2. Đo RMS, sample peak, true peak và integrated LUFS của bản gốc.
3. Đọc từng đoạn 4 giây từ file tạm, tạo fbank và chạy ONNX Runtime CPU.
4. Ghi thống kê mask từng đoạn và toàn tác vụ.
5. Áp mask vào phổ phức, iSTFT và ghi PCM kết quả theo luồng.
6. Đo lại RMS, peak và LUFS ngay sau AI, trước mọi thay đổi âm lượng.
7. Tính gain cố định theo chế độ người dùng chọn.
8. Áp limiter khi được bật, mã hóa và đo lại file cuối.

## Điều khiển đầu ra

### Giữ mức sau AI

Tắt cả **Khớp âm lượng bản gốc** và **Chuẩn hóa tới LUFS mục tiêu**. Chỉ gain bổ sung cùng limiter được áp dụng.

### Khớp âm lượng bản gốc

Ứng dụng lấy chênh lệch integrated LUFS giữa bản gốc và PCM sau AI rồi áp một gain cố định. Khi LUFS không khả dụng, RMS được dùng làm fallback. Đây là chế độ mặc định để so sánh A/B công bằng.

### LUFS mục tiêu

Ứng dụng lấy chênh lệch giữa LUFS mục tiêu và LUFS sau AI rồi áp gain cố định. Không dùng `loudnorm` động, vì bộ lọc đó có thể làm thay đổi cảm nhận và che lấp tác dụng thật của model.

### Gain và limiter

- Gain bổ sung: từ -12 đến +12 dB, bước 0,5 dB.
- Limiter có thể bật hoặc tắt độc lập.
- Trần limiter: từ -6 đến -0,5 dBFS.
- Tổng gain tự động và gain bổ sung được chặn trong khoảng -24 đến +24 dB.

## Giao diện thử nghiệm

- Điều khiển vẫn hiển thị sau khi xử lý để có thể chỉnh và bấm **Xử lý lại**.
- Trình phát A/B chuyển giữa **Bản gốc** và **Kết quả**.
- Thẻ thống kê hiển thị LUFS, RMS, sample peak, true peak, gain đã áp và RTF.
- Kết quả vẫn có hành động lưu và chia sẻ.

## Diagnostics

Mỗi tác vụ ghi:

- Audio metrics cho `source`, `after_ai` và `final_output`.
- Mask min, max, mean, p10, p50, p90.
- Tỷ lệ mask dưới 0,9 và dưới 0,5.
- Tỷ lệ mask gần 1 để phát hiện graph gần passthrough.
- Tỷ lệ mask nằm ngoài khoảng 0 đến 1.
- Gain thực tế, cấu hình loudness/limiter, inference RTF và peak PSS.

## Bảo vệ vận hành

- Foreground service loại `mediaProcessing` và partial wake lock tối đa 6 giờ.
- Kiểm tra thời lượng, dung lượng tạm và RAM trống trước khi chạy.
- Hủy FFmpeg và ONNX RunOptions khi người dùng hủy.
- Tự dọn file tạm khi thành công, thất bại hoặc bị hủy.
- Mask có NaN hoặc vô cực bị từ chối ngay.
- Đầu ra là audio mono kể cả khi đầu vào là video hoặc stereo.

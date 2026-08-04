# Tích hợp MossFormer2_SE_48K

## Phạm vi

Ứng dụng cung cấp tính năng **Làm sạch giọng** chạy ngoại tuyến bằng ONNX Runtime CPU. Người dùng chọn độ dài ngữ cảnh AI, cách giữ âm lượng và limiter; mọi xử lý vẫn dùng cùng một model MossFormer2 có trục thời gian động.

## Model đã ghim

- Model gốc: `modelscope/ClearerVoice-Studio`, `MossFormer2_SE_48K`.
- Bản xuất ONNX: `TigreGotico/audiosronnx-mossformer2`.
- Revision: `0d91401f480ab971bb26daa108771c5fc9c8cfeb`.
- Tệp: `mossformer2_48k.onnx`.
- Dung lượng chính xác: `229126935` byte.
- SHA-256: `0904ff3b74bdc089854612096edbe5a2fcfada489241972ba69e0c3ccb24304a`.
- Giấy phép: Apache-2.0.

Model không nằm trong APK. Downloader hỗ trợ tiếp tục tải và chỉ cho phép suy luận sau khi dung lượng cùng SHA-256 khớp descriptor.

## Ba chế độ ngữ cảnh

- **Tương thích, 4 giây:** hàng rào RAM khả dụng 768 MiB.
- **Cân bằng, 10 giây:** mặc định, hàng rào RAM khả dụng 1,5 GiB.
- **Tối đa, 15 giây:** dành cho thiết bị rất mạnh, hàng rào RAM khả dụng 3 GiB.

Tệp ngắn hơn giới hạn của chế độ được chạy một lượt với toàn bộ ngữ cảnh và không bỏ mép. Chế độ Tối đa cho phép one-pass tới 20 giây. Tệp dài hơn được chia theo cửa sổ đã chọn với stride 75% và bỏ 1/8 cửa sổ ở mỗi mép nối.

Kích thước model được tính động:

- 4 giây: `[1, 496, 180]` → `[1, 496, 961]`.
- 10 giây: `[1, 1246, 180]` → `[1, 1246, 961]`.
- 15 giây: `[1, 1871, 180]` → `[1, 1871, 961]`.
- One-pass 20 giây dùng số frame được căn theo hop 384 mẫu.

## Contract DSP

- Sample rate: 48.000 Hz, mono.
- Frontend: Kaldi-style fbank 60 mel, frame 40 ms, shift 8 ms, pre-emphasis 0,97, Hamming đối xứng, FFT 2.048.
- Feature model: 60 mel + delta + delta-delta.
- STFT mask: FFT 1.920, hop 384, Hamming đối xứng, không center.
- iSTFT: overlap-add và chia envelope bình phương cửa sổ.
- Waveform dùng để áp mask luôn là waveform gốc, không chứa dither.

## Dither và parity frontend

Upstream gọi Kaldi fbank với `dither=1.0`. Ứng dụng áp Gaussian dither mức 1 LSB chỉ trên bản sao dùng để tạo feature. Bộ sinh số dùng seed cố định theo segment để kết quả có thể tái lập.

Ở segment đầu, diagnostics tạo cả feature không dither và feature 1-LSB, sau đó ghi:

- Mean absolute difference.
- RMSE.
- Maximum absolute difference.
- Tỷ lệ phần tử thay đổi.

Unit test golden đối chiếu các frame đại diện với `torchaudio.compliance.kaldi.fbank` khi dither tắt. Dung sai chéo runtime là 0,002 log-mel, khóa sai số FFT float Android so với backend torchaudio nhưng vẫn nhỏ hơn nhiều mức có ý nghĩa âm thanh. Script `scripts/generate_mossformer2_frontend_golden.py` tái tạo fixture bằng PyTorch/torchaudio.

## Pipeline

1. FFmpeg giải mã audio hoặc video thành PCM float32 mono 48 kHz.
2. Đo RMS, sample peak, true peak và integrated LUFS của bản gốc.
3. Chọn kế hoạch one-pass hoặc chia đoạn sau khi biết chính xác số mẫu.
4. Tạo fbank, chạy ONNX Runtime CPU và áp mask lên phổ của waveform gốc.
5. Chỉ thống kê mask trên frame có dữ liệu thật và có tâm nằm trong vùng đầu ra được giữ lại; frame padding bị loại.
6. Đo sample jump và chênh RMS ở mỗi điểm nối, ghi cảnh báo khi vượt ngưỡng.
7. Ghi PCM kết quả theo luồng và xác minh đúng số mẫu nguồn.
8. Đo lại PCM sau AI, tính gain cố định, áp limiter khi được bật, mã hóa và đo file cuối.

## Điều khiển đầu ra

### Giữ nguyên kết quả lọc

Không tự bù loudness. Chỉ gain bổ sung và limiter được áp dụng.

### Giống bản gốc

Đây là mặc định. Ứng dụng lấy chênh lệch integrated LUFS giữa bản gốc và PCM sau AI rồi áp một gain cố định. Khi LUFS không khả dụng, RMS được dùng làm fallback.

### Đặt âm lượng mong muốn

Ứng dụng lấy chênh lệch giữa LUFS mục tiêu và LUFS sau AI rồi áp gain cố định. Không dùng `loudnorm` động để tránh che lấp tác dụng thật của model.

### Gain và limiter

- Gain bổ sung: từ -12 đến +12 dB, bước 0,5 dB.
- Limiter có thể bật hoặc tắt độc lập.
- Trần limiter: từ -6 đến -0,5 dBFS.
- Tổng gain tự động và gain bổ sung được chặn trong khoảng -24 đến +24 dB.

## Diagnostics

Mỗi tác vụ ghi:

- Audio metrics cho `source`, `after_ai` và `final_output`.
- Kế hoạch cửa sổ thực tế, số frame, stride, edge discard, full-context và RAM yêu cầu.
- Mask min, max, mean, p10, p50, p90, số frame và số giá trị hợp lệ.
- Tỷ lệ mask dưới 0,9, dưới 0,5, gần 1 và ngoài khoảng 0 đến 1.
- Số frame padding đã bị loại khỏi thống kê.
- Dither A/B trên segment đầu.
- Seam count, sample jump, relative jump dB và RMS delta dB.
- `model_open_ms`, `frontend_ms`, `onnx_ms`, `mask_apply_ms`, `pcm_write_ms`, `enhance_ms`.
- `onnx_rtf`, `enhance_rtf`, `pipeline_ms` và `pipeline_rtf`.
- Peak PSS, gain thực tế và cấu hình loudness/limiter.

## Hủy và lỗi nghiêm trọng

- `CancellationException` luôn được truyền lại để UI ghi đúng trạng thái hủy.
- `Error`, gồm OOM và lỗi JVM nghiêm trọng, không bị biến thành lỗi đo metrics có thể bỏ qua.
- Chỉ `Exception` thông thường trong bước đo loudness mới được ghi cảnh báo và fallback sang metrics rỗng.
- FFmpeg và ONNX RunOptions được kết thúc khi người dùng hủy.

## Bảo vệ vận hành

- Foreground service loại `mediaProcessing` và partial wake lock tối đa 6 giờ.
- Kiểm tra thời lượng, dung lượng tạm và RAM trống trước khi chạy.
- Tự dọn file tạm khi thành công, thất bại hoặc bị hủy.
- Mask và PCM có NaN hoặc vô cực bị từ chối ngay.
- Đầu ra là audio mono kể cả khi đầu vào là video hoặc stereo.

## Xác minh

GitHub Actions đã đạt toàn bộ trên nhánh `agent/voice-cleanup-window-modes`:

- Xác minh cấu trúc dự án.
- Build APK debug.
- Kiểm tra APK và ABI arm64-v8a.
- Xác minh chữ ký APK.
- Android Lint.
- Toàn bộ unit test, gồm parity frontend, dither tái lập, hình học one-pass/chia đoạn, mask bỏ padding, seam metrics và hủy tác vụ.

## Xác minh còn cần trên thiết bị

CI không nghe được âm thanh và không mô phỏng được nhiệt hoặc áp lực RAM của điện thoại. Trước khi gộp cần chạy cùng một bộ audio thật trên điện thoại cao cấp ở cả ba chế độ để kiểm tra peak PSS, nhiệt, tốc độ, chất lượng phụ âm và các điểm nối.

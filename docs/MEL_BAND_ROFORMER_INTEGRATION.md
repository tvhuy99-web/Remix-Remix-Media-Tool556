# Ghi chú tích hợp Mel-Band RoFormer 2-stem

Ngày đối chiếu: 2026-08-01.

## Nguồn tham chiếu

- Bài báo Mel-Band RoFormer: https://arxiv.org/abs/2310.01809
- Checkpoint/inference vocals của KimberleyJensen: https://github.com/KimberleyJensen/Mel-Band-Roformer-Vocal-Model
- Bản chuyển đổi waveform ONNX được chọn: https://huggingface.co/smank/mel-band-roformer-vocals-onnx
- Catalog/backend nhiều kiến trúc để tham khảo cách tách model khỏi engine: https://github.com/nomadkaraoke/python-audio-separator
- Cấu hình XNNPACK của ONNX Runtime: https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html

## Artifact đã chọn

- Revision: `60cb6b4b97e41b42f7ff16c2e386f47a8cc7e50a`
- Tệp: `melband_roformer_vocals.onnx`
- Kích thước: `953292899` byte
- SHA-256/LFS OID: `64a4f3bee48fbe7d971b23875adc924ed004c3533f49672592641dddc0f6f561`
- Opset: 17
- Input: float32 `mix [1, 2, 352800]`
- Output: float32 `sources [1, 2, 2, 352800]`
- Source 0: vocals; source 1: instrumental/other.

Endpoint ghim revision đã được kiểm tra bằng range request. Phản hồi trả `206`, `Content-Range: bytes 0-1023/953292899`, `X-Repo-Commit` đúng revision và `X-Linked-ETag` đúng SHA-256 ở trên.

## Quyết định pipeline

- Giải mã stereo float32 ở 44,1 kHz; không dùng global mean/std cho model này.
- Chunk đúng 352.800 frame (8 giây), bước 176.400 frame, tương đương `num_overlap=2` của cấu hình tham chiếu.
- Edge fade 35.280 frame; khi ghép, chia cho tổng hai trọng số tương đương window/counter để không tăng biên độ.
- Với bài dài hơn 8 giây, thêm reflect-padding 176.400 frame ở hai đầu, sau đó trim theo sample chính xác khi encode.
- Giữ PCM output ở float32 đến codec cuối để tránh clip stem ở trung gian.
- CPU là lựa chọn mặc định. NNAPI tạm bị chặn cho descriptor này cho đến khi có ma trận tương thích; XNNPACK vẫn là thử nghiệm và phải fallback CPU.
- XNNPACK dùng pool riêng; ORT intra-op bằng 1 và tắt spinning theo tài liệu ONNX Runtime.
- Mỗi lần chạy ghi provider thực tế, thời gian tạo session, shape tensor, thời gian/heap từng chunk và phase FFmpeg vào gói chẩn đoán; không ghi waveform hay đường dẫn nguồn.

## Giới hạn cần đo trên thiết bị

Mã nguồn và contract đã được kiểm tra tĩnh/core smoke, nhưng checkpoint gần 1 GB không được tải toàn bộ trong môi trường tái cấu trúc. Trước phát hành cần chạy trên arm64 RAM 8/12 GB và ghi lại:

- thời gian tạo session và thời gian mỗi chunk;
- peak RSS/available RAM trước và sau session;
- lỗi operator/session ở CPU, NNAPI và XNNPACK;
- nhiệt độ/throttling với bài 3, 10 và 30 phút;
- độ dài sample chính xác và ranh giới chunk;
- so sánh ít nhất 10 bài với pipeline PyTorch tham chiếu.

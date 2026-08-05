# MDX23C vocal-only export lab

Thư mục này chuyển checkpoint vocal MDX23C của MSST thành ONNX **spectrogram core** cố định. STFT, iSTFT, reflect padding và overlap-add vẫn chạy trong host Android.

Nguồn kiến trúc và config được khóa theo commit MSST `e247dfe4abc1f17c69dff719207fe045dc04413a`. Không dùng nhánh `main` trôi nổi khi tạo artifact.

## Artifact dùng cá nhân đã xác minh

- Release tag: `mdx23c-vocal-personal-v1`.
- URL tải: `https://github.com/tvhuy99-web/Remix-Remix-Media-Tool556/releases/download/mdx23c-vocal-personal-v1/mdx23c-vocals-core.onnx`.
- Tệp: `mdx23c-vocals-core.onnx`.
- Kích thước: `448152790` byte.
- SHA-256 ONNX: `8925ece1f0da006d342856f93e75ba2dea9058d44c286c4cd6a98a41c67367bb`.
- SHA-256 checkpoint gốc: `49d51472769e34a2501cd1da782346a3212555c3a5619fc2c53507445528d816`.
- Opset: 18.
- PyTorch/ONNX Runtime max absolute error: `5.8770179748535156e-05`.
- PyTorch/ONNX Runtime mean absolute error: `1.1756378626159858e-06`.
- Phạm vi: dùng cá nhân/phi thương mại.

Graph đã qua `onnx.checker`, không dùng external data và chỉ chứa operator chuẩn: `Add`, `Concat`, `Constant`, `Conv`, `ConvTranspose`, `Div`, `Erf`, `Gather`, `InstanceNormalization`, `MatMul`, `Mul`, `Reshape`, `Transpose`.

## Contract đã khóa

- Audio: stereo 44.100 Hz.
- Waveform chunk: 261.120 frame.
- STFT: FFT 8192, hop 1024, giữ 4096 bin, 256 time frame.
- Input ONNX: `spectrogram [1, 4, 4096, 256]`, float32.
- Output ONNX: `vocals_spectrogram [1, 4, 4096, 256]`, float32.
- Bước inference: 65.280 frame, tương ứng `num_overlap: 4`.
- Overlap: 75%.
- Linear edge fade: 26.112 frame, tương ứng `chunk_size // 10`.
- Reflect boundary: 195.840 frame mỗi bên đối với audio đủ dài.
- Instrumental: `mix gốc - vocals`.
- Polarity denoise: tắt vì checkpoint này không có contract denoise đã xác minh.

## Export lại

Dùng checkout MSST bất biến và đúng config/checkpoint:

```bash
git -C /path/to/Music-Source-Separation-Training checkout e247dfe4abc1f17c69dff719207fe045dc04413a
python -m venv .venv
. .venv/bin/activate
pip install -r tools/mdx23c/requirements.txt

python tools/mdx23c/export_vocal_core_onnx.py \
  --msst-root /path/to/Music-Source-Separation-Training \
  --config /path/to/config_vocals_mdx23c.yaml \
  --checkpoint /path/to/model_vocals_mdx23c_sdr_10.17.ckpt \
  --output /tmp/mdx23c-vocals-core.onnx

python tools/mdx23c/validate_vocal_core_onnx.py /tmp/mdx23c-vocals-core.onnx
```

Exporter đối chiếu PyTorch với ONNX Runtime và ghi manifest JSON chứa SHA-256 checkpoint, SHA-256 ONNX, kích thước, tensor metadata và parity.

## Trạng thái Android

Artifact đã được ghim trong `StemModelRegistry` với backend `MDX_ONNX`, CPU fallback, XNNPACK thử trước khi phù hợp và guard thiết bị 8 GB RAM/3 GB RAM trống. UVR MDX-Net vẫn là lựa chọn mặc định; MDX23C xuất hiện như lựa chọn riêng để đo chất lượng và tài nguyên trên thiết bị thật.

Trước khi coi giai đoạn 1 hoàn tất trên thiết bị cần thu diagnostics OnePlus cho:

1. provider thực tế và fallback;
2. peak PSS, native heap và RAM khả dụng;
3. thời gian mở session và từng chunk;
4. seam metrics quanh bước 65.280 frame;
5. reconstruction, sample length, NaN/Inf và clipping;
6. so sánh bài sáo/humming khó với UVR MDX-Net.

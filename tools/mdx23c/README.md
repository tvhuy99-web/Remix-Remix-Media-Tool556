# MDX23C vocal-only export lab

Thư mục này chuyển checkpoint vocal MDX23C chính thức của MSST thành ONNX **spectrogram core** cố định. STFT, iSTFT, reflect padding và overlap-add vẫn chạy trong host Android.

Nguồn kiến trúc và config được khóa theo commit MSST `e247dfe4abc1f17c69dff719207fe045dc04413a`. Không dùng nhánh `main` trôi nổi khi tạo artifact phân phối.

## Contract đã khóa

- Audio: stereo 44.100 Hz.
- Waveform chunk: 261.120 frame.
- STFT: FFT 8192, hop 1024, giữ 4096 bin, 256 time frame.
- Input ONNX: `spectrogram [1, 4, 4096, 256]`, float32.
- Output ONNX: `vocals_spectrogram [1, 4, 4096, 256]`, float32.
- Bước inference: 65.280 frame, tương ứng `num_overlap: 4`.
- Linear edge fade: 26.112 frame, tương ứng `chunk_size // 10`.
- Reflect boundary: 195.840 frame mỗi bên đối với audio đủ dài.
- Instrumental: `mix gốc - vocals`.

## Export

Dùng checkout MSST bất biến và đúng config/checkpoint:

```bash
git -C /path/to/Music-Source-Separation-Training checkout e247dfe4abc1f17c69dff719207fe045dc04413a
python -m venv .venv
. .venv/bin/activate
pip install -r tools/mdx23c/requirements.txt

python tools/mdx23c/export_vocal_core_onnx.py \
  --msst-root /path/to/Music-Source-Separation-Training \
  --config /path/to/Music-Source-Separation-Training/configs/config_vocals_mdx23c.yaml \
  --checkpoint /path/to/model_vocals_mdx23c_sdr_10.17.ckpt \
  --output /tmp/mdx23c-vocals-core.onnx

python tools/mdx23c/validate_vocal_core_onnx.py /tmp/mdx23c-vocals-core.onnx
```

Exporter mặc định đối chiếu PyTorch với ONNX Runtime và ghi manifest JSON chứa SHA-256 checkpoint, SHA-256 ONNX, kích thước và tensor metadata.

## Cổng đưa vào catalog

Không thêm prototype vào `StemModelRegistry` trước khi đủ các điều kiện:

1. Điều khoản phân phối trọng số của đúng checkpoint được ghi rõ.
2. ONNX là một tệp, có URL bất biến, kích thước byte và SHA-256 chính xác.
3. Parity desktop có max absolute error không quá `2e-4`.
4. ONNX Runtime Android mở được graph bằng CPU fallback, không custom operator.
5. Diagnostics OnePlus ghi peak PSS, native heap, thời gian mở session và thời gian từng chunk.
6. Mẫu sáo/humming khó phải thắng hoặc bổ sung rõ rệt cho UVR MDX-Net mà không tạo seam mới.
7. Reconstruction và độ dài sample vượt bộ kiểm tra PCM hiện có.

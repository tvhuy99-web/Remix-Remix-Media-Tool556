# Spatial Audio Hiss Protection

## Mục tiêu

Giảm cảm giác tiếng xì bị mở rộng hoặc kéo dài bởi HRTF và reverb mà không làm tối tín hiệu gốc. Đường dry PCM luôn giữ nguyên; mọi xử lý bảo vệ chỉ áp dụng cho tín hiệu đi vào renderer và các nhánh wet sau HRTF.

## Cơ sở thiết kế

- HRTF thay đổi biên độ, thời gian đến và phổ âm theo hướng nguồn. Vì vậy nhiễu rộng dải có thể trở nên dễ nhận biết hơn sau binaural rendering dù tổng năng lượng treble không tăng.
- Steam Audio hỗ trợ suy hao cao tần và các hệ số EQ ba dải cho reverb/direct path. Thiết kế dùng chính các tham số high-band này thay vì cắt treble trên master.
- FFmpeg `highshelf` chỉ giảm rất nhẹ dải cao của nhánh wet. Đường dry không qua shelf.
- FFmpeg `afftdn` có noise-floor tracking nhưng là bộ lọc FFT có latency. Vì vậy AUTO không dùng denoise; STRONG dùng mức nhẹ và bù 1.200 mẫu ở 48 kHz trước khi trộn dry/wet.

Tài liệu tham khảo:

- https://valvesoftware.github.io/steam-audio/doc/capi/guide.html
- https://valvesoftware.github.io/steam-audio/doc/capi/direct-effect.html
- https://ffmpeg.org/ffmpeg-filters.html#afftdn
- https://ffmpeg.org/ffmpeg-filters.html#treble

## Pipeline

1. Giải mã nguồn thành PCM stereo float 48 kHz.
2. Đo các block 1024 mẫu và ước lượng năng lượng trên 7 kHz trong 20% block yên nhất.
3. Tính `hiss_risk` từ noise floor cao tần, tỷ lệ high-band/broadband, độ ổn định và khả năng nghe thấy trong đoạn yên.
4. AUTO giữ nguyên spatial input. STRONG denoise nhẹ spatial input rồi trim/pad đúng 1.200 mẫu để giữ đồng bộ pha.
5. Render HRTF/reverb bằng Steam Audio và giảm EQ/RT60 cao tần theo `hiss_risk`.
6. Áp high-shelf rất nhẹ lên nhánh HRTF wet.
7. Sau HRTF, tách dải cao bằng bộ lọc một cực không tạo độ trễ và suy giảm riêng tai đối diện theo vị trí trái/phải của nguồn. Gain được làm mượt 40 ms; nguồn ở giữa không bị suy giảm liên tai.
8. Ghép Mid/Side hoặc Dual Object, sau đó blend với PCM dry nguyên bản và limiter có bù latency.

## Chế độ

- `AUTO`: mặc định. Không FFT denoise; dùng damping reverb, shelf rất nhẹ và suy giảm cao tần riêng tai đối diện.
- `STRONG`: dành cho bản thu cũ, cassette, live hoặc nguồn nén có hiss rõ; thêm denoise nhẹ có bù latency và tăng damping tai đối diện.
- `OFF`: tắt hoàn toàn để A/B; hard bypass 0% vẫn giữ nguyên như trước.

## Giới hạn an toàn

- AUTO không denoise nguồn.
- Không hard gate.
- Không denoise hoặc EQ đường dry.
- Không thêm limiter trung gian, tránh tái tạo lỗi comb filtering.
- Damping liên tai không tác động khi nguồn ở giữa và được làm mượt 40 ms.
- Diagnostics ghi profile, kế hoạch, damping tai đối diện và số mẫu bù latency.

## Hiệu chỉnh sau A/B trên thiết bị

A/B thực tế cho thấy STRONG ban đầu giảm hiss tổng thể nhưng làm giảm treble ở tai có nguồn nhiều hơn tai đối diện. Trong các cửa sổ lệch kênh hơn 3 dB:

- 8–12 kHz: tai trội giảm khoảng 7,8–8,4 dB, tai đối diện chỉ khoảng 2,7–3,1 dB.
- 12–20 kHz: tai trội giảm khoảng 14,0–14,6 dB, tai đối diện chỉ khoảng 3,9–4,9 dB.

Vì vậy lớp cao tần ở phía đối diện trở nên tương đối dễ nhận ra dù tổng hiss đã giảm. Bản hiệu chỉnh mới giảm mạnh shelf toàn nhánh, giữ treble ở tai gần nguồn và suy giảm riêng dải cao ở tai đối diện theo `pose.direction.x`.

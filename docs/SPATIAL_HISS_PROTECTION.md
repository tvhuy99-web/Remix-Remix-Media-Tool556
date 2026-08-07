# Spatial Audio Hiss Protection

## Mục tiêu

Giảm cảm giác tiếng xì bị mở rộng hoặc kéo dài bởi HRTF và reverb mà không làm tối tín hiệu gốc. Đường dry PCM luôn giữ nguyên; mọi xử lý bảo vệ chỉ áp dụng cho tín hiệu đi vào renderer và các nhánh wet sau HRTF.

## Cơ sở thiết kế

- Steam Audio mô tả HRTF là cặp bộ lọc thay đổi biên độ, thời gian đến và phổ âm theo hướng nguồn. Vì vậy nhiễu rộng dải có thể trở nên dễ nhận biết hơn sau binaural rendering dù tổng năng lượng treble không tăng.
- Steam Audio hỗ trợ suy hao cao tần theo khoảng cách và các hệ số EQ ba dải cho reverb/direct path. Thiết kế dùng chính các tham số high-band này thay vì cắt treble trên master.
- FFmpeg `highshelf` được dùng để giảm nhẹ 0,8–2,5 dB trên đúng nhánh wet sau HRTF. Đường dry không qua shelf.
- FFmpeg `afftdn` có noise-floor tracking, residual tracking và gain smoothing, nhưng là bộ lọc FFT có latency. Vì vậy nó không được dùng trong chế độ mặc định. Chỉ chế độ Mạnh mới dùng mức 3–5 dB và bù 1.200 mẫu ở 48 kHz trước khi trộn dry/wet.

Tài liệu tham khảo:

- https://valvesoftware.github.io/steam-audio/doc/capi/guide.html
- https://valvesoftware.github.io/steam-audio/doc/capi/direct-effect.html
- https://ffmpeg.org/ffmpeg-filters.html#afftdn
- https://ffmpeg.org/ffmpeg-filters.html#treble

## Pipeline

1. Giải mã nguồn thành PCM stereo float 48 kHz.
2. Đo các block 1024 mẫu và ước lượng năng lượng trên 7 kHz trong 20% block yên nhất.
3. Tính `hiss_risk` từ noise floor cao tần, tỷ lệ high-band/broadband, độ ổn định và khả năng nghe thấy trong đoạn yên.
4. Ở chế độ Tự động, giữ nguyên spatial input để tránh latency và musical-noise. Ở chế độ Mạnh, denoise nhẹ spatial input rồi trim/pad đúng 1.200 mẫu để giữ đồng bộ pha.
5. Render HRTF/reverb bằng Steam Audio, đồng thời rút ngắn RT60 và EQ dải cao theo `hiss_risk`.
6. Áp high-shelf nhẹ lên đúng các nhánh HRTF wet.
7. Ghép Mid/Side hoặc Dual Object, sau đó blend với PCM dry nguyên bản và limiter có bù latency.

## Chế độ

- `AUTO`: mặc định. Chỉ damping wet/reverb, không FFT denoise. Luôn bảo vệ nhẹ và tăng theo nội dung từng bài.
- `STRONG`: dành cho bản thu cũ, cassette, live hoặc nguồn nén có hiss rõ; dùng denoise nhẹ có bù latency.
- `OFF`: tắt hoàn toàn để A/B; hard bypass 0% vẫn giữ nguyên như trước.

## Giới hạn an toàn

- Auto không denoise nguồn.
- Auto high-shelf tối đa -2,5 dB.
- Strong denoise tối đa 5 dB và luôn bù latency.
- Không dùng hard gate.
- Không denoise hoặc EQ đường dry.
- Không thêm limiter trung gian, tránh tái tạo lỗi comb filtering.
- Diagnostics ghi profile, kế hoạch đã áp dụng và số mẫu bù latency để kiểm tra trên thiết bị.


## Hiệu chỉnh sau A/B trên thiết bị

A/B thực tế cho thấy chế độ Mạnh giảm hiss tổng thể nhưng làm giảm treble ở tai gần nhiều hơn tai xa, khiến lớp cao tần ở tai đối diện tương đối dễ nhận ra khi nguồn chạy sang một bên. Bản sửa tiếp theo vì vậy:

- giảm mạnh mức high-shelf toàn nhánh wet;
- dùng bộ tách cao tần một cực không latency ngay trong renderer;
- suy giảm chỉ tai đối diện, theo thành phần trái/phải của vị trí nguồn;
- làm mượt gain trong 40 ms để tránh zipper/pumping;
- tiếp tục giữ nguyên dry path và không damping khi nguồn ở giữa.

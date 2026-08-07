# Spatial Audio Hiss Protection

## Mục tiêu

Giảm cảm giác tiếng xì bị mở rộng hoặc kéo dài bởi HRTF và reverb mà không làm tối tín hiệu gốc. Đường dry PCM luôn giữ nguyên; mọi xử lý bảo vệ chỉ áp dụng cho tín hiệu đi vào renderer và các nhánh wet sau HRTF.

## Cơ sở thiết kế

- Steam Audio mô tả HRTF là cặp bộ lọc thay đổi biên độ, thời gian đến và phổ âm theo hướng nguồn. Vì vậy nhiễu rộng dải có thể trở nên dễ nhận biết hơn sau binaural rendering dù tổng năng lượng treble không tăng.
- Steam Audio hỗ trợ suy hao cao tần theo khoảng cách và các hệ số EQ ba dải cho reverb/direct path. Thiết kế dùng chính các tham số high-band này thay vì cắt treble trên master.
- FFmpeg `afftdn` hỗ trợ noise-floor tracking, residual tracking và gain smoothing. Bộ bảo vệ dùng mức giảm 1,5–4 dB ở chế độ Tự động, thấp hơn nhiều so với mặc định 12 dB, và chỉ trên spatial input.
- FFmpeg `highshelf` được dùng để giảm nhẹ 0,8–2,5 dB trên nhánh wet sau HRTF. Đường dry không qua shelf.

Tài liệu tham khảo:

- https://valvesoftware.github.io/steam-audio/doc/capi/guide.html
- https://valvesoftware.github.io/steam-audio/doc/capi/direct-effect.html
- https://ffmpeg.org/ffmpeg-filters.html#afftdn
- https://ffmpeg.org/ffmpeg-filters.html#treble

## Pipeline

1. Giải mã nguồn thành PCM stereo float 48 kHz.
2. Đo các block 1024 mẫu và ước lượng năng lượng trên 7 kHz trong 20% block yên nhất.
3. Tính `hiss_risk` từ noise floor cao tần, tỷ lệ high-band/broadband, độ ổn định và khả năng nghe thấy trong đoạn yên.
4. Tạo bản sao spatial input bằng `afftdn` mức nhẹ. PCM dry ban đầu không thay đổi.
5. Render HRTF/reverb bằng Steam Audio, đồng thời rút ngắn RT60 và EQ dải cao theo `hiss_risk`.
6. Áp high-shelf nhẹ lên đúng các nhánh HRTF wet.
7. Ghép Mid/Side hoặc Dual Object, sau đó blend với PCM dry nguyên bản và limiter có bù latency.

## Chế độ

- `AUTO`: mặc định. Luôn bảo vệ nhẹ và tăng tối đa theo nội dung từng bài.
- `STRONG`: dành cho bản thu cũ, cassette, live hoặc nguồn nén có hiss rõ.
- `OFF`: tắt hoàn toàn để A/B; hard bypass 0% vẫn giữ nguyên như trước.

## Giới hạn an toàn

- Auto denoise tối đa 4 dB.
- Auto high-shelf tối đa -2,5 dB.
- Không dùng hard gate.
- Không denoise hoặc EQ đường dry.
- Không thêm limiter trung gian, tránh tái tạo lỗi comb filtering do latency.
- Diagnostics ghi cả profile và kế hoạch đã áp dụng để kiểm tra trên thiết bị.

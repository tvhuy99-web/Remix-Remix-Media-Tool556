# Spatial Audio Engine

Spatial Audio dùng Steam Audio 4.8.1 để render binaural ngoại tuyến trên Android ARM64. FFmpeg giải mã nguồn về PCM float stereo 48 kHz, engine dựng trường âm, sau đó mã hóa lại audio hoặc ghép với video.

## Mục tiêu

- Render vị trí trước, sau, trái, phải, trên và dưới qua tai nghe.
- Không ép nhạc stereo về mono.
- Cho phép A/B nhiều chiến lược xử lý stereo thay vì coi một chiến lược là luôn đúng.
- Tự chuyển nguồn mono thành stereo trước khi đi vào renderer.
- Tách khoảng cách vật lý khỏi reverb.
- Hỗ trợ HRTF tích hợp và HRTF SOFA ở tầng kỹ thuật.
- Giữ loudness và sample peak trong miền an toàn mà không dùng compressor mặc định.
- Đo integrated loudness và true peak trên tệp đã mã hóa.

## Ba chế độ stereo

### Mid/Side

Đây là mặc định cho nhạc.

1. Tách Mid bằng `(L + R) / 2` và nhân thành stereo để đưa qua Steam Audio.
2. Spatialize Mid theo quỹ đạo đang chọn.
3. Giữ Side `(L - R) / 2` ngoài renderer.
4. Ghép Side trở lại sau khi Mid đã spatialize.

Mục tiêu của chế độ này là cho phần trung tâm của bản mix chuyển động nhưng vẫn giữ nhiều thông tin độ rộng gốc.

### Cùng vị trí

Đây là baseline tương thích với renderer trước đây. Hai kênh L/R vẫn khác dữ liệu nhưng cùng đi qua một `IPLBinauralEffect` với một hướng nguồn chung. Chế độ này hữu ích để A/B và xác định chính xác khác biệt do cách dựng sân khấu stereo.

### Hai nguồn L/R

Tách L và R thành hai đường riêng. Mỗi đường được render độc lập bằng Steam Audio, với phương vị lệch quanh tâm quỹ đạo theo mặc định `-15°` cho L và `+15°` cho R. Hai kết quả được cộng lại sau render.

Độ lệch object là tham số kỹ thuật `stereoObjectHalfAngleDeg`, mặc định 15° và được giới hạn 0–45°.

## Contract Cường độ 3D

`Cường độ 3D` là master blend của toàn bộ đường spatial.

- `0%`: hard bypass. PCM stereo đã decode được chép thẳng sang đầu ra render. Không chạy distance attenuation, air absorption, directivity, HRTF, reverb hoặc makeup gain.
- `1–100%`: renderer tạo một đường spatial ở cường độ đầy đủ, sau đó trộn với PCM gốc theo giá trị người dùng.

Nhờ đó 0% có ý nghĩa rõ ràng và không còn reverb/HRTF ẩn.

## Điều khiển giao diện

Giao diện thông thường gồm:

1. Chế độ stereo.
2. Kiểu chuyển động.
3. Chu kỳ.
4. Khoảng cách.
5. Cường độ 3D.
6. Độ vang.

Chu kỳ chạy trực tiếp từ 3 đến 30 giây. Khoảng cách thân thiện chạy từ 0,8 đến 20 mét.

## Kiến trúc xử lý

1. FFmpeg materialize và decode nguồn thành PCM stereo float 48 kHz.
2. Engine đo loudness đầu vào.
3. Tùy chế độ stereo:
   - Mid/Side: tạo Mid, render Mid, ghép Side lại.
   - Cùng vị trí: render stereo hiện tại làm baseline.
   - Hai nguồn L/R: render hai object riêng rồi cộng lại.
4. Master blend trộn PCM gốc và đường spatial theo `Cường độ 3D`.
5. Một sample-peak safety limiter ở khoảng -1 dBFS bảo vệ các bước ghép PCM trung gian.
6. FFmpeg mã hóa audio hoặc ghép với video.
7. FFmpeg `loudnorm` đo integrated LUFS, LRA và true peak trực tiếp từ log của phiên phân tích, kể cả release build.

## Hệ tọa độ

Renderer dùng hệ tọa độ Steam Audio:

- `+X`: bên phải người nghe.
- `+Y`: phía trên.
- `-Z`: phía trước.

Góc phương vị 0° ở phía trước, 90° ở bên phải, 180° ở phía sau và -90° ở bên trái.

## Mặc định âm nhạc

- Chế độ stereo: Mid/Side.
- Khoảng cách: 1,2 m.
- Khoảng cách không suy hao: 1,2 m.
- Distance rolloff: 0,65.
- Air absorption: 0,35.
- Cường độ 3D: 85%.
- Reverb Wet: 12%.
- Độ lệch Dual Object: ±15°.
- Sample-peak ceiling: khoảng -1 dBFS.

Lưu ý: sample-peak protection trong renderer không phải true-peak limiter có oversampling. True peak được đo sau mã hóa để diagnostics phản ánh đúng tệp người dùng nhận được.

## Loudness và peak

Steam Audio native vẫn đo RMS của nội dung chính và áp shared gain để tránh thay đổi loudness quá mạnh. Gain bù native giới hạn từ -6 dB đến +12 dB và một hệ số peak protection chung được áp cho hai tai.

Các bước ghép Mid/Side, Dual Object và master blend có sample-peak limiter riêng để tránh cộng tín hiệu vượt full scale. Sau mã hóa, `loudnorm` đo integrated LUFS và true peak. Nếu cần cam kết true-peak ceiling cứng cho codec mất dữ liệu, cần một true-peak limiter có oversampling ở thay đổi riêng.

## Diagnostics

Sự kiện chính:

- `spatial_render_start`
- `spatial_input_quality`
- `spatial_native_complete`
- `spatial_output_quality`
- `spatial_render_success`
- `spatial_render_failed`
- `spatial_render_cancelled`

Các trường quan trọng gồm:

- codec, channel layout, sample rate và thời lượng;
- `stereo_mode` và độ lệch object;
- hard bypass có được kích hoạt hay không;
- peak/RMS/correlation/balance/dual-mono từ native renderer;
- integrated LUFS, LRA và true peak trước/sau mã hóa;
- loudness delta và true-peak delta;
- gain bù, sample-peak protection;
- frame, block, tail frame và render time;
- PSS, native heap, Java heap và nhiệt độ pin;
- HRTF và phiên bản Steam Audio.

Diagnostics không ghi URI nguồn hoặc đường dẫn SOFA đầy đủ.

## Kiểm thử A/B bắt buộc

Dùng tai nghe và ít nhất các nguồn sau:

1. Nhạc stereo rộng.
2. Nguồn mono/dual-mono.
3. Tín hiệu có Side rất mạnh hoặc L/R gần ngược pha.
4. Impulse hoặc tiếng vỗ tay ngắn.
5. Pink noise hoặc giọng nói.

Với cùng một nguồn, thử lần lượt:

- Cường độ 0% để xác nhận bypass.
- Mid/Side.
- Cùng vị trí.
- Hai nguồn L/R.
- Wet 0 và Wet lớn hơn 0.
- Chu kỳ ngắn, vừa và dài.
- Khoảng cách gần, vừa và xa.
- Audio-only và video.

Nên so sánh loudness, true peak, correlation, left/right balance và cảm nhận độ rộng bằng cùng mức nghe.

## Tail và thời lượng

Audio-only hiện giữ tail của direct/HRTF và reverb khi hiệu ứng kéo tới cuối tệp, nên có thể dài hơn nguồn. Video dùng `-shortest`, vì vậy tail vượt quá hình sẽ bị cắt. Đây là chính sách hiện tại và cần được kiểm tra bằng A/B trước khi thay đổi hành vi xuất file.

## CI và quyền GitHub Actions

Build workflow chỉ cần `contents: read`. Logic đặc biệt dành cho PR Spatial Audio cũ đã được bỏ. Workflow xóa toàn bộ Actions artifacts/caches/runs là thao tác phá hủy dữ liệu và chỉ được phép chạy thủ công bằng `workflow_dispatch`.

## Quy tắc hợp nhất

Không hợp nhất chỉ vì build xanh. Cần xác nhận trên thiết bị rằng:

- 0% thực sự bypass;
- không click ở seam hoặc mép phạm vi hiệu ứng;
- không clipping sau các bước ghép;
- ba chế độ stereo tạo khác biệt có thể nghe và đo được;
- Mid/Side không làm co sân khấu bất thường;
- Dual Object không tạo cảm giác hai nguồn tách rời quá mức;
- loudness đầu ra hợp lý và true peak sau mã hóa được ghi đầy đủ;
- video giữ đồng bộ hình tiếng;
- RAM, nhiệt và realtime factor phù hợp thiết bị mục tiêu.

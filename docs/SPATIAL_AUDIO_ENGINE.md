# Spatial Audio Engine

Nhánh `agent/spatial-audio-engine` thay thế hoàn toàn đường xử lý Nhạc 8D cũ dựa trên `apulsator + aecho` bằng renderer binaural object-based.

## Mục tiêu

- Render vị trí trước, sau, trái, phải, trên và dưới qua tai nghe.
- Hỗ trợ quỹ đạo 360 độ ngang, vòng dọc, hình số 8, tuyến tính và vị trí tĩnh.
- Tách khoảng cách vật lý khỏi reverb.
- Hỗ trợ HRTF tích hợp và HRTF cá nhân ở định dạng SOFA.
- Cho phép người dùng điều chỉnh toàn bộ tham số có tác dụng thật.
- Ghi cấu hình, timing và metrics chất lượng vào diagnostics.
- Không thay đổi `main` cho tới khi CI và kiểm thử thiết bị đạt.

## Kiến trúc

1. FFmpeg giải mã nguồn về PCM float mono, 48 kHz.
2. `SpatialAudioEngine` truyền PCM và `SpatialAudioConfig` sang JNI.
3. `mediatool_spatial` dùng Steam Audio để:
   - áp distance attenuation;
   - áp air absorption ba dải;
   - áp source directivity;
   - render HRTF binaural với nội suy bilinear hoặc nearest;
   - thêm parametric reverb ba dải khi Wet lớn hơn 0;
   - crossfade ở mép phạm vi hiệu ứng;
   - áp shared gain nếu peak vượt trần.
4. FFmpeg mã hóa PCM stereo hoặc ghép lại với video bằng stream-copy.

## Hệ tọa độ

Renderer dùng hệ tọa độ Steam Audio:

- `+X`: bên phải người nghe.
- `+Y`: phía trên.
- `-Z`: phía trước.

Góc phương vị 0 độ ở phía trước, 90 độ ở bên phải, 180 độ ở phía sau và -90 độ ở bên trái.

## Tham số người dùng

### Quỹ đạo

- Kiểu quỹ đạo.
- Lặp hoặc chạy một lần.
- Chu kỳ.
- Góc ngang bắt đầu và kết thúc.
- Độ cao bắt đầu và kết thúc.
- Khoảng cách bắt đầu và kết thúc.

### HRTF

- Nội suy bilinear hoặc điểm gần nhất.
- Mức binaural.
- HRTF mặc định của Steam Audio.
- Tệp SOFA cá nhân.

### Âm học trực tiếp

- Khoảng cách không suy hao.
- Độ dốc suy hao.
- Mức hấp thụ không khí.
- Trọng số và độ tập trung hướng phát.
- Hướng quay của nguồn.

### Không gian phản xạ

- Wet 0 đến 100 phần trăm.
- RT60 thấp, trung và cao.
- EQ reverb thấp, trung và cao.

Wet bằng 0 không tạo hoặc chạy reflection effect. Renderer vẫn xả tail ngắn của direct/HRTF khi hiệu ứng kéo tới cuối tệp; khi Wet lớn hơn 0, reflection và wet-binaural tail cũng được xả đầy đủ.

### Phạm vi và đầu ra

- Thời điểm bắt đầu và kết thúc.
- Gain đầu ra.
- Block DSP 256 đến 4096 mẫu.

## Diagnostics

Sự kiện chính:

- `spatial_render_start`
- `spatial_native_complete`
- `spatial_render_success`
- `spatial_render_failed`
- `spatial_render_cancelled`

Các trường quan trọng:

- toàn bộ cấu hình quỹ đạo và âm học;
- HRTF tích hợp hoặc SOFA;
- số frame và block;
- số tail frame được xả sau khi nguồn kết thúc;
- thời gian render và realtime factor;
- peak trước và sau shared gain;
- RMS dBFS;
- gain bảo vệ đã áp;
- số mẫu không hữu hạn;
- số mẫu vượt full-scale trước gain;
- phiên bản Steam Audio;
- kích thước tệp đầu ra.

Diagnostics không ghi đường dẫn nguồn hoặc đường dẫn SOFA đầy đủ.

## Kiểm thử bắt buộc

### CI

- Xác minh cấu trúc dự án.
- Biên dịch CMake/NDK cho `arm64-v8a`.
- Kiểm tra APK chứa `libphonon.so` và `libmediatool_spatial.so`.
- Android lint.
- Unit test Kotlin.
- Xác minh chữ ký APK.

### Thiết bị

Dùng tai nghe và ít nhất ba loại nguồn:

1. Impulse hoặc tiếng vỗ tay ngắn để phát hiện click và reverb ngoài ý muốn.
2. Pink noise hoặc giọng nói để kiểm tra định vị trước/sau/trên/dưới.
3. Nhạc phổ rộng để kiểm tra chuyển động dài, âm sắc và fatigue.

Cần A/B ít nhất:

- HRTF mặc định và một SOFA hợp lệ.
- Bilinear và nearest.
- Wet 0 và Wet lớn hơn 0.
- Vòng ngang, vòng dọc và tuyến tính một lần.
- Block 512, 1024 và 2048.

## Giới hạn được chấp nhận

- Đầu ra binaural được thiết kế cho tai nghe.
- Độ chính xác trước/sau và trên/dưới phụ thuộc mức phù hợp giữa HRTF và tai người nghe.
- Tệp stereo đã render không thể phản ứng với head tracking sau khi xuất.
- Phiên bản đầu chưa mô phỏng phòng bằng hình học hoặc ray tracing. Reverb là parametric ba dải, độc lập với vị trí trực tiếp.
- Nguồn được thu về mono trước khi trở thành một object. Đây là chủ ý để vị trí object không xung đột với stereo image có sẵn.

## Quy tắc hợp nhất

Không hợp nhất vào `main` chỉ vì build thành công. Cần có diagnostics thiết bị, mẫu A/B và xác nhận rằng:

- không click tại thay đổi HRTF hoặc mép thời gian;
- không clipping sau shared gain;
- Wet 0 hoàn toàn khô;
- quỹ đạo lặp không tạo seam nghe thấy;
- video giữ đúng đồng bộ hình và tiếng;
- RAM, nhiệt và realtime factor phù hợp thiết bị mục tiêu.

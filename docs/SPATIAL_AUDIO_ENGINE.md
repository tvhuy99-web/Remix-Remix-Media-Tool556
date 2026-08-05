# Spatial Audio Engine

Nhánh `agent/spatial-audio-engine` thay thế hoàn toàn đường xử lý Nhạc 8D cũ dựa trên `apulsator + aecho` bằng renderer binaural object-based dùng Steam Audio.

## Mục tiêu

- Render vị trí trước, sau, trái, phải, trên và dưới qua tai nghe.
- Giữ ảnh stereo gốc của nhạc thay vì ép toàn bộ nguồn về mono.
- Tự chuyển nguồn mono thành stereo trước khi đi vào renderer.
- Hỗ trợ quỹ đạo vòng quanh đầu, vòng dọc, hình số 8 và tuyến tính.
- Tách khoảng cách vật lý khỏi reverb.
- Hỗ trợ HRTF tích hợp và HRTF cá nhân ở định dạng SOFA ở tầng kỹ thuật.
- Cân lại loudness tự động mà không dùng compressor để làm phẳng động học.
- Ghi cấu hình, timing, loudness, stereo và tài nguyên thiết bị vào diagnostics.
- Không thay đổi `main` cho tới khi CI và kiểm thử thiết bị đạt.

## Kiến trúc

1. FFmpeg giải mã nguồn về PCM float stereo, 48 kHz.
   - Nguồn stereo giữ nguyên hai kênh trái và phải.
   - Nguồn mono được FFmpeg nhân đôi thành stereo.
2. `SpatialAudioEngine` đo metadata và loudness đầu vào, sau đó truyền PCM stereo cùng `SpatialAudioConfig` sang JNI.
3. `mediatool_spatial` dùng Steam Audio để:
   - áp distance attenuation trên hai kênh;
   - áp air absorption ba dải;
   - áp source directivity;
   - render HRTF binaural với đầu vào stereo;
   - trộn hai kênh thành một đường mono riêng chỉ để cấp cho parametric reverb;
   - crossfade ở mép phạm vi hiệu ứng;
   - bù RMS tự động theo đầu vào, giới hạn từ -6 dB đến +12 dB;
   - áp một peak ceiling chung ở -1 dBFS cho cả hai tai.
4. FFmpeg mã hóa PCM stereo hoặc ghép lại với video bằng stream-copy.
5. FFmpeg đo integrated loudness và true peak của tệp đã mã hóa để diagnostics phản ánh đúng đầu ra người dùng nhận được.

## Hệ tọa độ

Renderer dùng hệ tọa độ Steam Audio:

- `+X`: bên phải người nghe.
- `+Y`: phía trên.
- `-Z`: phía trước.

Góc phương vị 0 độ ở phía trước, 90 độ ở bên phải, 180 độ ở phía sau và -90 độ ở bên trái.

## Giao diện người dùng thông thường

Giao diện mặc định chỉ có năm điều khiển:

1. Kiểu chuyển động.
2. Tốc độ.
3. Khoảng cách.
4. Cường độ 3D.
5. Độ vang.

Các lựa chọn này ánh xạ vào bộ tham số kỹ thuật đã giới hạn trong miền phù hợp cho nhạc. Cấu hình nghiên cứu cũ không được mang sang phiên bản giao diện đơn giản, tránh giữ lại các giá trị cực đoan như nguồn cách hơn 10 mét.

Các tùy chỉnh chuyên sâu như góc chính xác, directivity, SOFA, RT60 ba dải, block DSP và phạm vi thời gian vẫn nằm trong lõi nhưng chưa xuất hiện trên giao diện thông thường. Chúng sẽ được xem xét sau khi cấu hình mặc định đã ổn định bằng dữ liệu A/B.

## Mặc định âm nhạc

- Khoảng cách: 1,2 mét.
- Khoảng cách không suy hao: 1,2 mét.
- Distance rolloff: 0,65.
- Air absorption: 0,35.
- Cường độ binaural: 85 phần trăm.
- Reverb Wet: 12 phần trăm.
- Peak ceiling: -1 dBFS.

Thanh khoảng cách thông thường giới hạn từ 0,8 đến 4 mét. Thanh tốc độ ánh xạ chu kỳ từ 18 giây xuống 3 giây.

Wet bằng 0 không tạo hoặc chạy reflection effect. Renderer vẫn xả tail của direct/HRTF khi hiệu ứng kéo tới cuối tệp; khi Wet lớn hơn 0, reflection và wet-binaural tail cũng được xả đầy đủ.

## Bảo toàn loudness

Renderer đo RMS của stereo đầu vào và RMS phần nội dung chính sau spatial processing, không tính tail reverb vào mục tiêu cân bằng. Gain bù tự động được giới hạn từ -6 dB đến +12 dB. Gain thủ công nội bộ, nếu có, được cộng sau đó.

Sau khi xác định gain mong muốn, renderer dùng cùng một hệ số peak protection cho cả hai tai để giữ ảnh stereo và giới hạn peak ở -1 dBFS. Không hard-clip từng kênh và không dùng compressor mặc định.

Diagnostics cuối cùng còn đo integrated LUFS và true peak trên tệp đã mã hóa, vì codec mất dữ liệu có thể thay đổi peak so với PCM.

## Diagnostics

Sự kiện chính:

- `spatial_render_start`
- `spatial_input_quality`
- `spatial_native_complete`
- `spatial_output_quality`
- `spatial_render_success`
- `spatial_render_failed`
- `spatial_render_cancelled`

Các trường quan trọng:

- codec, số kênh, channel layout, sample rate và thời lượng nguồn;
- số kênh decode và đầu ra;
- loại stereo processing: giữ stereo hoặc upmix mono;
- peak và RMS đầu vào tổng thể, trái và phải;
- độ tương quan stereo, cân bằng trái-phải và RMS phần chênh lệch;
- nhận diện dual-mono;
- peak và RMS đầu ra tổng thể, trái và phải;
- RMS phần nội dung chính trước và sau gain;
- gain bù loudness, gain peak protection và tổng gain;
- integrated LUFS, loudness range và true peak trước/sau mã hóa;
- loudness delta và true-peak delta;
- toàn bộ cấu hình quỹ đạo và âm học;
- HRTF tích hợp hoặc SOFA;
- số frame, block và tail frame;
- thời gian render và realtime factor;
- số mẫu không hữu hạn;
- số mẫu vượt full-scale trước gain;
- PSS, native heap, Java heap và nhiệt độ pin trước/sau render;
- phiên bản Steam Audio và kích thước tệp đầu ra.

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

Dùng tai nghe và ít nhất bốn loại nguồn:

1. Nhạc stereo rộng để xác nhận không mất side information.
2. Nguồn mono để xác nhận upmix stereo và dual-mono detection.
3. Impulse hoặc tiếng vỗ tay ngắn để phát hiện click và reverb ngoài ý muốn.
4. Pink noise hoặc giọng nói để kiểm tra định vị trước/sau/trên/dưới.

Cần A/B ít nhất:

- nguồn gốc và Spatial Audio với cùng mức nghe;
- Wet 0 và Wet lớn hơn 0;
- vòng ngang, vòng dọc, hình số 8 và tuyến tính;
- mức khoảng cách gần, vừa và xa;
- mức cường độ 3D nhẹ, cân bằng và rõ;
- integrated loudness delta và true peak sau mã hóa;
- stereo correlation, left/right balance và dual-mono detection;
- audio-only và video giữ hình.

## Giới hạn được chấp nhận

- Đầu ra binaural được thiết kế cho tai nghe.
- Độ chính xác trước/sau và trên/dưới phụ thuộc mức phù hợp giữa HRTF và tai người nghe.
- Tệp stereo đã render không thể phản ứng với head tracking sau khi xuất.
- Parametric reverb dùng một nguồn mono tổng hợp từ stereo direct path, nhưng đường âm thanh trực tiếp vẫn giữ stereo xuyên suốt.
- Phiên bản hiện tại chưa mô phỏng phòng bằng hình học hoặc ray tracing.

## Quy tắc hợp nhất

Không hợp nhất vào `main` chỉ vì build thành công. Cần có diagnostics thiết bị, mẫu A/B và xác nhận rằng:

- không click tại thay đổi HRTF hoặc mép thời gian;
- không clipping sau peak protection;
- Wet 0 hoàn toàn khô;
- ảnh stereo không bị thu hẹp bất thường;
- loudness đầu ra gần đầu vào trong giới hạn nghe hợp lý;
- quỹ đạo lặp không tạo seam nghe thấy;
- video giữ đúng đồng bộ hình và tiếng;
- RAM, nhiệt và realtime factor phù hợp thiết bị mục tiêu.

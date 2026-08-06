# Spatial Audio roadmap

Tài liệu này chia việc nâng cấp Spatial Audio thành các lát cắt có thể build, đo và nghe A/B độc lập. Không gộp toàn bộ thay đổi vào một PR vì lỗi trong reflection simulator, convolution hoặc stem routing có thể làm khó xác định nguyên nhân.

## Giai đoạn 1: Room-aware Spatial Audio

### 1A. Mô hình phòng và giao diện thân thiện

Trạng thái: mã nguồn và CI đã hoàn tất trong draft PR #28; còn bài nghe A/B trên thiết bị.

- Sáu preset: Không gian khô, Studio, Phòng nghe nhạc, Nhà hát, Nhà kho và Ngoài trời.
- Mỗi phòng kín có kích thước, diện tích bề mặt, hấp thụ ba dải và scattering.
- RT60 được ước tính bằng công thức Sabine rồi giới hạn trong miền an toàn cho renderer.
- Thanh `Phản xạ phòng` ánh xạ phi tuyến vào wet kỹ thuật và không bao giờ cho wet-only.
- Preset cập nhật đồng bộ RT60, EQ, distance rolloff và air absorption.
- Diagnostics lưu preset, hình học, thể tích, scattering, thời gian phản xạ đầu và phiên bản mô hình.
- Migration giữ ý định của cấu hình cũ nhưng chặn giá trị reverb cực đoan.

Đây là nền dữ liệu cho scene hình học. 1A chưa tuyên bố rằng phản xạ đã được ray trace.

### 1B. Steam Audio scene và phản xạ động

Trạng thái: mã nguồn và CI đã hoàn tất trong draft PR #29; còn runtime/A-B trên OnePlus.

- Dựng mesh hình hộp từ kích thước preset, với vật liệu riêng cho tường, sàn và trần.
- Tạo `IPLScene`, `IPLStaticMesh`, `IPLSimulator` và một source phản xạ.
- Chạy reflections theo keyframe của quỹ đạo thay vì ở mọi block âm thanh.
- Dùng Hybrid Reflection Effect: convolution cho early reflections, parametric cho late tail.
- Giải mã IR Ambisonics sang binaural trước khi trộn với direct path.
- Dùng Mid của stereo gốc làm reflection send để không suy giảm direct path hai lần.
- Cường độ là outer effect mix thật; 0% bỏ qua direct spatialization và room reflections.
- Giới hạn automatic makeup còn +3 dB cho nguồn xa và quỹ đạo độ sâu.
- Ngoài trời không tạo mesh bao quanh và dùng parametric fallback tối thiểu.
- Diagnostics ghi reflection mode, số lần cập nhật, thời gian simulation, source clamp và cấu hình chất lượng.

Điều kiện hoàn thành:

- cùng một khoảng cách nghe khác nhau hợp lý giữa Studio, Nhà hát và Ngoài trời;
- quỹ đạo không có zipper noise khi cập nhật mô phỏng;
- tail không làm sai thời lượng video;
- benchmark OnePlus đạt realtime factor phù hợp và không vượt guard RAM/nhiệt;
- intensity 0% khớp PCM đầu vào trong sai số số học cho phép.

### 1C. Bảo toàn stereo và độ tin cậy production

Trạng thái: 1C.1 đang được xác minh trong draft PR #30.

Đã triển khai trong 1C.1:

- ID Kotlin/JNI ổn định, không phụ thuộc thứ tự enum.
- Preflight dung lượng cho hai pass PCM, tail và safety margin.
- Mid/Side post-process theo luồng, giữ Mid binaural và phục hồi tối đa 40% Side nguồn.
- Giảm độ rộng phục hồi theo khoảng cách và bỏ qua nguồn dual-mono.
- Pass peak chỉ-giảm-gain với trần -1 dBFS.
- Diagnostics cho disk guard và stereo post-process.

Còn lại trong 1C:

- Vô hiệu hóa Pan, AutoPan, reverb cũ và mono khi Spatial Audio đang bật.
- Native cancellation.
- Sửa parser LUFS/true peak và thêm native/Kotlin trajectory parity tests.
- Quy định tail riêng cho audio và video.
- Runtime A/B point stereo, Mid/Side và sau đó mới cân nhắc cặp nguồn L/R.

## Giai đoạn 2: High Quality Offline

- Preset chất lượng Cân bằng và Cao.
- Ambisonics bậc 1 cho Cân bằng, bậc 2 cho Cao.
- Tăng rays, bounces và IR duration theo năng lực thiết bị.
- Bake probe cho các phòng preset và nội suy dữ liệu theo vị trí nguồn.
- Cache IR/keyframe để tránh tính lại khi chỉ đổi codec đầu ra.
- HRTF calibration và lưu profile theo tai nghe.

Mọi tăng chất lượng phải đi kèm benchmark CPU, RAM, nhiệt, dung lượng tạm và kiểm tra seam. Không mở mặc định chế độ Cao trước khi có số đo từ renderer Cân bằng trên thiết bị thật.

## Giai đoạn 3: Object Music

- Mid/Side là đường mặc định cho nhạc stereo hoàn chỉnh.
- Chế độ nâng cao dùng stem: vocal, drums, bass, instruments và ambience.
- Mỗi stem có quỹ đạo, độ rộng và reflection send riêng.
- Vocal giữ định vị rõ; bass hạn chế chuyển động; ambience đi vào trường Ambisonics.
- Khi tách stem không đủ tốt, tự động quay về Mid/Side thay vì tạo artifact.

## Nguyên tắc chất lượng

1. Không gọi số mét là phép đo của căn phòng thật khi nguồn chỉ là một file stereo.
2. Tất cả tín hiệu khoảng cách phải nhất quán: direct gain, DRR, air absorption và early reflection timing.
3. Không dùng makeup gain để xóa chênh lệch gần/xa.
4. Không để một slider người dùng tạo wet-only hoặc peak không an toàn.
5. Không tăng rays hay Ambisonics order trước khi có số đo thiết bị và bài nghe A/B.

## Production tuning v4

- Phản xạ phòng dùng đường cong điều khiển 1.15 và trần wet theo từng preset.
- Wet bus được lọc 7.2 kHz, giảm high-band và dùng gate attack/release để loại tiếng xì, pumping.
- Parametric fallback dùng cặp HRTF khuếch tán cố định thay vì bám theo hướng nguồn.
- Quỹ đạo trước/sau chạy ngang ở elevation 0°, với rear notch, low-pass, attenuation và wet boost rõ hơn.
- Diagnostics ghi commit, branch, renderer version và gain phản xạ hiệu dụng.

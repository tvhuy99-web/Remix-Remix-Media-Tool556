# Nhật ký chẩn đoán MediaTool 1.3.1

## Mục tiêu

Người dùng chỉ cần tạo và gửi một gói ZIP để người bảo trì biết lỗi xảy ra ở tác vụ, phase, model/provider, lệnh FFmpeg hoặc chunk ONNX nào. Hệ thống ưu tiên ba nguyên tắc: không chặn UI/pipeline, đủ dữ kiện tương quan và không thu thập nội dung media.

## Cách xuất

- Mở **Cài đặt > Nhật ký chẩn đoán > Tạo gói nhật ký**; hoặc dùng thẻ cùng tên xuất hiện ngay trên màn hình Tách stem sau lỗi.
- Chọn **Gửi ZIP** để chia sẻ trực tiếp, hoặc **Lưu ZIP** rồi đính kèm thủ công.
- Gửi nguyên ZIP cùng mô tả thao tác ngay trước lỗi. Không cần chép từng dòng log.

Gói gồm:

- `summary.json`: phiên bản app, Android/ABI, RAM/heap, dung lượng, cấu hình stem, model hiện có, trạng thái tác vụ và lịch sử lý do process thoát gần đây trên Android 11+.
- `logs/events-*.jsonl`: sự kiện có thứ tự thời gian.
- `README.txt`: mô tả nội dung và bảo vệ riêng tư.

## Schema JSONL

Mỗi dòng là một JSON độc lập:

```json
{
  "schema": 1,
  "seq": 42,
  "ts_utc": "2026-08-01T12:34:56.789Z",
  "elapsed_ms": 123456,
  "level": "ERROR",
  "component": "AudioSeparator",
  "event": "ffmpeg_failed",
  "session": "task-uuid",
  "message": "Mô tả đã che dữ liệu",
  "fields": {
    "phase": "encode_vocals",
    "command_id": "mã băm một chiều",
    "return_code": "1"
  },
  "exception_class": "java.io.IOException",
  "stack_trace": "Stack trace đã che dữ liệu"
}
```

`session` nối toàn bộ sự kiện của một tác vụ. `source_id` và `command_id` chỉ là SHA-256 rút gọn để biết hai sự kiện có cùng nguồn/lệnh; không thể dùng chúng để đọc lại URI hoặc command.

## Sự kiện quan trọng

| Component | Sự kiện | Ý nghĩa |
| --- | --- | --- |
| `Application` | `process_start`, `uncaught_exception` | Mở process và crash Java/Kotlin chưa bắt |
| `ModelDownloader` | `download_preflight_ok`, `response_opened`, `sha256_validated`, `download_failed` | Mạng, resume, kích thước và hash model |
| `StemService` | `preflight_snapshot`, `model_validated`, `task_failed` | Duration, RAM/storage, model và trạng thái tác vụ |
| `AudioSeparator` | `onnx_session_opened`, `tensor_contract_validated`, `inference_chunk_*` | Provider thực tế, shape tensor, thời gian/heap từng chunk |
| `AudioSeparator` | `ffmpeg_start`, `ffmpeg_failed`, `pipeline_cleanup` | Phase decode/encode, return code, log tail đã che và cleanup |
| `MediaEngine` | `ffmpeg_*`, `saf_open_failed` | Các công cụ cắt/nối/trộn/đổi định dạng |
| `RecordingManager` | `recording_*` | Khởi tạo, pause/resume, chốt WAV và lỗi cuối của recorder |
| `SubViewModel` | `tts_*` | Init/ngôn ngữ, queue, callback cũ bị bỏ qua và mã lỗi TTS |
| `DiagnosticReport` | `report_requested`, `report_created`, `report_failed` | Trạng thái tạo gói chẩn đoán |

## Giới hạn và lưu giữ

- Log nằm trong vùng riêng của ứng dụng, xoay ở khoảng 2 MiB/tệp, giữ tối đa 5 tệp cũ và 7 ngày.
- Ghi log dùng một worker riêng và hàng đợi tối đa 512 sự kiện; khi quá tải, app bỏ sự kiện cũ thay vì chặn tác vụ media. `dropped_events` trong summary cho biết có mất sự kiện hay không.
- Crash Java/Kotlin được ghi đồng bộ rồi chuyển tiếp cho crash handler hệ thống.
- Native crash/ANR có thể không tạo stack Java. Trên Android 11+, `recent_process_exits` vẫn ghi lý do `CRASH_NATIVE`, `ANR`, `LOW_MEMORY`…; các log ngay trước lần thoát giúp xác định phase cuối.

## Bảo vệ riêng tư

Gói không chứa audio, video, ảnh, subtitle, model hoặc FFmpeg command nguyên bản. Trước khi ghi, hệ thống che:

- `content://`, `file://`, URL và đường dẫn tuyệt đối;
- tên tệp media, email, token/API key/signature;
- title, artist, album, comment, lyrics và metadata tương tự trong log FFmpeg;
- mọi URI nguồn được thay bằng mã băm tương quan một chiều.

Không thêm Android ID, serial, tài khoản, SSID hoặc dữ liệu media vào summary.

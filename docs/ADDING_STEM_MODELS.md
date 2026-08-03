# Thêm model stem

Model mới phải có:

- ID ổn định.
- URL ghim revision.
- Dung lượng và SHA-256.
- Sample rate, tensor shape và source mapping.
- Chunk, overlap và chuẩn hóa.
- Yêu cầu RAM.
- Giấy phép.
- CPU fallback.

Cần thêm descriptor vào `StemModelRegistry`, test contract, build APK và benchmark trên thiết bị thật trước khi đưa vào danh sách.

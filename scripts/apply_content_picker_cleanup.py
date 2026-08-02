#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Không tìm thấy mẫu bắt buộc: {label}")
    return text.replace(old, new)


def update(path: Path, replacements: list[tuple[str, str, str]]) -> None:
    text = path.read_text(encoding="utf-8")
    for old, new, label in replacements:
        text = replace_required(text, old, new, label)
    path.write_text(text, encoding="utf-8")


other = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/screens/OtherScreen.kt"
update(other, [
    ("import androidx.activity.result.contract.ActivityResultContracts\n", "", "OtherScreen import cũ"),
    ("import com.aistudio.mediatool.core.DocumentUtils\n", "import com.aistudio.mediatool.core.GetContentWithMimeTypes\nimport com.aistudio.mediatool.core.DocumentUtils\n", "OtherScreen import picker"),
    ("ActivityResultContracts.OpenDocument()", "GetContentWithMimeTypes()", "OtherScreen launcher"),
    ('                Text("Nhập các mốc thời gian (giây) để cắt ảnh. Cách nhau bằng dấu phẩy.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)\n', "", "ghi chú mốc ảnh"),
    ('                Text("Ảnh sẽ được đóng thành một tệp ZIP để bạn lưu hoặc chia sẻ.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)\n', "", "ghi chú ZIP ảnh"),
    ("Bật chuẩn hóa loudness (EBU R128)", "Chuẩn hóa âm lượng", "nhãn normalize"),
    ("Bật Lọc nhiễu (Denoise)", "Lọc nhiễu", "nhãn denoise"),
    ("Tự động cắt khoảng lặng (Silence Remove)", "Cắt khoảng lặng", "nhãn silence"),
    ("Bật Noise Gate (Cổng triệt ồn)", "Noise Gate", "nhãn gate"),
    ("Bật Thay đổi Tốc độ & Độ cao", "Tốc độ và cao độ", "nhãn speed"),
    ("Bật Pan tĩnh", "Pan", "nhãn pan"),
    ("Bật Auto Pan (Hiệu ứng đảo tai)", "Auto Pan", "nhãn autopan"),
    ("Bật tiếng vang (Echo)", "Echo", "nhãn echo"),
    ("Bật Reverb (Vang phòng thu)", "Reverb", "nhãn reverb"),
    ("Bật Compressor (Nén âm lượng)", "Compressor", "nhãn compressor"),
    ("Chế độ Limiter (chặn cứng)", "Limiter", "nhãn limiter"),
    ("Bật EQ (Equalizer 5 dải tần)", "EQ", "nhãn EQ"),
    ("✂️ Tạo mẫu 10s & Nghe thử", "Nghe thử 10 giây", "nhãn preview"),
    ("Không tạo được ảnh. Hãy kiểm tra các mốc thời gian có nằm trong video hay không", "Mốc thời gian không hợp lệ", "lỗi mốc ảnh"),
    ("Đã đóng gói ${images.size} ảnh thành ZIP. Chọn Lưu hoặc Chia sẻ bên dưới.", "Đã tạo ZIP ${images.size} ảnh", "trạng thái ZIP"),
])

mix = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/screens/MixScreen.kt"
update(mix, [
    ("import androidx.activity.result.contract.ActivityResultContracts\n", "", "MixScreen import cũ"),
    ("import com.aistudio.mediatool.core.DocumentUtils\n", "import com.aistudio.mediatool.core.GetContentWithMimeTypes\nimport com.aistudio.mediatool.core.GetMultipleContentsWithMimeTypes\nimport com.aistudio.mediatool.core.DocumentUtils\n", "MixScreen imports picker"),
    ("ActivityResultContracts.OpenDocument()", "GetContentWithMimeTypes()", "MixScreen picker đơn"),
    ("ActivityResultContracts.OpenMultipleDocuments()", "GetMultipleContentsWithMimeTypes()", "MixScreen picker nhiều"),
    ("Bật cân bằng kênh tĩnh cho từng đoạn", "Cân bằng kênh theo đoạn", "nhãn cân bằng"),
    ("Cân bằng kênh đoạn gốc (các giá trị cách nhau bằng dấu phẩy)", "Cân bằng kênh gốc", "nhãn kênh gốc"),
    ("Kênh này đang bị khóa âm lượng do tắt tiếng ở màn hình ngoài.", "Kênh đang tắt tiếng.", "trạng thái kênh"),
])

(ROOT / "scripts/apply_content_picker_cleanup.py").unlink()
(ROOT / ".github/workflows/apply-content-picker-cleanup.yml").unlink()
print("UI CLEANUP OK")

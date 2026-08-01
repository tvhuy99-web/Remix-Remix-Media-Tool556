# Third-party notices

Dự án dùng các thành phần bên thứ ba được tải từ Maven hoặc tải theo yêu cầu khi chạy:

- AndroidX và Jetpack Compose, theo giấy phép Apache License 2.0.
- Kotlin và kotlinx.coroutines, theo giấy phép Apache License 2.0.
- OkHttp, theo giấy phép Apache License 2.0.
- ONNX Runtime, theo giấy phép MIT.
- FFmpegKit maintained và FFmpeg cùng các thư viện codec đi kèm. Điều khoản cuối cùng phụ thuộc package FFmpegKit và codec được phân phối; hãy rà soát LGPL/GPL và yêu cầu notice trước khi phát hành thương mại.
- Smart Exception Java/Common 0.2.1 của Arthenica, theo giấy phép BSD 3-Clause; đây là dependency runtime của FFmpegKit.
- Model `jackjiangxinfa/demucs-onnx`, metadata nguồn khai báo Apache License 2.0. Model được tải khi người dùng chọn tính năng AI và không nằm trong kho mã nguồn này.
- Bản chuyển đổi `smank/mel-band-roformer-vocals-onnx`, giấy phép MIT, được tải theo yêu cầu và không nằm trong APK/kho nguồn. Bản chuyển đổi dẫn xuất từ checkpoint vocals của KimberleyJensen (MIT), mã Mel-Band RoFormer của ZFTurbo/lucidrains (MIT) và STFT/iSTFT dạng convolution từ fork Demucs của Mixxx (MIT). Nguồn và attribution đầy đủ: https://huggingface.co/smank/mel-band-roformer-vocals-onnx

Tệp này là bản tóm tắt kỹ thuật, không phải tư vấn pháp lý. Khi phân phối APK công khai, giữ notice bắt buộc, cung cấp giấy phép tương ứng và xác minh nghĩa vụ của toàn bộ codec native trong APK thực tế.

## Kiến trúc

Thanh Phản xạ phòng điều khiển room presence. Presence được chia thành cụm phản xạ sớm sạch và đuôi late reflection ray-traced đã lọc. Cách này tăng cảm giác kích thước phòng mà không phải khuếch đại trực tiếp lớp noise Monte-Carlo.

Front/back giữ quỹ đạo trên mặt phẳng ngang, tăng rear spectral shadow và rear wet ratio để giảm nhầm lẫn trước sau với HRTF mặc định.

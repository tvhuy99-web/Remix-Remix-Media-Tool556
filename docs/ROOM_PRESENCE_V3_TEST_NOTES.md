# Room presence và front/back v3

Bản thử này tách cảm giác hiện diện của phòng thành hai lớp:

- cụm phản xạ sớm xác định, có độ trễ và độ rộng theo preset phòng;
- đuôi ray-traced tối hơn và nhẹ hơn để tránh lớp xì cao tần.

Mục tiêu kiểm thử trên OnePlus PJF110:

1. Với phản xạ phòng 0%, không có tiếng xì và chuyển động trái/phải giữ nguyên chất lượng PR #33.
2. Tại 25%, 50%, 75% và 100%, độ hiện diện phòng phải tăng rõ theo từng nấc.
3. 100% phải nghe rõ kích thước phòng nhưng không tạo noise nền cố định.
4. Quỹ đạo Trước ra sau phải nằm ngang, không vòng chếch qua phía trên đầu.
5. Khi nguồn ở phía sau, độ tối phổ và tỷ lệ wet tăng rõ nhưng không làm mất lời.

Các marker diagnostics mới:

- `early_reflection_max_gain`
- `early_reflection_first_ms`
- `room_presence_control`
- `rear_direct_scale_min`
- `rear_wet_scale_max`

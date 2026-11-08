# VR Browser cho Android (không cần máy tính)

App duyệt web chạy thẳng trên điện thoại Android, chia đôi màn hình cho kính VR lắp điện thoại.

## Cài đặt
1. Chép `VR-Browser.apk` vào điện thoại (Zalo, Google Drive, cáp USB… đều được) rồi mở file.
2. Nếu máy hỏi "Cài ứng dụng không rõ nguồn gốc": vào **Cài đặt** và bật **Cho phép từ nguồn này**.
   Play Protect có thể cảnh báo vì app không có trên CH Play: chọn **Vẫn cài đặt**.
3. Mở app **VR Browser**, điện thoại tự xoay ngang. Lắp vào kính.

Yêu cầu: Android 5.0 trở lên, máy có con quay hồi chuyển (gyroscope).

## Điều khiển
| Muốn làm | Cách làm |
|---|---|
| Trỏ | Quay đầu: chấm tròn giữa tầm nhìn là con trỏ |
| Bấm link / nút | Chạm màn hình, nút bấm trên kính, hoặc nút chính của remote Bluetooth |
| Cuộn trang | Trỏ vào ▲ ▼ hai bên rồi chạm (giữ để cuộn liên tục), hoặc nút âm lượng / phím lên-xuống của remote |
| Nhập địa chỉ | Trỏ vào thanh địa chỉ phía trên rồi chạm, bàn phím ảo hiện ra, trỏ vào phím rồi chạm để gõ, xong chạm **Enter** |
| Gõ chữ vào ô trên trang | Bấm vào ô đó, rồi bấm nút **Bàn phím** |
| Lùi / Tiến / Tải lại / Trang chủ | Các nút dưới màn hình (nút Back của máy cũng là Lùi) |
| Màn hình bị lệch | Quay mặt về hướng muốn đặt, bấm **Căn giữa** (hoặc giữ ngón tay trên màn hình 1 giây) |

App nhớ trang cuối cùng bạn xem, lần sau mở lại đúng trang đó. Âm thanh phát luôn trên điện thoại.

## Tự dựng lại APK (Ubuntu/Debian)
```bash
sudo apt install aapt apksigner android-sdk-platform-23 dalvik-exchange zipalign default-jdk
./build.sh
```
Mã nguồn chính: `src/vn/vrbrowser/MainActivity.java`.

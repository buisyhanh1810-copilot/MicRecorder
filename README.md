# Mic Recorder / Ghi Âm (Android)

Ứng dụng ghi âm từ micro, build APK tự động bằng GitHub Actions.

## Tính năng
- Ghi âm AAC (.m4a), **tạm dừng / tiếp tục / dừng**
- Ghi chạy nền (foreground service) – vẫn ghi khi tắt màn hình, điều khiển ngay trên thông báo
- Đồng hồ thời gian + thanh mức âm lượng
- Danh sách bản ghi: ngày giờ, thời lượng, dung lượng
- Phát lại có thanh tua, tạm dừng
- Đổi tên, chia sẻ, xoá bản ghi
- Chọn chất lượng: Cao / Tiêu chuẩn / Thấp
- Giao diện Tiếng Việt + English (theo ngôn ngữ máy), hỗ trợ chế độ tối
- Android 8.0 (API 26) trở lên; target Android 15

Bản ghi lưu tại `Android/data/vn.micrecorder.app/files/Music/` (không cần quyền bộ nhớ).

## Build trên GitHub
1. Tạo repo mới trên GitHub.
2. Giải nén ZIP và đẩy toàn bộ lên repo (nhớ có cả thư mục ẩn **`.github/`**):
   ```bash
   git init && git add . && git commit -m "Initial"
   git branch -M main
   git remote add origin https://github.com/<user>/<repo>.git
   git push -u origin main
   ```
   Nếu upload qua web, kiểm tra lại là thư mục `.github/workflows/build.yml` đã lên.
3. Vào tab **Actions** → workflow **Build APK** sẽ tự chạy (~3–5 phút).
   Có thể bấm **Run workflow** để chạy tay.
4. Mở lần chạy thành công → mục **Artifacts** → tải **MicRecorder-apk** (file zip chứa
   `MicRecorder-debug.apk` và `MicRecorder-release.apk`).
5. Cài APK lên điện thoại (cho phép "Cài ứng dụng không rõ nguồn gốc").

### Tạo bản Release
Đẩy tag dạng `v*`, APK sẽ được đính kèm vào trang Releases:
```bash
git tag v1.0 && git push origin v1.0
```

## (Tuỳ chọn) Khoá ký cố định – nên làm
Nếu không cấu hình, mỗi lần build APK được ký bằng khoá debug **ngẫu nhiên**, nên khi cài bản
mới đè lên bản cũ Android sẽ báo xung đột chữ ký (phải gỡ bản cũ → mất bản ghi).
Để cập nhật đè được, tạo khoá một lần:

```bash
keytool -genkeypair -v -keystore release.jks -alias micrecorder \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 release.jks > release.jks.b64
```
Vào repo → **Settings → Secrets and variables → Actions → New repository secret**, thêm:

| Secret | Giá trị |
|---|---|
| `KEYSTORE_BASE64` | nội dung file `release.jks.b64` |
| `KEYSTORE_PASSWORD` | mật khẩu keystore |
| `KEY_ALIAS` | `micrecorder` |
| `KEY_PASSWORD` | mật khẩu key |

Từ đó hãy cài **MicRecorder-release.apk**. Giữ kỹ file `release.jks` (không commit lên repo).

## Cấu trúc
```
.github/workflows/build.yml     # CI build APK
app/src/main/java/vn/micrecorder/app/
  MainActivity.kt               # giao diện, danh sách, phát lại
  RecorderService.kt            # ghi âm nền + thông báo
  RecordingsAdapter.kt          # danh sách bản ghi
  Storage.kt                    # lưu file, cài đặt chất lượng, tiện ích
app/src/main/res/               # layout, icon, chuỗi EN/VI
```
Đổi phiên bản trong `app/build.gradle.kts` (`versionCode`, `versionName`).

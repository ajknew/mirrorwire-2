# MirrorWire V2 — USB OTG Screen Mirroring

Aplikasi Android untuk melakukan screen mirroring dari ponsel sumber (CLIENT) ke ponsel penerima (HOST) melalui kabel USB OTG dan protokol Android Debug Bridge (ADB).

---

## 📥 Cara Mengunduh File APK dari GitHub

Setelah repository ini di-push ke akun GitHub Anda:

### Metode 1: Mengunduh Otomatis via GitHub Actions (Sangat Direkomendasikan)
1. Buka repository GitHub Anda di browser.
2. Klik tab **Actions** di bagian atas repository.
3. Anda akan melihat workflow bernama **"Build MirrorWire V2 APK"** yang berjalan otomatis setelah push (atau klik **Run workflow** secara manual).
4. Setelah build selesai (bertanda centang hijau ✅), klik workflow run tersebut.
5. Scroll ke bagian bawah pada tabel **Artifacts**, Anda akan melihat file:
   - 📦 **`MirrorWire-V2-Debug-APK`**
6. Klik nama artifact tersebut untuk langsung mengunduh file zip yang berisi `app-debug.apk`. Ekstrak dan instal di ponsel HOST Anda!

---

## 🛠️ Cara Membangun (Build) APK Sendiri Secara Lokal

Jika Anda meng-clone repository ini ke komputer:

```bash
# Clone repository
git clone <url-repository-anda>
cd mirrorwire-v2

# Jalankan build debug APK
./gradlew assembleDebug

# Lokasi file APK hasil kompilasi:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 Petunjuk Penggunaan MirrorWire V2

1. **Pasang APK**: Pasang `app-debug.apk` di ponsel **HOST**.
2. **Siapkan Ponsel CLIENT**:
   - Buka **Pengaturan (Settings)** -> **Tentang Ponsel (About Phone)** -> Ketuk *Nomor Bentukan (Build Number)* 7 kali hingga Opsi Pengembang aktif.
   - Buka **Opsi Pengembang (Developer Options)** dan aktifkan **USB Debugging**.
3. **Hubungkan Kabel**:
   - Pasang adapter USB OTG ke ponsel **HOST**.
   - Hubungkan kabel USB dari adapter OTG ke ponsel **CLIENT**.
4. **Izinkan Koneksi**:
   - Di ponsel HOST: ketuk **Izinkan (Allow)** saat muncul dialog izin USB.
   - Di ponsel CLIENT: ketuk centang *"Always allow from this computer"* lalu pilih **OK** saat muncul dialog otorisasi ADB.
5. **Mulai Mirroring**:
   - Tekan tombol **START MIRROR** pada aplikasi MirrorWire V2 di ponsel HOST.
   - Layar CLIENT akan langsung tampil secara real-time dengan akselerasi hardware H.264 MediaCodec!

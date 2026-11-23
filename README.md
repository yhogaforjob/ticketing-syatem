# Tiket Nuvera

Aplikasi tiket sisi agent untuk menangani chat bantuan customer dari aplikasi-aplikasi Nuvera.
Satu kode (`www/index.html`) dipakai untuk web dan APK Android (dibungkus Capacitor).

- Status tiket: **Open → In Progress → Resolved**, dengan durasi berjalan dan total durasi.
- Tiket otomatis **In Progress** saat dibuka. Chat dari customer setelah Resolved membuat tiket baru.
- Label sumber aplikasi dan kategori komplain otomatis, plus dashboard tren komplain.
- Balasan agent sampai ke customer dengan jeda beberapa detik (polling ke n8n).

## Cara kerja

```
App Nuvera ──form──▶ n8n /nuvera-tiket ──▶ Google Sheet (tickets, messages)
App Nuvera ──chat──▶ n8n /nuvera-chat  ──▶ Google Sheet
Aplikasi ini ──────▶ n8n /tiket-agen   ──▶ Google Sheet
```

Aplikasi ini tidak membaca Sheet langsung. Semua lewat webhook `tiket-agen` di n8n, yang dilindungi kunci agent.
Workflow n8n-nya ada di file `ticketing_chat.json` dan `ticketing_system_sheet.json` (tidak disimpan di repo ini).

## Chat Shopee (lewat Duoke + tablet bridge)

Tiket Shopee berkode `SHP-xxxxx` dan berlabel sumber **Shopee**. Datanya tidak lewat n8n:

```
Pembeli Shopee ─▶ Duoke ─▶ tablet bridge (script di Duoke web) ─▶ Supabase (tabel tiket_*)
Aplikasi ini ──── RPC tiket_agent (kunci agent yang sama) ───────▶ Supabase
Balasan agent ─▶ tiket_outbox ─▶ tablet bridge ─▶ Duoke ─▶ Shopee
```

- Daftar tiket menggabungkan tiket n8n dan tiket Shopee. Alur Open → In Progress → Resolved, kategori, dan balasan sama seperti tiket lain.
- Balasan Shopee tidak langsung terkirim: masuk antrean lalu dikirim oleh tablet bridge (biasanya beberapa detik). Tanda ⏳ = masih antre, ⚠️ = gagal terkirim. Auto reply Shopee ditandai 🤖 dan tidak dihitung sebagai balasan agent.
- Judul daftar menampilkan status penghubung: *Shopee tersambung*, *Penghubung Shopee offline* (tidak ada heartbeat 3 menit), atau *login ulang Duoke*.
- Kunci agent harus didaftarkan sekali di Supabase (fungsi `tiket_agent_set_key`, hanya service role). Kalau belum, tiket Shopee tidak tampil dan tiket n8n tetap normal.
- Notifikasi latar belakang di APK (NotifBg) masih hanya untuk tiket n8n.

## Info pelanggan dan reset PIN Nuvera Kasir

Di detail tiket (mode live) ada kartu **Pelanggan**: usaha, paket, lisensi (samar), masa berlaku, email akun, HP, versi app. Datanya dari tab `POS Registrasi` lewat aksi `customer`.
Tombol **Buat kode reset PIN** (aksi `reset`) meminta agent mencentang minimal 2 data yang cocok dengan pelanggan, lalu membuat kode 6 angka (30 menit, sekali pakai) yang bisa disalin atau dikirim ke chat.
Perangkat yang belum terdaftar (Nuvera Kasir di bawah v0.2.24) tidak punya data, jadi tombolnya tidak muncul. Butuh workflow n8n satu flow `n8n-workflow-nuvera-satu-flow.json` (repo Nusavera-Digital---POS, folder docs/ticketing).

## Memasang APK

1. Buka halaman **Releases** repo ini, rilis **APK terbaru**.
2. Unduh `tiket-nuvera.apk` di ponsel Android, lalu buka. Izinkan "pasang dari sumber ini" bila diminta.
3. Saat pertama dibuka, isi **nama agent** dan **kunci agent** (nilai `AK` di node "Cek agen" pada workflow n8n).
   Kunci hanya disimpan di perangkat. Tombol **mode demo** tersedia untuk mencoba tanpa server.

APK yang dibuat adalah build **debug**: cukup untuk dipasang langsung, tetapi bukan untuk Play Store.

## Membangun ulang

Setiap push ke `main` menjalankan workflow **Build APK** (GitHub Actions) dan memperbarui rilis **APK terbaru**.
Bisa juga dijalankan manual dari tab Actions → Build APK → Run workflow. Build memakan waktu sekitar 5 sampai 10 menit.

## Mengubah pengaturan

| Yang diubah | Di mana |
|---|---|
| Alamat webhook agent | konstanta `API` di `www/index.html` |
| Interval polling | `POLL_LIST` dan `POLL_MSG` di `www/index.html` |
| Batas waktu peringatan Open | `SLA_WARN` dan `SLA_CRIT` di `www/index.html` |
| Nama dan ID aplikasi Android | `capacitor.config.json` |
| Ikon | `resources/android/res` (dibuat ulang bila perlu) |

Sumber aplikasi dan kategori komplain dibaca dari tab `sources` dan `categories` di Google Sheet, jadi menambah aplikasi baru cukup dengan menambah baris di sheet.

## Jalankan di komputer

Buka `www/index.html` di browser. Untuk mencoba di Android Studio:

```bash
npm install
npx cap add android
./scripts/patch-android.sh
npx cap sync android
npx cap open android
```

## Catatan keamanan

- Jangan menaruh kunci agent di kode atau di repo. Kunci dimasukkan lewat layar masuk.
- URL webhook ada di kode. Webhook tetap aman selama kunci agent rahasia, tetapi jika repo dibuat publik, ganti kunci bila pernah bocor.

## Pembaruan dalam aplikasi

Setiap push ke `main` membangun APK baru (nomor build = nomor run GitHub Actions) dan memperbarui rilis
`apk-terbaru`. Aplikasi yang sudah terpasang mengecek rilis itu; kalau nomor build lebih baru, lonceng
bertitik dan tombol **Unduh dan pasang** mengunduh APK lalu membuka layar pemasangan Android.
Semua build ditandatangani keystore tetap di `signing/`, jadi pembaruan menimpa versi lama.

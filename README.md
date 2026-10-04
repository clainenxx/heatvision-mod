# Heat Vision

Mod Fabric untuk Minecraft **1.21.5**.

Tekan (tahan) tombol **I** — bisa diganti di *Options > Controls > Heat Vision* —
dan dua laser panas keluar dari mata kamu, lurus ke arah crosshair.

- **Kena tanah / blok**: muncul api di sisi blok, lalu blok hancur dengan efek ledakan
  (makin keras bloknya, makin lama hancurnya; bedrock tidak bisa hancur).
- **Kena player / mob / entity**: kena damage terus-menerus selama laser menempel di badannya,
  dan entity ikut terbakar.
- **First person**: seluruh layar jadi berfilter merah.
- **Stamina**: heat vision hanya bisa dipakai total **10 detik**. Progress bar stamina ada di atas hotbar.
  Kalau stamina habis -> **cooldown 20 detik** (bar terisi pelan-pelan, abu-abu), lalu stamina penuh lagi.
  Kalau dilepas sebelum habis, stamina terisi ulang **secepat pemakaian** (pakai 7 detik = 7 detik untuk penuh),
  dan selama terisi ulang heat vision tetap bisa dipakai lagi.
- **Suara laser** selama aktif (+ suara "pew" saat menyala), terdengar juga oleh pemain lain.
- Mata menyala di wajah dan laser keluar tepat dari kedua mata.
- Laser digambar sebagai beam sungguhan (bukan particle) dan terlihat oleh pemain lain.

## Pengaturan
Server (`HeatVisionMod`): `RANGE`, `DAMAGE`, `HIT_INTERVAL_TICKS`, `BURN_SECONDS`,
`BREAK_BASE_TICKS`, `BREAK_TICKS_PER_HARDNESS`, `BREAK_BLOCKS`, `MAX_STAMINA_TICKS` (200 = 10 dtk), `COOLDOWN_TICKS` (400 = 20 dtk).
Client (`HeatVisionClient`): `HOLD_MODE` (tahan vs toggle), `FILTER_ALPHA`, `LOOP_VOLUME`, `START_VOLUME`, `SHOW_BAR_WHILE_REGEN`.
Posisi mata laser (`BeamRenderer`): `EYE_FORWARD`, `EYE_SIDE`, `NECK_BELOW_EYE`, `HEAD_EYE_UP`.

## Build
```
./gradlew build      # -> build/libs/heatvision-1.0.0.jar
./gradlew runClient
```
Mod harus terpasang di server juga (di singleplayer otomatis).

Made by **Geord**. Licensed under MIT.

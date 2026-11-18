# Akıllı Sürücü Dikkat Sistemi (Driver Attention Monitoring) — Android

Bu bir bilgisayar mühendisliği bitirme projesidir. Geliştirici Kotlin/Android'e hâkim değil; kodu sen (Claude Code) yazıyorsun. Bu yüzden:

- Her adımı küçük tut, her adımdan sonra projenin **derlendiğinden emin ol** (`./gradlew assembleDebug`).
- Kodda **Türkçe yorum satırları** kullan; özellikle algoritma kısımlarında (EAR, MAR, PERCLOS, baş pozu) "ne yapıyor ve neden" açıkla. Geliştirici bu kodu jüriye anlatacak.
- Değişken/fonksiyon/sınıf isimleri İngilizce olsun.
- Her adımın sonunda neyi değiştirdiğini ve telefonda nasıl test edileceğini Türkçe, kısa özetle.
- Kullanıcıya sormadan büyük mimari değişiklik yapma.

## Amaç

Telefonun ön kamerasıyla sürücünün yüzünü gerçek zamanlı izleyip şunları algılamak ve uyarmak:

1. **Uyuşukluk / mikro uyku** — gözlerin uzun süre kapalı kalması
2. **Esneme** — yorgunluk belirtisi
3. **Dikkat dağınıklığı** — başın uzun süre yoldan başka yöne dönük olması (yana/aşağı)
4. **Yüz tespit edilemiyor** — sürücü kameranın görüş alanında değil

Tüm işlem **cihaz üzerinde (on-device)** yapılır; internet gerekmez, görüntü hiçbir yere gönderilmez.

## Teknoloji

- Dil: **Kotlin**
- UI: **Jetpack Compose** (Material 3)
- Kamera: **CameraX** (Preview + ImageAnalysis, `STRATEGY_KEEP_ONLY_LATEST`)
- Yüz landmark: **MediaPipe Tasks Vision – Face Landmarker** (`face_landmarker.task` modeli `assets/` içinde), `RunningMode.LIVE_STREAM`, mümkünse **GPU delegate** (desteklenmezse CPU'ya düş)
- Opsiyonel (Faz 6): **TensorFlow Lite** ile göz açık/kapalı sınıflandırıcı
- Mimari: **MVVM** (ViewModel + StateFlow), tek Activity
- Kalıcı veri: **Room** (sürüş oturumları ve uyarı kayıtları)
- minSdk 24, kütüphanelerin güncel kararlı sürümleri

## Paket yapısı (önerilen)

```
com.example.driverattention
├── camera/        # CameraX kurulumu, frame analyzer
├── detection/     # FaceLandmarkerHelper (MediaPipe sarmalayıcı)
├── analysis/      # EarCalculator, MarCalculator, HeadPoseEstimator, PerclosTracker, DriverStateEngine
├── alert/         # AlertManager (ses, titreşim, görsel)
├── data/          # Room entity/dao/repository
├── ui/            # Compose ekranları: Monitor, Calibration, History, Settings
└── MainActivity.kt
```

## Algoritmalar

### EAR (Eye Aspect Ratio)
`EAR = (|p2-p6| + |p3-p5|) / (2 * |p1-p4|)`

MediaPipe Face Mesh indeksleri:
- Sol göz: p1=33, p2=160, p3=158, p4=133, p5=153, p6=144
- Sağ göz: p1=362, p2=385, p3=387, p4=263, p5=373, p6=380

İki gözün ortalaması kullanılır. Normalize koordinatları piksele çevirmeden önce görüntü en-boy oranını hesaba kat.

### MAR (Mouth Aspect Ratio)
Dikey: 13 (üst dudak iç) – 14 (alt dudak iç); yatay: 78 – 308.
`MAR = |13-14| / |78-308|`

### Baş pozu
Face Landmarker'ın `facialTransformationMatrixes` çıktısından (veya burun ucu 1, çene 152, göz köşeleri 33/263, ağız köşeleri 61/291 ile solvePnP benzeri hesapla) **yaw / pitch / roll** açıları.

### PERCLOS
Son 60 saniyede gözlerin kapalı (EAR < eşik) olduğu karelerin yüzdesi. Kayan pencere (ring buffer, zaman damgalı).

### Kalibrasyon
Uygulama açılışında ~5–10 sn kullanıcıdan düz bakması istenir; ortalama açık-göz EAR'ı ölçülür.
`EAR_eşik = kalibre_EAR * 0.75` (ayarlardan değiştirilebilir). Kalibrasyon yoksa varsayılan 0.21.

### Karar mantığı (DriverStateEngine)
Gürültüyü azaltmak için değerlere hareketli ortalama (son ~5 kare) uygula.

| Durum | Koşul (varsayılan, ayarlanabilir) | Seviye |
|---|---|---|
| Mikro uyku | EAR < eşik, **≥ 1.5 sn** kesintisiz | KRİTİK |
| Yorgunluk | PERCLOS ≥ %15 (60 sn pencere) | UYARI |
| Esneme | MAR > 0.6, ≥ 1 sn; 5 dk içinde ≥ 3 esneme → yorgunluk | UYARI |
| Dikkat dağınıklığı | \|yaw\| > 30° veya pitch < -20° (aşağı), **≥ 2 sn** | UYARI |
| Yüz yok | ≥ 3 sn yüz tespit edilemedi | BİLGİ |

Aynı uyarı art arda tetiklenmesin (cooldown, ~5 sn).

## Uyarılar (AlertManager)
- KRİTİK: yüksek sesli alarm (ToneGenerator veya `res/raw` ses), güçlü titreşim, tam ekran kırmızı yanıp sönme
- UYARI: kısa bip + titreşim + turuncu banner
- Sesli uyarı için TextToSpeech (Türkçe): "Dikkat! Lütfen yola odaklanın." / "Yorgun görünüyorsunuz, mola verin."

## Ekranlar
1. **Monitor** — kamera önizlemesi (isteğe bağlı landmark overlay), canlı EAR/MAR/yaw/pitch/PERCLOS değerleri, FPS, durum göstergesi (yeşil/turuncu/kırmızı), Başlat/Durdur
2. **Calibration** — geri sayım + yönlendirme
3. **History** — geçmiş sürüş oturumları: süre, uyarı sayıları, uyarı zaman çizelgesi
4. **Settings** — eşikler, ses/titreşim aç-kapa, overlay göster/gizle, debug modu

## Performans hedefleri
- Orta seviye telefonda ≥ 15 FPS (hedef 25–30)
- Analiz çözünürlüğü 640x480 civarı
- Ana thread'i bloklama; analiz arka plan executor'ında
- Ekran açık kalsın (`FLAG_KEEP_SCREEN_ON`) sürüş sırasında
- FPS ve kare başına işlem süresini ölç, debug modda göster (rapor için gerekli)

## Test / değerlendirme (rapor için)
- `analysis/` paketindeki saf Kotlin sınıfları için **birim testleri** (EAR/MAR hesaplama, PERCLOS penceresi, karar motoru zamanlamaları)
- Debug modda ölçümleri CSV olarak dışa aktarma (zaman, EAR, MAR, yaw, pitch, durum) → raporda grafik çizmek için

## Geliştirme fazları
1. Proje iskeleti + izinler + CameraX önizleme (ön kamera)
2. MediaPipe Face Landmarker entegrasyonu + landmark overlay + FPS
3. EAR, MAR, baş pozu hesapları + ekranda canlı değerler
4. Kalibrasyon + DriverStateEngine + AlertManager
5. Room ile geçmiş + History ve Settings ekranları + CSV dışa aktarma
6. (Opsiyonel) TFLite göz sınıflandırıcı, düşük ışık iyileştirmesi (histogram eşitleme), birim testlerinin tamamlanması

Bir faz bitmeden sonrakine geçme.

**Faz 1–6 tamamlandı.** Aşağıdaki fazlar geliştirme aşamasıdır.

---

## Faz 7 — Yorgunluk Skoru (0–100)

Tek tek uyarıların ötesinde, sürücünün genel yorgunluğunu sürekli izleyen bir skor.

`analysis/FatigueScoreCalculator.kt` (saf Kotlin, birim testli). Her bileşen 0–1 arasına normalize edilir:

| Bileşen | Normalizasyon | Ağırlık |
|---|---|---|
| PERCLOS (60 sn) | `min(PERCLOS / 0.30, 1)` | 0.35 |
| Uzun göz kapanmaları (> 0.5 sn), son 5 dk | `min(sayı / 5, 1)` | 0.20 |
| Esneme sayısı, son 10 dk | `min(sayı / 4, 1)` | 0.15 |
| Dikkat dağınıklığı olayları, son 5 dk | `min(sayı / 5, 1)` | 0.10 |
| Kesintisiz sürüş süresi | `min(dakika / 120, 1)` | 0.10 |
| Göz kırpma süresi ortalaması (son 2 dk) | `clamp((ort_ms - 150) / 250, 0, 1)` | 0.10 |

`skor = 100 * Σ(ağırlık * bileşen)`, ardından üstel hareketli ortalama (α = 0.05, saniyede bir güncelleme) ile yumuşatılır. Ağırlıklar ve sınırlar `Settings`'ten değil, sabitler dosyasından gelsin (raporda tablo olarak verilecek).

Seviyeler:
| Skor | Seviye | Davranış |
|---|---|---|
| 0–29 | Normal (yeşil) | — |
| 30–59 | Hafif yorgun (sarı) | Ekranda bilgi |
| 60–79 | Yorgun (turuncu) | TTS: "Yorgunluk belirtileri artıyor, mola vermeyi düşünün." (10 dk'da en fazla 1 kez) |
| 80–100 | Çok yorgun (kırmızı) | TTS + titreşim: "Lütfen en kısa sürede mola verin." + "Mola yeri bul" butonu |

- "Mola yeri bul" butonu: `geo:0,0?q=dinlenme tesisi` / `benzin istasyonu` intent'iyle harita uygulamasını açar.
- Monitor ekranında yarım daire gösterge (gauge) + bileşen dökümü (debug modda).
- Skor zaman serisi Room'a kaydedilsin (her 10 sn); History'deki oturum detayında **skor–zaman grafiği** (Compose Canvas ile çiz, ek kütüphane gerekirse Vico kullan) ve uyarıların grafikteki işaretleri.
- CSV'ye `fatigue_score` sütunu ekle.

## Faz 8 — Hıza Duyarlı Uyarılar (GPS)

Araç dururken (kırmızı ışık, park) gereksiz alarm çalmasın; hız arttıkça dikkat dağınıklığı toleransı azalsın.

- `ACCESS_FINE_LOCATION` izni; `FusedLocationProviderClient`, 1 sn aralık. Hız `Location.speed` (m/s → km/s), son 3 ölçümün medyanı. GPS yok/izin yoksa: hız bilinmiyor → mevcut (Faz 4) davranış.
- `analysis/SpeedPolicy.kt` (saf Kotlin, birim testli):

| Hız | Dikkat dağınıklığı süresi | Mikro uyku | Diğer |
|---|---|---|---|
| < 5 km/s (duruyor) | uyarı yok | sadece görsel uyarı | esneme/PERCLOS kaydedilir, uyarı yok |
| 5–30 km/s | 3.0 sn | normal | normal |
| 30–70 km/s | 2.0 sn | normal | normal |
| 70–100 km/s | 1.5 sn | normal | normal |
| > 100 km/s | 1.0 sn | normal + daha yüksek ses | normal |

- Monitor ekranında anlık hız ve "Duruyor / Sürüşte" rozeti.
- **Simülasyon modu (zorunlu):** Settings → Debug'da "Hızı simüle et" anahtarı + 0–150 km/s kaydırıcı. Geliştirici araç kullanırken test yapmamalı; tüm testler simüle hızla masa başında yapılabilmeli. Simülasyon açıkken ekranda belirgin "SİMÜLASYON" etiketi.
- CSV'ye `speed_kmh` ve `speed_source` (gps/sim/none) sütunları ekle.

## Faz 9 — CNN Göz Durumu Modeli (TFLite)

Model Colab'da eğitildi (`goz_modeli_egitimi.ipynb`). `app/src/main/assets/` içinde `eye_state.tflite` ve `model_info.json` var. **Ön işlemeyi `model_info.json`'daki adımlara birebir uy** — eğitimle aynı olmazsa model yanlış çalışır.

- Bağımlılık: TensorFlow Lite / LiteRT Android runtime (güncel kararlı). Mümkünse GPU delegate, olmazsa XNNPACK CPU.
- `detection/EyeStateClassifier.kt`:
  - Giriş `[1,64,64,1] float32`, gri tonlama `0.299R+0.587G+0.114B`, `/255`.
  - Göz kutusu: sol göz landmark 33,133,159,145 / sağ göz 362,263,386,374 → kare kutu + her yöne göz genişliğinin %30'u pay. Görüntü sınırlarına clamp.
  - Kırpmayı **aynalanmamış** analiz karesinden yap (önizleme aynalı olabilir, analiz karesi değil).
  - İki göz ayrı çalıştırılır, çıktıların ortalaması = `closedProb`. Eşik `model_info.json`'daki `threshold`.
  - Debug modda kırpılmış 64x64 göz görüntülerini ekranın köşesinde küçük göster (doğru kırpıldığını görmek için).
- Settings'e **Göz durumu yöntemi** seçeneği: `EAR` / `CNN` / `Hibrit` (varsayılan Hibrit).
  - Hibrit: göz kapalı ⇔ `EAR < eşik` VE `closedProb > threshold`; veya ikisinden biri çok güçlüyse (`EAR < 0.8*eşik` veya `closedProb > 0.9`).
- Seçili yöntemden bağımsız olarak **her karede üç yöntemin de kararını hesapla ve CSV'ye yaz**: `ear_closed`, `cnn_prob`, `cnn_closed`, `hybrid_closed`, ayrıca `cnn_ms` (çıkarım süresi). Bu, raporda yöntem karşılaştırması için.
- **Etiketleme modu (Debug):** Ekranda basılı tutulan bir "Gözlerim kapalı" butonu; basılıyken CSV'de `ground_truth=1`, değilken `0`. Böylece geliştirici kendi yüzüyle etiketli test verisi toplayıp üç yöntemin doğruluğunu karşılaştırabilir.
- Debug ekranında: CNN çıkarım süresi (ms), toplam FPS (CNN eklenince FPS düşüşünü raporda göstermek için).

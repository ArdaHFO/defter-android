# Defter 0.5.0-alpha kullanım ve geliştirme notları

Tam özellik listesi [Türkçe README](../README.tr.md) ve [English README](../README.md) içindedir. Arayüz varsayılan olarak İngilizce açılır. **App language → Türkçe → Apply** seçimi kalıcıdır. Kullanıcının notları, defter adları ve transkriptleri çevrilmez.

## Yazma ve sayfalarda gezinme

**Finger: pan** modunda parmak sayfayı taşır, kalem seçili araçla yazar. **Finger: draw** parmakla da çizim yapılmasını sağlar. Parmak gibi algılanan pasif kalemler çizim modunda kullanılmalıdır. Basınç ve yan düğme desteği Android'in uygulamaya ilettiği donanım olaylarına bağlıdır.

Kalemin yan düğmesine basılı tutmak seçili silgi ayarlarını geçici olarak kullanır. Bırakınca önceki araca dönülür. Silgi tam çizgi veya kısmi silme yapabilir, kalem/fosforlu/şekil filtresi uygulanır. Metin, görsel ve PDF arka planı silgiden etkilenmez.

Taşıma modunda uzun basmak sayfa işlemlerini açar. Kopyala, kes, yapıştır, tümünü seç ve seçimi sil kullanılabilir. Mürekkep, görsel ve metin seçimleri açık uygulama oturumunda sayfalar/defterler arasında düzenlenebilir olarak yapıştırılır. Süreç kapanınca bu iç pano kaybolur. Android panosundan dış metin ve görseller de yapıştırılabilir.

Sayfanın alt sınırında biraz daha yukarı çekip ipucu hazır olduğunda bırakmak sonraki sayfaya geçer. Son sayfada bir yeni sayfa ekler. Üst sınırdan aşağı çekmek önceki sayfanın altını açar. Bu özellik sayfa sonunda geçiştir, kesintisiz uzun sayfa akışı değildir. Sayfa okları ve sayfa yöneticisi de kullanılabilir.

Geri al/yinele açık sayfa oturumundaki son 60 işlemi tutar. Sayfa değişince geçmiş sıfırlanır. Sayfa yöneticisi önizleme, sürükleyerek veya oklarla sıralama, çoğaltma, taşıma, kopyalama, silme ve geri getirme sağlar. Defterde en az bir aktif sayfa kalır.

Sekmelerde birden fazla defter açılır. Referans bölmesinde bağımsız gezinme ve yakınlaştırma yapılabilir. Düzenlemek için panellerin yerini değiştirin. Telefonda paneller üst üste yerleşir. Referans tarafı salt okunurdur.

## Ses ve tanıma

Kütüphanedeki **Audio** bağımsız ses arşivini, defterdeki mikrofon ise o defterin seslerini açar. Kayıt, duraklatma/devam, harici ses içe aktarma, sayfa bağlantılı zaman işaretleri ve 0,5–2× oynatma bulunur. Sesler deftere bağlanabilir/ayrılabilir ve klasörlere taşınabilir.

İlk transkripsiyon için yaklaşık 148 MB Whisper base modeli kullanıcı isteğiyle indirilir ve SHA-256 ile doğrulanır. Sonraki transkripsiyon cihazda çalışır. API anahtarı veya abonelik gerekmez. Modelin 99 dil kapsamı ile uygulamanın Türkçe/İngilizce/otomatik dil kontrolleri farklıdır. Ayrıntı ve dil listesi README'dedir. Her dilin doğruluğu bu projede ölçülmüş değildir.

Transkripsiyon tamamlanmış kayıtta çalışır, duraklatılıp tamamlanan bölümden sürdürülebilir. Zaman damgasından dinleme, arama, metin düzeltme, sayfaya ekleme, TXT ve SRT dışa aktarma vardır. Canlı altyazı, konuşmacı ayırma ve çeviri yoktur.

El yazısı tanıma iki dil sunar, Türkçe ve İngilizce. ML Kit dil modeli indirildikten sonra mürekkep üzerinde cihazda çalışır. Yeni çizim eski tanıma sonucunu geçersiz kılar; arama dizini yeniden hazırlanmalıdır. Fotoğraf/PDF OCR'ı veya denklem çözümü içermez.

## Kayıt, yedek ve çöp kutusu

Yerel kayıt otomatik yapılır. Sürüm denetimi eski editörün yeni kaydı ezmesini önler; başarısız değişiklikler ayrı kurtarma kopyasına alınabilir. Tam kütüphane yedeği düzenlenebilir `.defter` paketidir ve ses/transkriptleri içerir. Geri yükleme mevcut kütüphaneye birleştirir. Yedek alma kullanıcı tarafından başlatılır, otomatik sürümlü yedekleme yoktur.

Defter ve ses çöp kutularında çoklu seçim ve onaylı kalıcı silme vardır. Arama açıkken tümünü seç yalnızca görünen sonuçları seçer. Defter kalıcı silinirken sesler korunur, silinen sayfa bağlantıları ayrılır. İlgili ses kaydı veya transkripsiyon sürüyorsa işlem reddedilir. Paylaşılan varlıklar başka içerik tarafından kullanılıyorsa korunur.

## Teknik sınırlar

| Alan | Sınır veya davranış |
| --- | --- |
| Android | 8.0 / API 26 ve üzeri; hedef SDK 35. Dört ABI derlenir. |
| PDF içe aktarma | Dosya başına 64 MiB ve 2.000 sayfa. Özgün baytlar saklanır. Ekran ve PDF dışa aktarımındaki arka plan rasterdır; özgün metin katmanı dışa taşınmaz. |
| Görsel | EXIF yönü uygulanır, PNG olarak saklanır; en fazla 4.096 piksel kenar ve 8.388.608 toplam piksel. Özgün dosya ayrıca saklanmaz. |
| Ses içe aktarma | Dosya başına 256 MiB. Destek Android medya çözücüsüne bağlıdır; gerçek ses izi doğrulanır. |
| Mikrofon kaydı | AAC/M4A yaklaşık 64 kbit/s. Tamamlanan parçalar saklanır; süreç kesilirse son tamamlanmamış parça kaybolabilir. |
| Yedek | JSON 32 MiB, genel girdi 64 MiB, ses girdisi 256 MiB, açılmış toplam 512 MiB. Bunlar sayfa/defter adet kotası değildir. |
| El yazısı tanıma | İstek başına en fazla 200.000 mürekkep noktası. |
| Belge sürümü | V1/V2 okunur, yeni kayıt V3'tür. Eski APK V3'ü okuyamaz. |

Uygulama yerel çalışır. Bulut eşitleme ve ortak düzenleme yoktur. ML Kit Google istemci kitaplıkları getirir; Google Play Services bulunmayan cihazlarda model indirme ve tanıma ayrıca doğrulanmalıdır. Fiziksel Tab S10 FE/S Pen performansı henüz doğrulanmış değildir.

## Derleme ve doğrulama

JDK 17, Gradle 8.13, SDK 35, Build Tools 35.0.0, NDK 28.2.13676358 ve CMake 3.22.1 kullanılır. [Derleme komutları](../README.md#build-from-source) ve [doğrulama kapsamı](VALIDATION.md) ayrı tutulur. `Check` test ve Lint çalıştırır, `Build` APK üretir. Özel imza anahtarını koruyun ve GitHub'a göndermeyin.

Güncel tamamlanmış kontrol 309 test / 50 sınıf, sıfır başarısızlık ve sıfır Lint hatası / 27 uyarı kaydeder. Bu sonuçlar bütün Android cihazlarında veya fiziksel kalemde doğrulama anlamına gelmez.

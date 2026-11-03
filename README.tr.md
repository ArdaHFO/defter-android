Defter, Android telefon veya tabletinizde el yazısıyla not almanızı, PDF üzerine yazmanızı ve ses kayıtlarını aranabilir metne dönüştürmenizi sağlar.

<div align="center">

# Defter

**Defterleriniz, belgeleriniz ve ses kayıtlarınız aynı yerde.**

Basınca duyarlı kalem, PDF üzerinde çalışma, yerel yedekler ve cihaz üzerinde konuşma tanıma sunan Android not defteri.

[English](README.md) · [Türkçe](README.tr.md)

[Başlangıç](#başlangıç) · [Dil desteği](#dil-desteği) · [Kaynaktan derleme](#kaynaktan-derleme) · [Doğrulama](docs/VALIDATION.md) · [Sorun bildir](https://github.com/ArdaHFO/defter-android/issues)

![Durum](https://img.shields.io/badge/durum-0.5.0--alpha-orange)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-7F52FF?logo=kotlin&logoColor=white)
![Arayüz](https://img.shields.io/badge/UI-Jetpack_Compose-4285F4)

</div>

## Genel bakış

Defter, el yazısı sayfaları, içe aktarılan belgeleri ve sesleri yerel bir kütüphanede birleştirir. Defterlerinizi klasörlere ayırabilir, bir referans belgenin yanında çalışabilir ve ses kaydını kullandığınız sayfaya bağlayabilirsiniz. İsteğe bağlı el yazısı ve konuşma modelleri indirildikten sonra cihaz üzerinde çalışır. Hesap, transkripsiyon API anahtarı veya abonelik gerekmez.

Bu depo, **3 Kasım 2026** tarihinde güncellenen **0.5.0-alpha** kaynağını içerir. Uygulama, cihazın dilinden bağımsız olarak **İngilizce** açılır. **App language → Türkçe → Apply** ile Türkçe seçilir ve tercih korunur. Defter adları, notlar, ses başlıkları ve transkriptlerin içeriği değiştirilmez.

0.5 sürümü kalıcı parmakla çizme/taşıma seçimi, uzun basma düzenleme menüsü, sayfalar arasında düzenlenebilir seçim kopyalama/kesme/yapıştırma, sayfa sınırından gezinme ve yeni sayfa oluşturma, defter ve ses çöp kutusunda onaylı toplu silme ekler. Önceki telefon/tablet yerleşimi, indigo/mint teması, defter kapakları, ortak ses arayüzü, uyarlanabilir ikon ve seçim/basma animasyonları korunur.

## Görünüm

<p align="center">
  <img src="docs/images/editor-split.png" alt="Tablette referans sayfanın yanında İngilizce Defter editörü" width="850">
  <img src="docs/images/library-tablet.png" alt="Klasör ve defter kapaklarıyla İngilizce Defter kütüphanesi" width="850">
</p>
<p align="center">
  <img src="docs/images/audio-phone-library.png" alt="Örnek kaydın bulunduğu İngilizce telefon ses kütüphanesi" width="220">
  <img src="docs/images/audio-tablet-transcript.png" alt="Düzenlenebilir metin ve zaman damgalı oynatma sunan İngilizce tablet transkripti" width="650">
</p>

Önizlemeler İngilizce arayüzü sentetik defter ve ses içeriğiyle gösterir. Robolectric üzerinden alınmış yerel çizimlerdir. Fiziksel cihaz ekran görüntüsü veya konuşma tanıma doğruluğunun kanıtı değildir. Çekim yöntemi ve kapsamı [görsel kaynak bilgileri](docs/images/README.md) dosyasında açıklanır.

## Uygulanan özellikler

| Alan | 0.5 sürümünde bulunanlar |
| --- | --- |
| Kütüphane düzeni | Klasör ve alt klasör oluşturma, klasörü yeniden adlandırma. Kapak rengiyle defter oluşturma, yeniden adlandırma, klasörler arasında taşıma, defter adı ve dizinlenmiş sayfa içeriğinde arama. |
| Defter ve ses çöp kutusu | Çöpe taşıma ve geri getirme. Görünür öğeleri çoklu seçme, tümünü seçme, seçimi temizleme ve onayla kalıcı silme. Defter silinirken bağlı sesler korunur, silinen sayfalara bağlantıları kaldırılır. Etkilenen ses kaydını durdurun; etkin transkripsiyonu duraklatın veya durdurun. |
| Sayfalar | Ekleme ve çoğaltma, gerçek küçük önizlemeler, sürükleme veya oklarla sıralama, silme ve geri getirme, defterler arasında kopyalama veya taşıma. Defterde en az bir aktif sayfa kalır. |
| Kâğıt | Boş, çizgili, kareli, noktalı ve Cornell şablonları. Açık ve koyu kâğıt renkleri. |
| Mürekkep ve renk | Kalem ve fosforlu kalem, cihaz basınç verisi sağlıyorsa değişken çizgi kalınlığı, hazır renkler, özel HEX renk ve kalınlık ayarı. |
| Silgi | Tam çizgi veya kısmi silme. Tüm çizimler, kalem, fosforlu veya şekil filtresi. Metin, görsel ve PDF arka planı silgiden etkilenmez. |
| Geometri | Çizgi, ok, dikdörtgen ve elips. İsteğe bağlı 15°/30°/45°/90° açı hizalaması, kare ve çember kısıtı. |
| Seçim ve pano | Tek nesne seçimi veya kementle grup seçme. Taşıma, çoğaltma, renklendirme, ölçekleme ve silme. Mürekkep, şekil, görsel ve metni sayfalar/defterler arasında düzenlenebilir olarak kopyalama, kesme ve yapıştırma. Seçilen düz metin Android panosuna da aktarılır. |
| Görsel ve metin | Dosyadan veya galeriden görsel ekleme, desteklenen görsel/metni yapıştırma ve Android paylaşımını alma. Görsel kırpma, döndürme ve oranı koruyarak boyutlandırma. Var olan metnin içeriğini, boyutunu ve rengini düzenleme. Düzenleme ve kırpma taslakları ekran dönünce korunur. |
| Dokunma ve kalem | Varsayılan **Finger: pan / Parmak: taşı** modunda parmak gezinir, kalem seçili araçla çalışır. **Finger: draw / Parmak: çiz** modunda parmak da aynı aracı kullanır. Tercih korunur. İki parmakla yakınlaştırma ve genişliğe sığdırma bulunur. Parmak olarak algılanan pasif kalem çizim modunu kullanır. |
| Hızlı düzenleme ve gezinme | Taşıma modunda parmakla uzun basarak kopyala, kes, yapıştır, tümünü seç veya seçimi sil menüsü. Sayfa sınırını geçip bırakınca gezinme. Son sayfanın altından çekip bırakınca tek sayfa ekleme. İlerleme ve bırakma ipucu görünür. |
| Kalem yan düğmesi | Android'in bildirdiği kalem yan düğmesine basılı tutarak geçici silgi. Bırakınca önceki araç, renk ve kalınlığa dönüş. Samsung'a özel SDK yerine standart Android olayları kullanılır. |
| Geçmiş ve çalışma alanı | Yazılı Geri al/Yinele düğmeleri, defter sekmeleri, sekme başına son sayfa ve bağımsız gezinilen salt okunur referans paneli. Referansı düzenlemek için panelleri değiştirme. Tablette yan yana, dar ekranda üst üste yerleşim. Android etkinliği geri yüklerken sekme/sayfa/referans oturum durumu korunur. |
| PDF iş akışı | PDF'yi defter sayfaları olarak içe aktarma ve üzerine yazma. Özgün PDF varlığını yerelde koruma. Mürekkep, şekil, metin, görsel ve çizilmiş arka planlarıyla defteri PDF olarak dışa aktarma. |
| El yazısı tanıma | Türkçe veya İngilizce model indirme. Seçili çizgileri veya bütün sayfayı tanıma, sonucu gözden geçirip düzeltme ve istenirse özgün mürekkebin yerine koyma. Tanınan el yazısını dizinleyip metin kutularıyla birlikte arama. |
| Yerel kayıt ve kurtarma | Görünür durumla otomatik kayıt, eski editörün daha yeni kaydı sessizce ezmesini önleyen sürüm denetimi, yeniden kayıt denemesi ve kaydedilemeyen değişiklikleri ayrı kopya olarak kurtarma. Bozuk veya desteklenmeyen belgeler boş not gibi açılmak yerine hata verir. |
| Düzenlenebilir yedek | Bütün kütüphaneyi sürümlü, SHA-256 kontrollü `.defter` ZIP paketi olarak dışa aktarma. Defter, varlık, ses ve transkriptleri dahil etme. Mevcut içerikle birleştirerek geri yükleme. Kalıcı silinen öğeler yedeğe girmez. |
| Ses kaydı ve düzeni | Defter içinde veya bağımsız ses arşivi/klasörlerde kayıt. Duraklatma, devam, durdurma ve harici ses içe aktarma. Not yazarken görünür kayıt çubuğu, isteğe bağlı sayfa bağlantılı zaman işaretleri, yeniden adlandırma, deftere bağlama/ayırma ve klasörler arasında taşıma. |
| Dinleme | Kayıt içinde gezinme, işaret veya transkript zaman damgasına atlama, 0,5–2× oynatma hızı. |
| Yerel transkripsiyon | Çok dilli Whisper base modelini indirip doğrulama. Tamamlanan kayıtları cihazda işleme, ilerleme, duraklatma/devam için kayıt noktaları ve transkripti değiştirmeden önce yeniden başlatma onayı. Hizmet ciddi sıcaklık durumunda veya Android işlem süresi dolunca duraklar. Dil kapsamı aşağıda ayrıntılıdır. |
| Transkript araçları | Ses başlıklarında ve transkript içeriğinde arama, bölümleri düzenleme, zaman damgasından dinleme, bölümü açık not sayfasına ekleme ve TXT/SRT dışa aktarma. |
| Arayüz dili | Varsayılan İngilizce ve kütüphane/editörden seçilebilen kalıcı Türkçe tercihi. Arayüz, el yazısı ve konuşma dili ayrı seçimlerdir. |

## Dil desteği

| Özellik | Güncel kapsam |
| --- | --- |
| Uygulama arayüzü | **2 dil** — İngilizce ve Türkçe. Türkçe cihazda da varsayılan İngilizcedir. |
| El yazısı tanıma | ML Kit üzerinden **2 seçilebilir model** — Türkçe (`tr-TR`) ve İngilizce (`en-US`). ML Kit'in diğer dilleri bu uygulamada sunulmaz. |
| Konuşma tanıma seçenekleri | **Türkçe (`tr`)**, **İngilizce (`en`)** veya **Otomatik (`auto`)**. Arayüz ve yerel köprü yalnız bu açık modları kabul eder. |
| Alttaki konuşma modeli | **99 dilli sözcük dağarcığına** sahip çok dilli **Whisper base**. Otomatik mod dil tespitini modele bırakır ve daha geniş model dil kümesindeki konuşmaları işlemeyi deneyebilir. Bu 99 dilin her birini elle seçen bir liste yoktur ve tamamı Defter'de doğrulanmamıştır. |

**99**, indirilen modelin kapsamıdır. Uygulamada tek tek seçilebilen veya bağımsız olarak test edilmiş 99 dil anlamına gelmez. Sayı, sabitlenmiş modelin `n_vocab = 51865` değeri ve motorun dil sayısı hesabına dayanır. Motorun tablosunda diğer modeller için Kantonca da bulunur, fakat bu ek giriş base modelinin 99 dilli kümesine dahil değildir. Transkripsiyon konuşmanın özgün dilinde metin üretir. Diller arasında çeviri kapalıdır.

<details>
<summary>Base modelinin 99 dil kodu</summary>

Kodların İngilizce dil adlarıyla tam listesi aşağıdadır. Bu liste model kapsamını gösterir, diller arasında eşit doğruluk iddiası değildir.

Afrikaans (`af`), Albanian (`sq`), Amharic (`am`), Arabic (`ar`), Armenian (`hy`), Assamese (`as`), Azerbaijani (`az`), Bashkir (`ba`), Basque (`eu`), Belarusian (`be`), Bengali (`bn`), Bosnian (`bs`), Breton (`br`), Bulgarian (`bg`), Burmese/Myanmar (`my`), Catalan (`ca`), Chinese (`zh`), Croatian (`hr`), Czech (`cs`), Danish (`da`), Dutch (`nl`), English (`en`), Estonian (`et`), Faroese (`fo`), Finnish (`fi`), French (`fr`), Galician (`gl`), Georgian (`ka`), German (`de`), Greek (`el`), Gujarati (`gu`), Haitian Creole (`ht`), Hausa (`ha`), Hawaiian (`haw`), Hebrew (`he`), Hindi (`hi`), Hungarian (`hu`), Icelandic (`is`), Indonesian (`id`), Italian (`it`), Japanese (`ja`), Javanese (`jw`), Kannada (`kn`), Kazakh (`kk`), Khmer (`km`), Korean (`ko`), Lao (`lo`), Latin (`la`), Latvian (`lv`), Lingala (`ln`), Lithuanian (`lt`), Luxembourgish (`lb`), Macedonian (`mk`), Malagasy (`mg`), Malay (`ms`), Malayalam (`ml`), Maltese (`mt`), Maori (`mi`), Marathi (`mr`), Mongolian (`mn`), Nepali (`ne`), Norwegian (`no`), Norwegian Nynorsk (`nn`), Occitan (`oc`), Pashto (`ps`), Persian (`fa`), Polish (`pl`), Portuguese (`pt`), Punjabi (`pa`), Romanian (`ro`), Russian (`ru`), Sanskrit (`sa`), Serbian (`sr`), Shona (`sn`), Sindhi (`sd`), Sinhala (`si`), Slovak (`sk`), Slovenian (`sl`), Somali (`so`), Spanish (`es`), Sundanese (`su`), Swahili (`sw`), Swedish (`sv`), Tagalog (`tl`), Tajik (`tg`), Tamil (`ta`), Tatar (`tt`), Telugu (`te`), Thai (`th`), Tibetan (`bo`), Turkish (`tr`), Turkmen (`tk`), Ukrainian (`uk`), Urdu (`ur`), Uzbek (`uz`), Vietnamese (`vi`), Welsh (`cy`), Yiddish (`yi`), and Yoruba (`yo`).

</details>

## Başlangıç

Aşağıdaki adımlarla geliştirme APK'sı derleyip Android 8.0+ telefon veya tablete kurun. Bu kaynak deposu model ağırlıkları veya mağaza imzalı sürüm içermez. Alpha sürümünü değerlendirirken önemli notlarınızın yedeğini koruyun. Aşağıdaki adımlar ilk açılıştaki İngilizce düğme adlarını kullanır.

1. **New folder** veya **New notebook** seçin. Defteri bir klasörde oluşturmak için önce klasöre girin. Kapak rengini ve ilk sayfanın şablonunu belirleyin.
2. Parmakla gezinip kalemle yazmak için **Finger: pan** modunda kalın. Parmakla çizmek veya pasif kalem kullanmak için **Finger: draw** seçin. Araç, renk ve kalınlığı ayarlayın. **Eraser options** ve **Math tools** ek seçenekleri açar.
3. Kütüphaneden **Import PDF** kullanın veya editörden görsel/metin ekleyin. Sayfa numarasına dokunarak **Pages** önizleme, sıralama ve işlemlerini açın. Sayfa sınırından çekerek veya sayfa oklarıyla gezinin.
4. Sekme artısı başka defter açar. **Open side by side** referans ekler, **Edit reference** onu düzenleme tarafına alır. Parmakla taşıma modunda uzun basma pano menüsünü açar.
5. Kütüphanedeki **Audio** veya defterin mikrofon düğmesinden **Start recording** ya da **Import audio** seçin. Kayıt mikrofon izni ister ve ön plan bildirimi gösterir. Yazarken işaret koyarak kayıt zamanını geçerli sayfaya bağlayın.
6. Ses ekranından **Speech model → Download model** açın. Model eksikse **Transcribe** de bu pencereyi açar. Yaklaşık **148 MB** indirme sürdürülebilir ve sabit SHA-256 özetiyle doğrulanır. Model hazır olunca kaydı durdurun, **Turkish**, **English** veya **Automatic** seçip **Transcribe** düğmesine basın. Sonucu kullanmadan önce gözden geçirip düzeltin.
7. **Handwriting and search** bölümünden Türkçe veya İngilizce seçip **Download language model** kullanın. **Prepare handwriting for search** defter mürekkebini dizinler. Yeni yazı eski tanıma sonucunu geçersiz kıldığı için düzenlemeden sonra dizini güncelleyin.
8. **Back up** ile düzenlenebilir `.defter` yedeğini cihaz dışında saklayın. **Restore backup** yedeği kütüphaneye birleştirir. PDF ve TXT/SRT ayrıca paylaşılabilir çıktılar sunar.
9. **Trash** içinde öğeleri veya **Select all** seçeneğini işaretleyin, **Delete selected** düğmesine basıp onaylayın. Seslerde aynı akış **Deleted items** bölümündedir. İşlem görünür seçime uygulanır ve onay sonrası geri alınamaz.

[Ayrıntılı Türkçe kılavuz](docs/DEVELOPMENT.tr.md) kontrolleri, depolama davranışını ve cihaz testlerini açıklar. Kılavuzdaki Türkçe düğme adlarını kullanmak için **App language → Türkçe → Apply** seçin. Arayüz dilini değiştirmek içeriği çevirmez, konuşma veya el yazısı modelini değiştirmez.

## Veriler ve modeller

Notlar, sesler ve transkriptler uygulamanın yerel alanında tutulur. İçe/dışa aktarma geniş depolama izni olmadan Android dosya seçicisini kullanır. Hesap girişi, bulut eşitleme veya not/ses yükleme akışı yoktur. Ağ erişimi isteğe bağlı model indirmeleri içindir. Mikrofon yalnız kullanıcı kayıt başlattıktan sonra açılır.

Konuşma tanıma, depoya eklenmiş **whisper.cpp v1.9.5** ve sabitlenmiş çok dilli **Whisper base** kullanır. Model **147.951.465 bayt**, yaklaşık 148 MB'dır. Ağırlıklar depoya veya APK'ya dahil değildir. İndirme hem boyut hem SHA-256 ile doğrulanır. Sonrasında transkripsiyon internet, API anahtarı veya abonelik olmadan çalışır. Model kaynağı ve lisansları [third_party/WHISPER_SOURCE.md](third_party/WHISPER_SOURCE.md) içinde belgelenir.

El yazısı tanıma, ayrı ve kullanıcı isteğiyle indirilen dil modelleriyle **ML Kit 19.0.0** kullanır. Google istemci kitaplıklarını bağımlılık olarak getirdiği için Google Play Services bağımsızlığı iddia edilmez. Google Play Services uygulaması bulunmayan cihazlarda model indirme ve tanıma ayrıca doğrulanmalıdır.

## Uyumluluk ve sınırlar

- **Android 8.0 / API 26 ve sonrası**, hedef SDK 35. Yapılandırılmış ABI'ler `arm64-v8a`, `armeabi-v7a`, `x86_64` ve `x86`. Kurulum desteği, eski veya düşük bellekli cihazda kullanılabilir transkripsiyon hızı garantisi değildir. Fiziksel S Pen, avuç içi ayrımı, mikrofon, uzun kayıt, pil ve sıcaklık davranışı hedef cihazda ayrıca ölçülmelidir.
- Basınç ve yan düğme desteği donanımın Android'e ilettiği olaylara bağlıdır. Uygulama Samsung kalemini uyumsuz donanımda çalıştıramaz. Üreticiye özel uzaktan kumanda davranışı vaat edilmez.
- Transkripsiyon **kayıt sonrasında** çalışır. Canlı altyazı, konuşmacı ayırma veya çeviri yoktur. Gürültü, uzak mikrofon ve üst üste konuşma hata üretebilir. Yerel ses doğrulaması 99 dilin tamamı için doğruluk ölçümü değildir.
- El yazısı tanıma fotoğraf veya PDF içindeki basılı yazıyı değil, mürekkep çizgilerini okur. PDF metin katmanında arama, fotoğraf OCR'ı, denklem tanıma/LaTeX, kurşun kalem dokusu, AI çalışma aracı, ortak düzenleme ve bulut eşitleme yoktur. Eşitleme kullanıcı tercihiyle ertelenmiştir.
- Referans paneli editörle yer değiştirene kadar salt okunurdur. İkinci bağımsız düzenleme oturumu değildir. Sayfa sınırı hareketi ayrı sayfalar arasında geçer, kesintisiz sayfa akışı oluşturmaz.
- Geri alma, **açık sayfa oturumundaki son 60 düzenlemeyi** tutar ve sayfa değişince sıfırlanır. Düzenlenebilir seçim panosu uygulama sürecine bağlıdır. Süreç kapanırsa seçim yeniden kopyalanmalıdır.
- Özgün PDF baytları korunur. Ekranda ve PDF çıktısında arka plan raster çizildiği için özgün aranabilir PDF metin katmanı çıktıya taşınmaz. İçe alınan görsellerin yönü düzeltilir ve en fazla 4096 piksel kenar / 8.388.608 piksel olacak şekilde PNG'ye dönüştürülür. Özgün görsel baytları saklanmaz. Her panelin yaklaşık dört milyon piksellik çözme bütçesi görsel/PDF önizleme çözünürlüğünü sınırlar.
- Ses biçimi desteği uzantıdan çok Android'in gerçek çözücüsüne bağlıdır. Mikrofon kaydı yaklaşık 64 kbit/s mono AAC/M4A kullanır ve uzun kayıtları kalıcı parçalara ayırır. Süreç sonlandırılırsa son tamamlanmamış parça kaybolabilir. Çok parçalı kayıt üstverisi daha uzun toplam süreye izin verse de çözümlenen tek ses dosyası 24 saati aşamaz.
- Teknik sınırlar **tek PDF için 64 MiB / 2.000 sayfa**, **içe alınan tek ses için 256 MiB**, **el yazısı isteği başına 200.000 mürekkep noktası**, **JSON belgesi başına 32 MiB** ve **açılmış yedek toplamında 512 MiB**. Genel yedek girdisi 64 MiB, ses girdisi 256 MiB, arşiv 50.000 girdiyle sınırlıdır. Bunlar kaynak korumalarıdır, aboneliğe bağlı defter/sayfa kotası değildir.
- V1/V2 belgeler okunur, yeni kayıtlar V3 kullanır. Eski APK'lar V3 okuyamaz. Sürüm değiştirmeden önce uyumlu yedekleri koruyun. Kalıcı silme önce kütüphaneyi günceller, sonra paylaşılmayan dosyaları temizler. Fiziksel temizlik başarısız olursa yedeklere girmeyen, ilişkisiz bir dosya diskte kalabilir. Güvenli fiziksel veri imhası sunulmaz.

## Kaynaktan derleme

Depodaki yapılandırma **JDK 17**, **Gradle 8.13**, **Android SDK 35 / Build Tools 35.0.0**, **NDK 28.2.13676358** ve **CMake 3.22.1** kullanır. İlk bağımlılık indirmesi için internet gerekir. Sabitlenmiş whisper.cpp kaynağı dahildir, CMake motor kaynağını ağdan indirmez.

```powershell
git clone https://github.com/ArdaHFO/defter-android.git
cd defter-android

# JAVA_HOME ve ANDROID_HOME (veya ANDROID_SDK_ROOT) kurulu araçları göstermelidir.
.\build.ps1 -Action Check
.\build.ps1 -Action Build
```

APK, `app/build/outputs/apk/debug/app-debug.apk` konumuna yazılır.

| İşlem | Sonuç |
| --- | --- |
| `Check` | Birim/arayüz testleri ve Android Lint. Betiğin varsayılanıdır. APK üretmez. |
| `Test` | Yerel JVM/Robolectric testleri. APK üretmez. |
| `Native` | Yapılandırılmış dört ABI için C/C++ motor derlemesi. APK üretmez. |
| `Build` | Geliştirme APK'sı ve yoksa yerel geliştirme imzası. |

PowerShell betiği önbellekleri `.local/` altında ayırır ve Windows için kısa geçici yollar kullanır. Özel `-SocketDirectory` yazılabilir ve 85 karakterden kısa olmalıdır. Sonraki geliştirme APK'ları aynı kurulumu güncelleyecekse `.local/debug.keystore` dosyasını özel olarak koruyun. Yeni üretilen anahtar, farklı anahtarla imzalanmış mevcut kurulumla eşleşmez. Anahtarlar ve önbellekler depoya dahil değildir.

Android instrumentation kaynakları `app/src/androidTest` altındadır. Çalıştırmak için ayrı emülatör veya cihaz gerekir ve test APK'ları kurulur. Kaynakların derlenmesi, bu testlerin çalıştırılması anlamına gelmez.

## Doğrulama

**0.5.0-alpha için 3 Kasım 2026 tarihinde tamamlanan doğrulama**, **50 sınıfta 309 başarılı test**, **0 Android Lint hatası / 27 uyarı** ve başarılı Android cihaz testi kaynağı derlemesi kaydeder. Bunlar tamamlanan geliştirme kontrollerinden korunan sonuçlardır, **bu belge güncellemesi için testler yeniden çalıştırılmadı**. README'deki yenilenen İngilizce görseller sentetik arayüz çizimleridir. İşlevsel test, yerel motor, emülatör ve fiziksel cihaz kanıtlarıyla ayrı değerlendirilir.

[Doğrulama kapsamı ve korunan kanıtlar](docs/VALIDATION.md), güncel kontrolleri önceki APK/güncelleme kontrolleri ve tarihsel yerel ses düzeneğinden ayırır. Otomatik test başarısı fiziksel Tab S10 FE/S Pen gecikmesini, mikrofon güvenilirliğini, el yazısı doğruluğunu veya bütün Android cihazlarında transkripsiyon performansını kanıtlamaz. Önceki yerel ses sonuçları yeni 0.5 çıkarım testi olarak sunulmaz.

## Proje haritası

| Yol | Sorumluluk |
| --- | --- |
| `app/src/main/java/dev/defter/app/model/` | Defter, sayfa, ses ve transkript modelleri. |
| `app/src/main/java/dev/defter/app/data/` | Atomik JSON kaydı, içe aktarma, yedekleme ve çakışma kurtarma. |
| `app/src/main/java/dev/defter/app/ink/` | Canvas girdisi, sayfa sınırı hareketleri, çizim, seçim ve geometrik düzenleme. |
| `app/src/main/java/dev/defter/app/ui/` | Compose defter/ses alanları, dil seçimi ve toplu çöp kutusu kontrolleri. |
| `app/src/main/java/dev/defter/app/localization/` | Kalıcı arayüz dili ve Android metin/çoğul kaynakları. |
| `app/src/main/java/dev/defter/app/audio/` | Kayıt, oynatma, çözme, model indirme ve transkripsiyon. |
| `app/src/main/java/dev/defter/app/recognition/` | El yazısı tanıma bağlantısı. |
| `app/src/main/cpp/` | Sabitlenmiş yerel motora JNI bağlantısı. |
| `app/src/test/`, `app/src/androidTest/` | Yerel ve Android cihaz test kaynakları. |
| `third_party/` | Gerekli motor kaynağı, lisanslar ve model bilgileri. |
| `docs/` | Ayrıntılı kılavuz, kapsamı belirtilmiş doğrulama kanıtları ve arayüz görselleri. |

Uygulama şu anda Kotlin, Compose, yerel Android Canvas, atomik JSON dosyaları ve Whisper C/C++ kullanır. Jetpack Ink ve Room bu uygulamaya dahil değildir.

## Katkı

Sorun bildirirken Android sürümünü, cihazı, adımları ve beklenen davranışı belirtin. Örneklerde sentetik defter veya ses kayıtları kullanın. Depolama değişiklikleri mevcut belgeleri ve yedek uyumluluğunu korumalıdır. İlgili yerel kontrolleri bildirin, gerçekten yaptığınız cihaz veya yerel motor kontrollerini ayrıca belirtin.

## Lisans ve teşekkürler

Bu kaynak ağacında Defter'in özgün kodu için depo genelinde bir lisans sağlanmamıştır. Bağımlılıkların lisanslarından uygulama için yeniden kullanım izni çıkarılmamalıdır.

Depoya eklenen [whisper.cpp](https://github.com/ggml-org/whisper.cpp) kaynağının [MIT lisansı](third_party/whisper.cpp/LICENSE), [yazarları](third_party/whisper.cpp/AUTHORS) ve içindeki üçüncü taraf bildirimleri korunmuştur. Whisper modelinin ayrı [MIT lisansı](third_party/WHISPER_MODEL_LICENSE.txt) vardır. Motor ve model bildirimlerinin kopyaları uygulama varlıklarına da dahildir. Kotlin, AndroidX, ML Kit ve diğer bağımlılıklar kendi lisanslarını korur.

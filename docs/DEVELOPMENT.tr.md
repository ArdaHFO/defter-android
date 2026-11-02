# Defter kullanım ve geliştirme kılavuzu

Bu kılavuz 2 Kasım 2026 tarihinde tamamlanan 0.4.0-alpha kaynak açıklamasından uyarlanmıştır. Güncel ürün tanıtımı [Türkçe README](../README.tr.md), doğrulama kapsamı ve korunan sonuçlar [doğrulama özeti](VALIDATION.md) içindedir. Yayın hazırlığında uygulama veya testler yeniden çalıştırılmamıştır.

## Bu sürümün kapsamı

- Klasör ve alt klasör oluşturma, klasör adını değiştirme.
- Defter oluşturma, kapak rengi, yeniden adlandırma, klasöre taşıma, arama.
- Defteri çöp kutusuna taşıma ve geri getirme.
- Sayfa ekleme/çoğaltma, gerçek önizlemeler, basılı tutarak sürükleme veya oklarla sıralama, silme/geri getirme, defterler arasında kopyalama/taşıma. Defterde en az bir aktif sayfa kalır.
- Boş, çizgili, kareli, noktalı ve Cornell kâğıdı. Açık ve koyu kâğıt renkleri.
- Basınç verisi sağlayan kalemlerle değişken kalınlık ve fosforlu kalem. Tam çizgi veya kısmi silgi. Kalem/fosforlu/şekil filtreleri.
- Çizgi, ok, dikdörtgen, elips. İsteğe bağlı 15°/30°/45°/90° hizalama, kare ve çember.
- Tek nesne veya kementle çoklu seçim, grup taşıma/kopyalama/renklendirme/büyütme/küçültme/silme.
- Görsel kırpma/döndürme, oranı koruyarak boyutlandırma. Var olan metnin içeriğini, boyutunu ve rengini düzenleme.
- Hazır renkler, özel HEX renk, kalınlık ayarı.
- Parmakla çizim seçeneği, iki parmakla yakınlaştırma ve gezinme.
- Kalemin yan düğmesine basılı tutarak geçici silgi, bırakınca önceki araca ve aynı renk/kalınlığa dönüş.
- Galeri/dosyadan resim, Android paylaş menüsünden resim/metin, kullanıcı isteğiyle panodan yapıştırma.
- Metin kutusu. İşlem bazında geri al/yinele. Düzenleme ve kırpma taslaklarının ekran döndürülünce korunması.
- Açık defter sekmeleri, defter başına son sayfa, bağımsız gezinilen referans paneli. Referansı düzenlemek için iki panelin yerini değiştirme.
- PDF içe aktarma ve üstüne çizme. Özgün PDF uygulamaya kopyalanır, arka plan silgiden etkilenmez.
- Türkçe/İngilizce el yazısını metne dönüştürme, sonucu düzeltme ve isteğe bağlı özgün mürekkebin yerine koyma. Tanınmış yazı ve metin kutularında kütüphane araması.
- Otomatik yerel kayıt ve görünür kayıt durumu. Eski editörün yeni kaydı ezmesini önleyen sürüm kontrolü ve kaydedilemeyen değişiklikleri ayrı kopya olarak kurtarma.
- Defterin PDF çıktısı. Bütün kütüphaneyi düzenlenebilir yedek olarak kaydetme ve birleştirerek geri yükleme.
- Defter içinde veya Dosyalar/klasörlerde bağımsız ses kaydı, duraklatma/devam/durdurma ve harici ses içe aktarma.
- Not yazarken görünür kayıt çubuğu, zaman işareti ve not sayfasına bağlantı. Kayıtları deftere bağlama/ayırma, klasöre taşıma, adlandırma ve çöp kutusundan geri getirme.
- Ses oynatma, konuma atlama ve 0,5–2× hız. Türkçe, İngilizce veya otomatik dil seçimiyle cihaz üzerinde Whisper transkripsiyonu.
- Transkriptte arama, zaman damgasına dokunarak dinleme, metin düzeltme, açık sayfaya metin ekleme ve TXT/SRT çıktısı. Sesler ve transkriptler yerel yedeğe dahildir.

## Kullanım

1. **Yeni klasör** düğmesiyle klasör oluşturabilirsiniz. Klasöre girip **Yeni defter** seçerseniz defter o klasörün içine kaydedilir. Aynı yerde başka defterler veya alt klasörler de oluşturabilirsiniz. Defter oluştururken isim, kapak ve ilk sayfa şablonunu seçin.
2. Kalemle yazın. Aktif kalem kullanırken **Parmakla yaz** seçeneğini kapatırsanız parmakla sayfayı gezebilirsiniz. Parmak gibi algılanan pasif kalemler için bu seçenek açık olmalı.
3. Üst araç çubuğundan kalem, fosforlu, çizgi silgisi veya şekil seçin. Araç çubuğu dar ekranlarda yatay kayar.
   Kalemin yan düğmesine basılı tutunca silgi devreye girer ve “Yan düğme · Silgi” göstergesi görünür. Bırakınca seçili araca dönersiniz. Düğme çizim sırasında da kullanılabilir. Kalem ekrana temas etmeden yalnızca düğmeye basmak sayfayı değiştirmez.
   Üstteki **Geri al** son düzenlemeyi kaldırır, **Yinele** geri alınan düzenlemeyi tekrar uygular. İşlem yoksa düğme pasiftir. Çok dar ekranlarda veya büyük sistem yazısında düğmeler başlığın altına geçer. Geri aldıktan sonra yeni bir düzenleme yaparsanız eski ileri alma kolu temizlenir.
4. Seç/taşı aracıyla bir nesneye dokunup sürükleyin. Altta açılan kontroller nesneyi büyütür, küçültür veya siler.
5. Üç nokta menüsünden resim/metin veya PDF ekleyin, panodan yapıştırın, kâğıdı değiştirin veya PDF alın. Kementle bir grubu seçtiğinizde alt çubuktan kopyalayabilir, renklendirebilir ve silebilirsiniz. Tek görsel/metin seçildiğinde özel düzenleme düğmeleri görünür.
6. Alttaki sayfa numarası sayfa yöneticisini açar. Kartı basılı tutup sürükleyerek veya oklarla sıralayın. Kart menüsü silme/taşıma/kopyalamayı, Silinenler sekmesi geri getirmeyi sağlar. Sağ alttaki düğme geçerli sayfanın arkasına yeni sayfa ekler. Yüzde düğmesi sayfayı genişliğe sığdırır.
7. Kütüphane menüsünden tam yedek alın. `.defter` dosyası sürümlü, SHA-256 kontrollü bir ZIP paketidir. Yedeği cihazınız dışında da saklayın.
8. Üstteki sekme artısı başka bir defter açar. Menüdeki **Yan yana aç** referans panelini açar. Referans üzerinde kaydırma/yakınlaştırma yapılır. İki ok düğmesi referansı düzenleme tarafına alır. Telefonda paneller üst üste yerleşir.
9. **El yazısı ve arama** bölümünden dili seçip **Dil modelini indir** düğmesine basın. Sonra **El yazılarını aramaya hazırla** ile kütüphaneyi dizinleyin. Yeni mürekkep eski tanıma sonucunu geçersiz kılar, dizini yeniden güncelleyin. Editör menüsündeki **El yazısını metne çevir** seçili mürekkebi veya bütün sayfayı okur. Sonucu kontrol edip uygulayın.
10. Kütüphanedeki **Sesler** ile bağımsız arşivi açın. Bir klasörün içindeyseniz yeni sesler o klasöre eklenir. **Kayıt başlat** mikrofon izni ister. **Ses içe aktar** Android dosya seçicisini açar. Defter açıkken mikrofon simgesi veya **Ses kaydı ve transkript** menüsü o defterin seslerini açar.
11. İlk transkripsiyonda **Konuşma modelini indir** seçeneğiyle yaklaşık 148 MB çok dilli modeli indirin. İndirme kullanıcı isteğiyle yapılır, SHA-256 ile doğrulanır ve yarıda kesilirse sürdürülebilir. Model hazır olduktan sonra internet gerekmez, ses ve transkript sunucuya gönderilmez. API anahtarı ve abonelik gerekmez.
12. Kaydı bitirdikten sonra **Metne çevir** düğmesine basın. İlerleme bildirimde görünür. Duraklatıp tamamlanan bölümden devam edebilirsiniz. Metindeki kalem simgesiyle hataları düzeltin. Dili değiştirip baştan işlemek mevcut transkripti değiştirir ve ayrıca onay ister.

**Silgi seçenekleri** tam çizgi ve kısmi silmeyi ayrı sunar. Kısmi silgi vektör çizgiyi parçalara ayırır, tek noktalı izleri bütün olarak kaldırır. Metin, görsel ve PDF arka planını silmez. Silgi filtresi kalemin yan düğmesinde de kullanılır.

## Android desteği ve sınırlar

- Minimum Android 8.0 / API 26. Derleme ve hedef SDK 35.
- Samsung SDK'sı veya Google hesabıyla oturum açma gerekmez. ML Kit 19.0.0, `play-services-base`, `play-services-basement` ve `play-services-tasks` gibi Google istemci kitaplıklarını bağımlılık olarak getirir. Bu nedenle Google Play Services bağımlılığı olmadığı söylenemez. Google Play Services uygulaması bulunmayan cihazlarda gerçek model indirme ve tanıma ayrıca doğrulanmalıdır.
- Notlar ve sesler yerel saklanır. Geniş depolama izni yoktur. Dosyalar Android dosya seçicisiyle alınır. INTERNET izni el yazısı ve konuşma modellerinin indirilmesi içindir. Uygulamada bulut eşitleme bağlantısı veya not/ses yükleme iş akışı yoktur. Mikrofon yalnızca kullanıcı kayıt başlattığında açılır, kayıt boyunca ön plan hizmeti bildirimi kullanılır.
- El yazısı tanıma ML Kit 19.0.0 kullanır. Model indirildikten sonra cihaz üzerinde çalışır. Fotoğraflar veya PDF içindeki basılı metin için OCR değildir. Otomatik testler SDK'ya hazırlanan mürekkebi ve kontrollü tanıyıcı yanıtlarıyla arayüz/dizinleme akışlarını doğrular. Gerçek model indirmesi ve Türkçe/İngilizce tanıma doğruluğu fiziksel cihazda ölçülmedi. Matematik araçları bu sürümde geometrik çizim içindir, el yazısı denklemi LaTeX'e çevirme veya çözme içermez.
- Eski V1/V2 belgeler okunur ve kayıtta V3 kullanılır. Eski APK V3 belgeleri okuyamaz. Eski sürüme dönmeden önce uygun kaynak sürümünü ve yedekleri koruyun.
- Kalem basıncı ve avuç içi davranışı cihazın bildirdiği olaylara bağlıdır. Samsung kaleminin başka üreticinin donanımında çalışması uygulamanın sağlayabileceği bir özellik değildir.
- Yan düğme desteği Android'in standart kalem düğmesi olaylarını kullanır. Cihazın bu olayları uygulamaya iletmesi gerekir. Fiziksel S Pen düğmesi ve üreticiye özel uzaktan kumanda davranışları bu kaynak güncellemesinde cihaz üzerinde doğrulanmadı.
- Ekran dönünce mevcut düzenleme ViewModel üzerinden korunur. Uygulama yeniden açıldığında tamamlanmış yerel kayıt yüklenir.
- Geri alma geçmişi o sayfanın açık oturumunda son 60 düzenlemeyle sınırlıdır. Sayfa değişince geçmiş sıfırlanır.
- İçeri alınan resimler EXIF yönü uygulanarak PNG olarak saklanır. Bellek kullanımı için en fazla 4096 piksel kenar ve 8.388.608 piksel toplam çözünürlüğe küçültülür. Özgün dosya baytları ayrıca saklanmaz.
- Çizim için her panel yaklaşık 4 milyon çözülmüş görüntü pikselini paylaşır. Çok görselli sayfalarda önizleme çözünürlüğü düşer, saklanan görseller değişmez. PDF'nin özgün baytları korunur. Ekranda ve PDF dışa aktarımında PDF arka planı raster olarak çizilir, özgün PDF metin katmanı çıktıya aktarılmaz.
- Bozuk veya desteklenmeyen sürümdeki belgeler boş defter gibi açılmaz. Hata gösterilir.
- Teknik koruma sınırları JSON dosyasında 32 MiB, genel yedek girdisinde 64 MiB, ses girdisinde 256 MiB ve açılmış yedek toplamında 512 MiB'dır. Bunlar defter/sayfa adet kotası değildir. Sesli büyük kütüphanelerde yedek toplam sınırını dikkate alın.
- Tek ses içe aktarma 256 MiB ile sınırlıdır. Biçim desteği cihazın Android medya çözücüsüne bağlıdır. Dosya adı uzantısı tek başına yeterli sayılmaz, gerçek ses izi ve süre doğrulanır. AAC/M4A kaydı yaklaşık 64 kbit/s kullanır. Uzun kayıtta Android'in yerel sonraki dosya mekanizması kullanılır, tamamlanan parçalar kalıcılaştırılır. Süreç sonlandırılırsa tamamlanmamış son parça kaybolabilir veya kurtarılamayabilir, kullanıcıya hata gösterilir.
- Ses modeli `whisper.cpp` v1.9.5 ve çok dilli Whisper `base` modelidir. Transkripsiyon kayıt bittikten sonra yapılır. Canlı altyazı, otomatik konuşmacı ayırma ve çeviri yoktur. Gürültü, uzak mikrofon ve karışık konuşma tanıma hatalarına yol açabilir. Sonuçlar düzenlenebilir. Fiziksel cihazda Türkçe doğruluk, uzun ders süresi, pil ve sıcaklık ölçümü yapılmadı.
- Tek PDF içe aktarma 64 MiB ve 2000 sayfayla sınırlıdır. Tek el yazısı tanıma isteği en fazla 200.000 mürekkep noktası alır. Çok yoğun yazılarda daha küçük bir bölge seçin.
- Henüz fiziksel Samsung kalem gecikmesi, mikrofonla uzun ders oturumu veya bütün üreticilerde uyumluluk doğrulanmış değildir. Güncel kaynak doğrulaması [0.4.0 doğrulama özeti](VALIDATION.md), önceki ses doğrulaması [tarihsel 0.3.0 ses doğrulaması](VALIDATION.md#historical-native-audio-evidence) dosyasındadır.

## Henüz bulunmayan özellikler

Bulut eşitleme kullanıcı tercihiyle ertelendi. Ortak düzenleme, PDF metin katmanında arama, fotoğraf OCR'ı, kalem/kurşun kalem dokuları, matematik OCR/LaTeX ve AI çalışma yardımcısı bu sürümde yoktur. Referans paneli salt okunurdur, panel değiştirilerek düzenlenir. Her iki tarafta aynı anda bağımsız düzenleme oturumu yoktur.

## Derleme

Gerekenler JDK 17, Android SDK platform 35, Build Tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1 ve ilk bağımlılık indirmesi için internet bağlantısıdır. Gradle Wrapper sürümü 8.13'tür. Whisper kaynağı pakete sabitlenmiş olarak eklenmiştir, CMake kaynak indirmez. Model ağırlıkları kaynak paketine dahil değildir. Yerel motor `arm64-v8a`, `armeabi-v7a`, `x86_64` ve `x86` için derlenir. Eski veya düşük bellekli cihazlarda kurulum desteği, transkripsiyonun kullanışlı hızda çalışacağı garantisi değildir.

Windows PowerShell:

```powershell
.\build.ps1 -Action Check
```

`Build` APK üretir. `Test` yerel JVM/Robolectric testlerini, varsayılan `Check` test + Android Lint kontrollerini çalıştırır. `Native` yalnızca dört ABI için C/C++ motorunu derler. `Test`, `Check` ve `Native` APK üretmez. `JAVA_HOME` ve `ANDROID_HOME` (veya `ANDROID_SDK_ROOT`) kurulu araçları göstermelidir.

Betik proje içindeki `.local` altında Gradle/Android önbelleği kullanır ve yalnızca `Build` için eksik geliştirme imzasını oluşturur. Windows AF_UNIX yol sınırı için kısa bir geçici dizin kullanır. Gerekirse `-SocketDirectory` ile yazılabilir ve 85 karakterden kısa bir yol belirtin. Ortam değişiklikleri yalnızca betiğin süreci boyunca geçerlidir.

APK `app/build/outputs/apk/debug/app-debug.apk` konumuna yazılır. Bu geliştirme imzalı APK, mağaza sürümü değildir. Aynı uygulamayı veri koruyarak güncelleyebilmek için `.local/debug.keystore` dosyasını koruyun. Kaynak paketinden yeni imza üretirseniz mevcut APK ile aynı imza olmaz.

PDF içe aktarma ve dışa aktarma cihaz testleri `app/src/androidTest` altındadır. Robolectric 4.14.1 yerel PDF motorunu uçtan uca doğrulamadığı için bu senaryolar Android emülatörü veya fiziksel cihaz gerektirir. Testler mevcut defterleri silmeden ayrı doğrulama verileri oluşturur. Bu kaynak güncellemesinde cihaz testleri derlendi, cihazda çalıştırılmadı. Aşağıdaki komutlar ayrıca APK üretir ve kurulum yapar.

Önceki 0.2.0 doğrulamasında APK üretmeyen bağımsız DEX test düzeneğiyle Android 15 emülatöründe gerçek PDF motoruna karşı 9 senaryo doğrulandı. Bu tarihsel sonuç ayrı bir kanıttır, instrumentation testlerinin veya bütün uygulamanın cihazda çalıştırılması yerine geçmez. Ayrıntılar özgün yerel paketteki `verification/PRO_UPGRADE.md` içindedir. 0.3.0 sürümüne ait gerçek ses testleri [tarihsel 0.3.0 ses doğrulaması](VALIDATION.md#historical-native-audio-evidence) içinde raporlanır.

```powershell
.\build.ps1 -Action Build
.\gradlew.bat :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class dev.defter.app.PdfExportTest dev.defter.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class dev.defter.app.PdfImportTest dev.defter.app.test/androidx.test.runner.AndroidJUnitRunner
```

Bu komutları geliştirme için ayrılmış bir cihazda kullanın. Windows geçici yol sorunu yaşanırsa `build.ps1` içindeki kısa soket dizini ayarı aynı Gradle sürecine uygulanmalıdır. Test çıktısı uygulamanın `files/verification` dizinine yazılır.

## Kaynak düzeni

- `model/Document.kt` sürümlü belgenin platformdan bağımsız veri modeli.
- `data/DocumentRepository.kt` sıralı IO, kayıt çakışması denetimi, PDF/görsel içe aktarma, sayfa yönetimi, yedekleme ve geri yükleme.
- `data/AtomicSnapshotFile.kt` dosya eşitleme ve denetlenen atomik değiştirme. Kayıt hataları kullanıcıya iletilir.
- `data/DocumentJson.kt` açık, doğrulamalı JSON dönüştürücü.
- `ink/NotebookCanvas.kt` geçici kalem hareketleri, dokunma ayrımı ve belge koordinatları.
- `ink/PageRenderer.kt` ekran ve PDF için ortak çizim.
- `ink/ElementGeometry.kt` nesne sınırları, seçim ve taşıma.
- `DefterViewModel.kt` otomatik kayıt kuyruğu, geri alma ve ekran işlemleri.
- `ui/DefterApp.kt` Compose arayüzü.
- `ui/DefterTheme.kt` ortak renk, yazı ve köşe biçimleri ile uygulama içi marka işareti.
- `ui/ProWorkspace.kt` sekmeler, referans paneli, içerik araması ve tanıma arayüzü.
- `ui/ProEditorDialogs.kt` gelişmiş silgi, geometri, nesne düzenleme ve sayfa yönetimi kontrolleri.
- `recognition/HandwritingRecognizer.kt` açık model indirme ve cihaz üzerinde ML Kit el yazısı tanıma bağlantısı.
- `audio/AudioViewModel.kt` ses arşivi, defter bağlantısı, oynatma ve transkript işlemleri.
- `audio/AudioRecorderService.kt` mikrofon ön plan hizmeti, parça kalıcılaştırma ve kurtarma.
- `audio/AudioTranscriptionService.kt` duraklatılabilir yerel transkripsiyon, sıcaklık ve Android süre sınırı yönetimi.
- `audio/AudioDecoder.kt`, `WhisperTranscriber.kt`, `WhisperModelManager.kt` sınırlı bellekli ses çözme, gerçek JNI motoru ve doğrulanmış model indirme.
- `app/src/main/cpp` JNI bağlantısı, `third_party/whisper.cpp` sabitlenmiş MIT lisanslı motor kaynağı. Lisanslar uygulama varlıklarına da eklenmiştir.
- `ui/AudioWorkspace.kt` bağımsız ve deftere bağlı ses ekranları.

Bu 0.4.0-alpha kaynak sürümü **Kotlin + Compose + yerel Canvas + atomik JSON dosyaları + Whisper C/C++** kullanır. İlk plandaki Jetpack Ink ve Room entegrasyonu henüz yapılmamıştır. Çizim yüzeyi ve depolama arayüzü ayrı tutulduğundan bu seçenekler bağımsız değerlendirilebilir. Gerçek kalem gecikmesi ve yoğun sayfalardaki performans için cihaz ölçümleri gerekir.

## Sonraki geliştirme sırası

1. Gerçek tablette kalem düğmesi, avuç içi, uzun ders oturumu ve düşük bellek koşullarını doğrulama.
2. Gerçek Türkçe/İngilizce model indirme, çevrimdışı tanıma ve farklı el yazılarıyla doğruluk değerlendirmesi.
3. PDF içe/dışa aktarma cihaz testlerini çalıştırma, yoğun PDF ve görselli sayfalarda bellek/kare süresi ölçümü.
4. Farklı Android sürümlerinde süreç sonlandırma, ses kaydı kurtarma ve V1/V2/V3 belge geçişlerini fiziksel cihazda doğrulama.
5. Cihaz ölçümlerine göre çizim motoru optimizasyonu ve gerekirse Jetpack Ink karşılaştırması. Yeni ürün özellikleri kullanıcı tercihine göre ayrıca kapsamlandırılır.

Temel Android başvuru belgeleri:

- [Gelişmiş kalem olayları](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)
- [Kalem düğmeleri ve MotionEvent](https://developer.android.com/reference/android/view/MotionEvent#BUTTON_STYLUS_PRIMARY)
- [Dosya seçicisi ve kalıcı erişim](https://developer.android.com/training/data-storage/shared/documents-files)
- [Ink sürüm notları](https://developer.android.com/jetpack/androidx/releases/ink)
- [AGP 8.9 uyumluluk bilgileri](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
- [ML Kit el yazısı tanıma ve model indirme](https://developers.google.com/ml-kit/vision/digital-ink-recognition/android)
- [ML Kit 19 Ink API başvurusu](https://developers.google.com/android/reference/com/google/mlkit/vision/digitalink/recognition/Ink)
- [El yazısı modellerinin desteklediği diller](https://developers.google.com/ml-kit/vision/digital-ink-recognition/base-models)
- [AndroidX ExifInterface 1.4.2 sürüm kaydı](https://developer.android.com/jetpack/androidx/releases/exifinterface#1.4.2)


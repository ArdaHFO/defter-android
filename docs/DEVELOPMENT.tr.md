# Defter 0.8 kullanım ve geliştirme rehberi

14 Kasım 2026. [Bütün özellikler](../README.tr.md), [İngilizce rehber](../README.md) ve [doğrulama kapsamı](VALIDATION.md) birlikte günceldir. Varsayılan arayüz İngilizcedir. App language içinden Türkçe seçilebilir. Arayüz dili, not içeriğini veya tanıma modelinin dilini değiştirmez.

1. Kütüphanede klasör açın ve içine defter veya alt klasör ekleyin. Defter kartının ayrı seçenek düğmesi yeniden adlandırma, taşıma ve çöp kutusuna erişir. Görünüm bölümünden ızgara/liste seçin.
2. Parmak taşıma modunda gezinir, kalem seçili araçla yazar. Parmak modu düğmesi parmakla çizimi açıp kapatır. Kalemin Android tarafından bildirilen yan düğmesine basılı tutmak geçici silgidir. Bırakınca önceki araca dönülür.
3. Üstteki tek araç satırı geçmiş, çizim ve ekleme eylemlerini tutar. Renk düğmesinde kalem için renk/kalınlık/örnek çizgi, silgide silme biçimi/filtre/boyut görünür. Uzun basma ve desteklenen kalem beklemesi açıklama gösterir.
4. Sayfalar kesintisiz kayar. Komşu sayfaları aynı anda görün. Son sayfanın altından çekip bırakmak tek yeni sayfa ekler. Sayfa numarası yöneticiyi açar. Burada sıralama, çoğaltma, silme/geri getirme ve başka deftere kopyalama/taşıma bulunur.
5. Alt çubuktaki harita simgesi küçük sayfa haritasını açar. Bir önizlemeye dokunun veya kaydırıcıyı bırakın. Tam ekran simgesi odak moduna geçer. Geri tuşu veya sağ üstteki çıkış düğmesi çubukları geri getirir. Aktif kayda erişim korunur.
6. Görünüm içinden açık/koyu/sistem teması, sayfa/ses paneli genişliği ve referans oranı seçin. Değişiklikler Uygula ile kaydedilir. Tema kâğıt ve mürekkebi değiştirmez. Sekme artısı başka bir defteri, yan yana aç eylemi referansı açar. Dar ekranda paneller üst üste uyarlanır.
7. Kementle grup seçin veya seçim aracıyla nesneye dokunun. Taşıma, kopyalama, renklendirme, ölçekleme ve silme yapılabilir. Tek görsel kırpılır/döndürülür, tek metin düzenlenir. Parmak taşıma modunda uzun basma pano menüsü açar.
8. PDF içe aktarın, üzerine yazın ve defteri PDF olarak paylaşın. PDF arka planı silgiden etkilenmez. Metin ve görseller Android paylaşımı, dosya seçici ve kullanıcı isteğiyle panodan alınabilir.
9. Kütüphanedeki Sesler bağımsız kayıtları açar. Defter sesleri yalnızca o deftere bağlı kayıtları gösterir. Mikrofon izniyle kayıt başlatılır, duraklatılır ve sürdürülür. Harici ses alınabilir. Not yazarken zaman işareti/sayfa bağlantısı eklenebilir.
10. Konuşma modelini isteğe bağlı bir kez indirin. Tamamlanan kaydı Türkçe/İngilizce/Otomatik seçimiyle cihazda metne çevirin. Modelin kapsamı 99 dildir, fakat arayüzde 99 ayrı dil seçimi ve bu dillerin tümü için doğruluk garantisi yoktur. Transkripti kontrol edin, düzeltin, zaman damgasından dinleyin, nota ekleyin veya TXT/SRT alın.
11. El yazısı için Türkçe/İngilizce modeli indirin. Seçili çizgiyi veya sayfayı metne dönüştürüp sonucu düzeltin. Kütüphane araması için dizinleyin ve yeni yazıdan sonra dizini güncelleyin. Bu özellik fotoğraf/PDF OCR veya denklem tanıma değildir.
12. Düzenlenebilir `.defter` yedeğini cihaz dışında saklayın. Yedeğe ses ve transkriptler dahildir. Geri yükleme mevcut içerikle birleştirir. Çöp kutusunda çoklu seçim ve kalıcı silme onayı bulunur. Etkilenen aktif kayıt/transkripsiyon önce durdurulmalıdır.

## Derleme

JDK17, Gradle8.13, SDK35/Build Tools35.0.0, NDK28.2.13676358 ve CMake3.22.1 kullanılır. Kök dizinde `./build.ps1 -Action Check` test/lint, `-Action Build` geliştirme APK'sı üretir. İlk bağımlılık indirmesi internet ister. Özel anahtar `.local` altında tutulur ve repoya konmaz. Mevcut kurulumu güncellemek için aynı imza korunmalıdır.

393 test ve 13 native test 13 Kasım geliştirme doğrulamasıdır. Bu yayın ayrıca üç İngilizce önizleme kontrolünü çalıştırır ve kaynak özetlerini karşılaştırır. Fiziksel cihaz gecikmesi, uzun mikrofon kullanımı ve tanıma doğruluğu ayrıca sınanmalıdır. Kullanıcı tercihiyle çalışma yereldir, hesap ve bulut eşitleme arayüzü yoktur.

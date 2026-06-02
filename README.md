# 🚀 VeloShare – Yüksek Hızlı Çevrimdışı Dosya Paylaşım Teknolojisi

VeloShare, internet bağlantısına (hücresel veri veya harici genişbant modem) ihtiyaç duymaksızın, cihazlar arasında yerel Wi-Fi ağları, kişisel erişim noktaları (Hotspot) veya manuel soket tanımlamaları üzerinden milisaniyeler düzeyinde ultra hızlı ve güvenli dosya aktarımı sağlayan premium bir Android uygulamasıdır.

Uygulama, görsel açıdan **Material Design 3** standartlarında modern **Slate Dark** teması, yumuşak mikro animasyonlar, gelişmiş QR kod tarama altyapısı, profil fotoğrafı özelleştirmesi ve adım adım çevrimdışı rehberiyle bütünsel bir kullanıcı deneyimi sunar.

---

## 📸 Uygulama Görselleri & Ekran Tanıtımları

*Aşağıdaki alanlara kendi hazırladığınız ekran görüntülerini ekleyerek dökümantasyonu görselleştirebilirsiniz.*

### 1. Karşılama ve Canlı Splash Ekranı
Yumuşak grafik ölçekleme, sonsuz dairesel dalga geçişleri ve akıcı rotasyon efektleriyle bezenmiş şık bir açılış deneyimi.
> `![Açılış Ekranı](assets/screenshots/splash_screen.png)

### 2. Ana Kontrol Paneli ve Radar Keşif İstasyonu
Ağdaki diğer aktif VeloShare noktalarını gerçek zamanlı mDNS dalgaları üzerinden tarayan ve dinamik radar animasyonuyla görselleştiren ana arayüz.
> `![Ana Panel](assets/screenshots/main_dashboard.png)

### 3. İnternetsiz Çevrimdışı Aktarım Rehberi
Altyapı bağımsız eşleşmelerde kullanıcıya kılavuzluk eden, genişleyebilir ve daraltılabilir şık bir bilgilendirme kartı.
> `![Çevrimdışı Rehber](assets/screenshots/offline_guide.png)

### 4. Gelişmiş Ayarlar ve Profil Fotoğrafı Yönetimi
Kullanıcıların ağda görünecek takma adlarını ve galeriden seçerek yerel depolama alanına (`SharedPreferences` ve dosya sistemi) kaydettikleri profil fotoğraflarını yönettikleri alan.
> `![Ayarlar](assets/screenshots/settings_dialog.png)

---

## ✨ Öne Çıkan Gelişmiş Özellikler

### 1. Sıfır Altyapı Güvencesi: Yerel Ağ Aktarımı
*   **Mobil Erişim Noktası (Hotspot) Uyumluluğu:** Herhangi bir hücresel internet paketiniz aktif olmasa dahi cihazlardan birinde kişisel erişim noktasını açıp diğerini bu yerel ağa bağlayarak saniyede onlarca megabayt (MB/s) hızla veri transferi sağlayabilirsiniz.
*   **Çevrimdışı Rehber Entegrasyonu:** Adım adım kılavuz sayesinde teknik bilgi gerektirmeden ağ eşleştirmesi yapmanızı kolaylaştırır.

### 2. QR Kod ile Hızlı ve Temassız Eşleştirme
*   Soket bağlantılarını (IP adresi ve Port) manuel yazmak yerine, alıcı cihazın ekranda dinamik oluşturduğu QR kodu, gönderici cihazın **CameraX** tabanlı entegre QR tarayıcısı ile anında okutarak saniyeler içinde bağlantıyı kurabilirsiniz.

### 3. Gelişmiş Profil Fotoğrafı ve Kimlik Yönetimi
*   Ağdaki karmaşayı önlemek için kullanıcılar galeri kaynaklı diledikleri görseli profil fotoğrafı yapabilirler.
*   Görseller veritabanına ve yerel dosya dizinine (`avatar.png`) kopyalanarak uygulamayı kapatıp açtığınızda bile kalıcılığını korur.
*   Profil fotoğrafı olmayan cihazlar için dinamik renk geçişli (Gradient) harf logolu avatarlar otomatik üretilir.

### 4. Kategori Bazlı Dosya ve Uygulama Dünyası
*   **Uygulamalar:** Yüklü sistem uygulamalarını ve kullanıcı uygulamalarını listeler, bunları doğrudan `.apk` paket formatı güvencesiyle paketleyip ağ üzerinden gönderebilir.
*   **Medya Galerisi:** Fotoğraflar, videolar, müzikler ve genel belgeler kendi kategorilerinde listelenerek kullanıcıya mükemmel bir seçici arayüz sunar.

---

## 🛠️ Teknik Mimari ve Kod Tasarımı

Uygulamanın mimari hiyerarşisi clean code prensiplerine uygun, reaktif ve tamamen asenkron (Coroutine & StateFlow) bir formatta oluşturulmuştur:

### 🔬 Ana Bileşenler

1.  **`ShareViewModel.kt` (Merkezi Yönetim İstasyonu):**
    *   Tüm keşif süreçlerini, ağ bağlantılarını ve dosya kuyruklarını kontrol eder.
    *   `getSharedPreferences` aracılığıyla kullanıcı profil resminin URI adresini ve cihaz adını kalıcı olarak tutar.
    *   Uygulamalar listelenirken veya asenkron veri akışlarında `viewModelScope` ile arka plan thread'lerini yönetir.

2.  **`TransferEngine.kt` (Yüksek Performanslı TCP Motoru):**
    *   Düşük seviyeli TCP soket mimarisi kullanarak bloklanmayan (non-blocking) veri akışı sunar.
    *   Çok parçalı dosya transferlerinde dinamik tampon (buffer) boyutu ayarlar ve aktarım yüzdesini milisaniyelik hassasiyetle hesaplar.

3.  **`AppUi.kt` (Dinamik Material 3 Kullanıcı Arayüzü):**
    *   Tamamen Jetpack Compose ile yazılmış olup renk şeması `Slate900` ve `Slate50` zeminleri üzerine nefis neon ve siber vurgularla (CyberCyan, Slate600) donatılmıştır.
    *   **`ProfileAvatar` Bileşeni:** StateFlow'u dinleyerek her an güncellenen, tıklanabilir dokunma alanlarına (48dp+) sahip, animasyonlu profil resmi kartıdır.
    *   **Canlı `SplashScreen`:** Uygulamanın açılışında, tescilli dairesel rotasyon, ölçeklenme ve alpha (titreşimli gradyan) animasyonlarıyla marka algısını pekiştirir.

4.  **`NsdHelper.kt` (Yerel Ağ Servis Keşfi):**
    *   **Network Service Discovery** (NSD) altyapısı sayesinde aynı ağdaki tüm VeloShare kullanıcılarını otomatik olarak tarar, bulur ve IP/Port bilgilerini dinamik olarak listeye ekler.

---

## 🚀 Kurulum ve Yerel Çalıştırma Rehberi

Uygulamayı Android Studio üzerinde derlemek ve çalıştırmak için aşağıdaki adımları takip edebilirsiniz:

1.  **Projeyi Klonlayın veya İnceleyin:**
    ```bash
    git clone <depo_adresi>
```
2.  **Android Studio ile Açın:**
    *   Android Studio Jellyfish / Koala veya daha yeni bir sürüm tavsiye edilir.
    *   İşletim sistemi olarak Android 8.0 (API 26) ve üzerini destekler.
3.  **Bağımlılıkları Eşitleyin (Gradle Sync):**
    *   Projedeki `libs.versions.toml` (Version Catalog) dosyasındaki bağımlılıklar otomatik algılanacaktır.
    *   Kamera entegrasyonu için `androidx.camera.lifecycle`, görsel yüklemeler için `io.coil-kt:coil-compose` kütüphaneleri optimize edilmiştir.
4.  **Derleyin ve Başlatın (Build & Run):**
    *   Cihazı bilgisayara bağlayın (veya emülatör başlatın) ve `Run 'app'` butonuna tıklayın.

---

## ⚙️ Çevrimdışı Aktarım Nasıl Yapılır? (Hızlı Kılavuz)

İnternetsiz bir ortamda dosya aktarımı gerçekleştirmek için yerleşik rehberimizdeki şu adımları izleyin:

*   **Adım 1:** Gönderici veya Alıcı cihazlardan birinde **Mobil Etkin Nokta (Hotspot)** özelliğini açın. *(Mobil veri paketinizin açık olması gerekmez.)*
*   **Adım 2:** Diğer cihazla bu kurulan yerel Wi-Fi ağına bağlanın.
*   **Adım 3:** Her iki cihazda da **VeloShare** uygulamasını açın.
*   **Adım 4:** Gönderici cihazdaki **QR Kod Tarayıcıyı** açıp, Alıcı cihazdaki QR kodu okutun ya da manuel IP alanından alıcının IP adresini yazarak aktarımı anında tetikleyin!

---

💡 *VeloShare, internet bağımlılığını ortadan kaldırarak her zaman, her yerde en hızlı ve en güvenli dosya paylaşım yol arkadaşınız olmak için tasarlandı!*

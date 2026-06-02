# 🚀 VeloShare – Yüksek Hızlı Çevrimdışı Dosya Paylaşım Teknolojisi

VeloShare, internet bağlantısına (hücresel veri veya harici genişbant modem) ihtiyaç duymaksızın, cihazlar arasında yerel Wi-Fi ağları, kişisel erişim noktaları (Hotspot) veya manuel soket tanımlamaları üzerinden milisaniyeler düzeyinde ultra hızlı ve güvenli dosya aktarımı sağlayan premium bir Android uygulamasıdır.

Uygulama, görsel açıdan **Material Design 3** standartlarında modern **Slate Dark** teması, yumuşak mikro animasyonlar, gelişmiş QR kod tarama altyapısı, profil fotoğrafı özelleştirmesi ve adım adım çevrimdışı rehberiyle bütünsel bir kullanıcı deneyimi sunar.

---

## 📸 Uygulama Görselleri & Ekran Tanıtımları


### 1. Karşılama ve Canlı Splash Ekranı
Yumuşak grafik ölçekleme, sonsuz dairesel dalga geçişleri ve akıcı rotasyon efektleriyle bezenmiş şık bir açılış deneyimi.
> <img width="500" height="600" alt="splash_screen" src="https://github.com/user-attachments/assets/b8b8ed3d-1c28-4b04-beab-ebd4d04f5580" />


### 2. Ana Kontrol Paneli ve Radar Keşif İstasyonu
Ağdaki diğer aktif VeloShare noktalarını gerçek zamanlı mDNS dalgaları üzerinden tarayan ve dinamik radar animasyonuyla görselleştiren ana arayüz.
> <img width="500" height="600" alt="main_dashboard" src="https://github.com/user-attachments/assets/28161809-4204-40e0-b09e-fcfc13a9bb47" />

### 3. İnternetsiz Çevrimdışı Aktarım Rehberi
Altyapı bağımsız eşleşmelerde kullanıcıya kılavuzluk eden, genişleyebilir ve daraltılabilir şık bir bilgilendirme kartı.
> <img width="500" height="600" alt="offline_guide" src="https://github.com/user-attachments/assets/be8b6a06-0d65-4a77-801b-7e174be84b8a" />

### 4. Gelişmiş Ayarlar ve Profil Fotoğrafı Yönetimi
Kullanıcıların ağda görünecek takma adlarını ve galeriden seçerek yerel depolama alanına (`SharedPreferences` ve dosya sistemi) kaydettikleri profil fotoğraflarını yönettikleri alan.
> <img width="500" height="600" alt="settings_dialog" src="https://github.com/user-attachments/assets/de6e1651-6df6-4546-8b44-b6610212e9b2" />

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

[📥 Uygulamayı Doğrudan İndirmek İçin Tıklayın](https://github.com/FOXYorj/VeloShare/blob/main/VeloShare.apk)


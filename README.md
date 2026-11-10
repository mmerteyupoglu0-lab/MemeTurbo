# ⚡ MemeTurbo - Akıllı Sohbet İçi Meme Arama Motoru

WhatsApp, Instagram ve Telegram gibi mesajlaşma uygulamalarında **sadece yazı yazmak için klavyeyi açtığınızda** devreye giren, klasörünüzdeki meme videolarının **içindeki konuşmaları (Speech-to-Text)** ve **ekrandaki yazıları (OCR)** okuyarak saniyeler içinde bulup göndermenizi sağlayan Android uygulaması.

## Özellikler
1. **Sıfır Rahatsızlık (Akıllı Tetikleyici):** Ana ekranda, oyunlarda veya YouTube'da video izlerken asla ekranda durmaz. Sadece izin verilen mesajlaşma uygulamalarında (`Instagram`, `WhatsApp`, `Telegram`, `Discord`, `Messenger`) klavye açıldığında klavyenin sağ üst köşesinde yarı saydam küçük bir `⚡ Meme` ikonu belirir.
2. **Game Turbo Tarzı Yarı Saydam Panel:** İkona dokunduğunuzda sohbetten çıkmadan ekranın üzerinde buzlu cam görünümünde arama paneli açılır.
3. **Çift Katmanlı İçerik Okuma (Ses + Görsel Yazı):**
   - **Google ML Kit OCR:** Videonun farklı saniyelerinden kareler alarak videonun üstüne yazılmış tüm metinleri ve altyazıları okur.
   - **Vosk Offline Speech-to-Text:** Videonun ses kanalını çözüp içindeki Türkçe konuşmaları metne döker (`files/vosk-model-small-tr` klasörüne Türkçe model eklendiğinde çevrimdışı çalışır).
4. **Önizle ve Seçerek Gönder:** Arama sonucunda çıkan videoları göndermeden önce `▶ İzle` tuşuyla panelin içinde izleyebilir, doğru videoyu bulduğunuzda `Gönder 🚀` tuşuyla doğrudan açık olan sohbete fırlatabilirsiniz.

## Bilgisayara Program Kurmadan APK Nasıl Alınır? (GitHub Actions)
1. Ücretsiz bir GitHub hesabı açıp yeni bir Repository (Depo) oluşturun.
2. Bu ZIP dosyasının içindeki tüm klasörleri ve dosyaları GitHub deponuza yükleyin.
3. Üst menüdeki **Actions** sekmesine tıklayın; GitHub sizin yerinize otomatik olarak `.apk` dosyasını derleyip **MemeTurbo-Android-APK** adıyla indirmeye hazır hale getirecektir.

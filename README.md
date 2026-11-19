# Takas (TradePlugin)

Oyuncular arası güvenli takas sistemi ve takas geçmişi menüsü.

**Uyumluluk:** Spigot / Paper / Purpur **1.16.5 – 1.21.x** · Java 8+ · Ek bağımlılık yok

## Özellikler

- `/trade <oyuncu>` ile sohbette tıklanabilir **Kabul Et / Reddet** butonlu istek
- 3x3 sol bölge (senin itemlerin) / 3x3 sağ bölge (karşı taraf), canlı senkron
- İki taraf onaylayınca geri sayım; item değişirse onaylar ve geri sayım otomatik sıfırlanır
- Geri sayım sırasında onay butonuna basarak durdurma, **İptal** butonu
- Menü kapanırsa / oyuncu çıkarsa / eklenti kapanırsa itemler sahiplerine iade edilir
- `/tradelog [oyuncu]` sayfalı takas geçmişi + her takasın item detayı
- Tüm menülerde dolgu camı yok — boş slotlar gerçekten boş
- Tüm mesajlar `config.yml` üzerinden düzenlenebilir

## Komutlar

| Komut | Açıklama | İzin |
|---|---|---|
| `/trade <oyuncu>` (`/takas`) | Takas isteği gönder | `tradeplugin.trade` (herkes) |
| `/trade toggle` | İstekleri aç / kapat | `tradeplugin.toggle` (herkes) |
| `/trade reload` | Config'i yenile | `tradeplugin.admin` (op) |
| `/tradereply accept\|deny` | İsteğe yanıt (butonlar otomatik) | `tradeplugin.trade` |
| `/tradelog [oyuncu]` (`/takaslog`) | Takas geçmişi | `tradeplugin.tradelog` (op) |

## Ayarlar (`config.yml`)

```yaml
trade:
  max-distance: 15            # 0 = sınırsız
  allow-cross-world: false
  countdown-seconds: 5
  request-timeout-seconds: 30
```

## 1.1.0'da düzeltilenler

- **1.21 uyumu:** `InventoryView` 1.21'de class → interface oldu; 1.16.5 API ile derlenen eski kod 1.21'de
  `IncompatibleClassChangeError` veriyordu. Menüler artık `InventoryHolder` ile tanınıyor, `getView()` kullanılmıyor.
- **SQLite:** Sürücü relocate edilince native JNI bağlantısı kopuyordu; artık sunucunun kendi sürücüsü kullanılıyor.
- **Dupe koruması:** Çift tıklama toplama (`COLLECT_TO_CURSOR`) karşı tarafın itemlerini çekebiliyordu → engellendi.
  İptal/tamamlama anında iki menü envanterden yeniden okunuyor (aynı tick'te imlece alınan item iki kez iade edilmiyor).
- Geri sayım sırasında item değişirse geri sayım artık durduruluyor (önceden onay sıfırlanıp takas yine tamamlanıyordu).
- Onaylar sadece itemler gerçekten değiştiğinde sıfırlanıyor.
- Takas geçmişi menüsünde sürükleme (drag) ile item kaybı engellendi; sayfa spam'i kilitlendi.
- Eklenti kapanırken açık geçmiş menüleri kapatılıyor (item alınamaz).
- Çıkan oyuncunun gönderdiği bekleyen istekler temizleniyor; görünmez (vanish) oyunculara istek atılamıyor.
- Tüm dolgu camları kaldırıldı.

## Derleme

```bash
mvn clean package
```

Çıktı: `target/TradePlugin-1.1.0.jar`

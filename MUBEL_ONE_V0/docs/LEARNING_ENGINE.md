# MUBEL ONE V0 — Vega/Arctos Öğrenme Motoru

## Amaç

Vega/Arctos içinde kullanıcı normal işini yaparken MUBEL ONE, YSERVER/YILANCIOGLU üzerinde
hangi SQL işlem zincirlerinin gerçekleştiğini otomatik olarak gözlemler ve yerel bir işlem
parmak izi kütüphanesi oluşturur.

## Güvenlik sınırı

V0, YILANCIOGLU veritabanına tablo, trigger, kolon veya uygulama verisi eklemez.
MUBEL ONE'ın öğrendiği olaylar yalnızca:

    C:\ProgramData\MUBEL_ONE\mubel_one.db

içinde tutulur.

Canlı öğrenme için SQL Server Extended Events üzerinde geçici bir server session kullanılır:

- ad: MUBEL_ONE_LEARN
- STARTUP_STATE = OFF
- hedef: yalnız RAM ring_buffer
- uygulama kapanınca STOP + DROP
- YILANCIOGLU verisine DML yazılmaz
- MUBEL ONE'ın kendi sorguları filtrelenir

Yetki yoksa sistem öğrenme oturumunu açamaz; veri yazmaya kalkmaz ve salt-okunur keşifte kalır.

## Öğrenme modeli

Olaylar session_id + transaction_id + zaman + SQL sırasına göre gruplanacaktır.

Her işlem için şu kanıtlar birleştirilir:

1. Canlı YSERVER Extended Events gözlemi
2. Gerçek INSERT / UPDATE / DELETE kodu bulunan GitHub projeleri
3. VegaWebService metod sözleşmeleri
4. Kullanıcının eski .linq / SQL / Excel aktarım çalışmaları
5. Arctos dosya/şema/rapor isimleri
6. Belge tipi / IZAHAT / BELGETIPI / IND / LN / EVRAKNO ilişkileri
7. İnternet dokümanları ve üretici entegrasyon yüzeyleri

## Kanıt seviyesi

- A: YSERVER'da canlı gözlendi + tablo zinciri doğrulandı
- B: çalışan kaynak kodunda gerçek yazma bulundu
- C: WebService sözleşmesi / resmi API yüzeyi
- D: şema, rapor, DLL, Excel, .linq veya doküman kanıtı
- E: yalnız isimsel/inferans; üretim yazması için kullanılamaz

## Otomatik sınıflandırma

İlk sınıflandırıcı tablo imzalarından şunları tanır:

- satış faturası
- alış faturası
- alış siparişi
- satış siparişi
- stok giriş/çıkış
- depo transferi
- banka/POS
- kasa
- çek
- senet
- silme/iptal

Yeni desenler Unknown olarak saklanır; aynı desen tekrarlandıkça sayaç ve güven skoru büyür.
Daha sonra tablo zinciri + IZAHAT + BELGETIPI + WebService eşleşmesi ile otomatik isimlendirilir.

## V1 hedefi

Öğrenme tamamlanmadan Vega'ya otomatik belge yazımı açılmaz.
V1 yazma motoru, yalnız A/B seviyesinde doğrulanmış işlem zincirlerini kullanır.

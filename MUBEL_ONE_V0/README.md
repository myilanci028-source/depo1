# MUBEL ONE V0

Yılancıoğlu / Vega Arctos için güvenli, otomatik keşif ve öğrenme çekirdeği.

## V0 sözleşmesi

- Tek Windows x64 EXE hedefi
- YSERVER / YILANCIOGLU otomatik keşif
- Vega firma/dönem/tablo yüzeyini otomatik tanıma
- YILANCIOGLU/VEGADB verisine INSERT/UPDATE/DELETE yapmama
- server üzerinde MUBEL database'i oluşturmama
- kendi cache/log/öğrenme verisini ProgramData altında SQLite'ta tutma
- geçici Extended Events ile gerçek Vega işlem zincirlerini öğrenme
- uygulama kapanınca XE oturumunu silme
- kaynaklarda veya ekranda SQL parolasını loglamama

## Birleştirilecek veri kaynakları

- Arctos kurulum/yedek haritası
- geçmiş LINQPad / .linq / SQL çalışmaları
- VegaExcelConverter + Belge.sql
- MUBELBANK / online banka / Excel banka aktarımı
- POS / VEGABOS_YENI / ODEME / TBLENPOSBANKA
- B2B ERP agent
- VegaWebService
- GitHub Vega/Arctos projeleri
- Ticimax, Trendyol, Hepsiburada, N11 SDK/API katmanları
- ileride mobil/PWA kontrol yüzeyi

## Bu dal

Bu çalışma `mubel-one-v0` dalındadır. Ana `main` dalındaki mevcut KANTAR ve diğer
çalışmalara dokunmaz.

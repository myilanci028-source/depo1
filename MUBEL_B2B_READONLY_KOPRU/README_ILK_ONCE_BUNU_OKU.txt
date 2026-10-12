MUBEL B2B - VEGA READ ONLY ERP KOPRUSU V1

SENIN YAPACAGIN
1) ZIP'i Indirilenler'e cikar.
2) 00_MUBEL_B2B_READONLY_KUR.bat dosyasina cift tikla.
3) Bittiginde Yönetim > ERP Köprüsü ekrani otomatik acilir.

PAKETIN YAPTIGI
- Mevcut C:\MUBEL_B2B kurulumunu kullanir.
- YSERVER / 192.168.1.250 uzerindeki YILANCIOGLU'yu SADECE OKUR.
- YSERVER'a B2B / Docker / Node kurmaz.
- YILANCIOGLU icinde tablo acmaz.
- YILANCIOGLU is verisine INSERT / UPDATE / DELETE yapmaz.
- Ayrı MUBEL_B2B_READER SQL hesabi olusturur.
- Bu hesap db_datareader + db_denydatawriter olur.
- ERP Agent write.enabled=false kalir.
- Komut kanali sadece 127.0.0.1:8787 uzerindedir.
- Ajanı yokla ve Siparis tablolarini incele kullanilabilir.

ONEMLI
- B2B'nin KENDI PostgreSQL database'i vardir. ERP'den okunan eslesen cari/stok
  bilgileri oraya yazilabilir. Bu YILANCIOGLU'ya yazmak degildir.
- Kurulum SQL yonetici kimligini mevcut Arctos/B2B configlerinden otomatik
  bulamazsa güvenlik geregi durur. SA parolasi bu pakete gomulu degildir.

LOG
C:\MUBEL_B2B\var\mubel-readonly\kurulum.log
C:\MUBEL_B2B\var\mubel-readonly\DURUM.txt

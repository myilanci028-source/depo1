# Yılancıoğlu Barkod Klavye iOS

Apple kurallarına uygun, iki parçalı barkod klavye sistemi:

1. Ana uygulama kamerayla barkodu okur ve son 50 kaydı cihaz içindeki App Group alanına yazar.
2. Sistem genelindeki YILANCIOĞLU klavye uzantısı son üç barkodu gösterir ve seçilen değeri aktif metin alanına ekler.

## Neden kamera klavyenin içinde değil?

Apple App Review Guideline 4.4.1, klavye uzantılarının Settings dışında başka uygulama açmasını ve bir klavye tuşunu kamera açmak için kullanmasını yasaklar. Bu nedenle kamera yalnızca ana uygulamadadır.

## Özellikler

- EAN-8, EAN-13 / UPC-A, UPC-E
- Code 39, Code 93, Code 128
- ITF, ITF-14, Codabar
- QR, Data Matrix, PDF417, Aztec
- Son 50 okumanın cihaz içi geçmişi
- Klavyede son üç kodu tek dokunuşla yazma
- Enter, Tab, Boşluk veya Hiçbiri sonlandırma seçeneği
- Tam Erişim kapalıyken de çalışan sayısal temel klavye
- Reklam, analiz SDK’sı, kullanıcı hesabı ve sunucu bağlantısı yok

## Paket kimlikleri

- Uygulama: `com.yilancioglu.barcodekeyboard.ios`
- Klavye: `com.yilancioglu.barcodekeyboard.ios.keyboard`
- App Group: `group.com.yilancioglu.barcodekeyboard`

## Projeyi oluşturma

```bash
cd ios-barcode-keyboard
brew install xcodegen
xcodegen generate
open YilanciogluBarcodeKeyboardIOS.xcodeproj
```

## Gerçek iPhone / TestFlight

Xcode’da hem uygulama hem klavye hedefi için aynı Apple Developer Team seçilmeli. Yukarıdaki iki Bundle ID ve App Group, Apple Developer portalında oluşturulmalı. GitHub Actions yalnızca imzasız iOS Simulator derlemesini doğrular; gerçek cihaz ve TestFlight için Apple imzası gerekir.

from pathlib import Path
import re, sys
root=Path(sys.argv[1])
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
s=s.replace('private static final long OCR_PERIOD_MS = 230L;', 'private static final long OCR_PERIOD_MS = 120L;')
s=s.replace('InputImage img = InputImage.fromBitmap(composite,0);\n            final Bitmap fFrame=frame, fCrop=crop, fComposite=composite;',
'''Bitmap boosted = redLedBoost(composite);
            InputImage img = InputImage.fromBitmap(boosted,0);
            final Bitmap fFrame=frame, fCrop=crop, fComposite=composite, fBoosted=boosted;''')
s=s.replace('try{fComposite.recycle();}catch(Exception ignored){} try{fCrop.recycle();}', 'try{fBoosted.recycle();}catch(Exception ignored){} try{fComposite.recycle();}catch(Exception ignored){} try{fCrop.recycle();}')
marker='    private int lum(int p){ return (Color.red(p)*3 + Color.green(p)*6 + Color.blue(p))/10; }'
add=r'''    private int lum(int p){ return (Color.red(p)*3 + Color.green(p)*6 + Color.blue(p))/10; }

    // 2.10.12: red seven-segment LED isolation. This deliberately leaves the stable
    // camera/ML Kit path intact and only feeds OCR a second, high-contrast view.
    private Bitmap redLedBoost(Bitmap src) {
        int w=src.getWidth(), h=src.getHeight(), n=w*h;
        int[] px=new int[n]; src.getPixels(px,0,w,0,0,w,h);
        for(int i=0;i<n;i++){
            int r=Color.red(px[i]), g=Color.green(px[i]), b=Color.blue(px[i]);
            int dominance=r-Math.max(g,b);
            int score=(r*2)+Math.max(0,dominance*4)-g-b;
            int v=score>180 ? 255 : (score>95 ? 210 : 0);
            px[i]=Color.rgb(v,v,v);
        }
        Bitmap mono=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        mono.setPixels(px,0,w,0,0,w,h);
        Bitmap big=Bitmap.createScaledBitmap(mono,w*2,h*2,false);
        if(big!=mono)mono.recycle();
        return big;
    }'''
if marker not in s: raise SystemExit('lum marker missing')
s=s.replace(marker,add,1)
s=s.replace('50 Hz anti-flicker + 3 kare parlaklık birleştirme aktif.', '50 Hz anti-flicker + hızlı çok-kare + kırmızı 7-segment güçlendirme aktif.')
s=s.replace('3 kare parlaklık birleştirme + tekrar doğrulama: ', 'LED güçlendirme + çok-kare doğrulama: ')
p.write_text(s,encoding='utf-8')

g=root/'app/build.gradle'
x=g.read_text(encoding='utf-8')
x=x.replace("applicationId 'com.mubel.kantar.v2111learn'","applicationId 'com.mubel.kantar.v2112camera'")
x=x.replace('versionCode 2111','versionCode 2112')
x=x.replace("versionName '2.10.11-STABIL-KUMANDA-OGRET'","versionName '2.10.12-CAMERA-BOOST-STABIL'")
g.write_text(x,encoding='utf-8')
print('PATCH_2112_OK')

from pathlib import Path
import sys
root=Path(sys.argv[1])
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
old='''                Bitmap frame = texture.getBitmap(960, 540);'''
new='''                // V18: NEVER squash the portrait TextureView into 960x540. That changed a real\n                // ~3:1 scale display into an artificial ~12:1 strip and every panel decoder rejected it.\n                int tw=Math.max(1,texture.getWidth()), th=Math.max(1,texture.getHeight());\n                int fw=Math.min(720,tw);\n                int fh=Math.max(1,Math.round(th*(fw/(float)tw)));\n                Bitmap frame = texture.getBitmap(fw, fh);'''
if old not in s: raise SystemExit('V18 getBitmap marker missing')
s=s.replace(old,new,1)
s=s.replace('50 Hz + GÖRECELİ PARLAKLIK + 5 HANE 7-SEGMENT aktif.','50 Hz + ORAN KORUMALI KAMERA + GÖRECELİ PARLAKLIK aktif.')
p.write_text(s,encoding='utf-8')
print('V18_ASPECT_RATIO_OK')

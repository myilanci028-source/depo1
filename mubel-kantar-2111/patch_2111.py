from pathlib import Path
import shutil,sys
root=Path(sys.argv[1]); extra=Path(sys.argv[2])
def rw(rel,fn):
 p=root/rel; s=p.read_text(encoding='utf-8'); n=fn(s)
 if n==s: raise SystemExit('Patch did not change '+str(p))
 p.write_text(n,encoding='utf-8')
shutil.copy2(extra/'v2111.js',root/'app/src/main/assets/v2111.js')
def gradle(s):
 s=s.replace("applicationId 'com.mubel.kantar.v2109control'","applicationId 'com.mubel.kantar.v2111learn'")
 s=s.replace('versionCode 2109','versionCode 2111')
 s=s.replace("versionName '2.10.9-UNIVERSAL-CONTROL'","versionName '2.10.11-STABIL-KUMANDA-OGRET'")
 return s
rw(Path('app/build.gradle'),gradle)
def index(s):
 m='<script src="v2109.js"></script>'
 if m not in s: raise SystemExit('v2109 marker missing')
 return s.replace(m,m+'\n<script src="v2111.js"></script>',1)
rw(Path('app/src/main/assets/index.html'),index)
print('MUBEL KANTAR 2.10.11 patch OK')

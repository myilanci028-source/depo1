from pathlib import Path
import sys
root=Path(sys.argv[1]); p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
# Add direct 7-segment attempt before ML OCR; fallback remains unchanged.
needle='''Bitmap boosted = redLedBoost(composite);
            InputImage img = InputImage.fromBitmap(boosted,0);'''
repl='''Bitmap boosted = redLedBoost(composite);
            final String segValue = decodeSevenSegment(composite);
            if (segValue != null) main.post(() -> {
                try { pushSample(Double.parseDouble(segValue), "7-SEG: "+segValue); }
                catch(Exception ignored) {}
            });
            InputImage img = InputImage.fromBitmap(boosted,0);'''
if needle not in s: raise SystemExit('boost needle missing')
s=s.replace(needle,repl,1)
marker='''    private Bitmap redLedBoost(Bitmap src) {'''
methods=r'''    // 2.10.13: direct red seven-segment decoder. It does not replace OCR;
    // a valid 7-seg reading becomes an additional candidate, OCR stays as fallback.
    private String decodeSevenSegment(Bitmap src) {
        int w=src.getWidth(), h=src.getHeight();
        // Find the densest red LED bounding box, rejecting tiny noise.
        int minX=w, minY=h, maxX=-1, maxY=-1, redCount=0;
        for(int y=0;y<h;y+=2) for(int x=0;x<w;x+=2) {
            int c=src.getPixel(x,y), r=Color.red(c), g=Color.green(c), b=Color.blue(c);
            if(r>105 && r>g*1.28 && r>b*1.18 && r-Math.max(g,b)>28) {
                minX=Math.min(minX,x); maxX=Math.max(maxX,x); minY=Math.min(minY,y); maxY=Math.max(maxY,y); redCount++;
            }
        }
        if(maxX<0 || redCount<35) return null;
        int bw=maxX-minX+1, bh=maxY-minY+1;
        if(bw<w*0.12 || bh<h*0.06) return null;

        // Most crane displays are five positions. Blank leading positions are allowed.
        int slots=5; double sw=bw/(double)slots; StringBuilder out=new StringBuilder();
        boolean started=false;
        for(int i=0;i<slots;i++) {
            int x0=(int)(minX+i*sw), x1=(int)(minX+(i+1)*sw);
            int d=decodeDigit(src,x0,minY,x1,maxY);
            if(d>=0){ out.append((char)('0'+d)); started=true; }
            else if(started) { /* tolerate blank trailing/scan phase */ }
        }
        if(out.length()==0 || out.length()>5) return null;
        return out.toString();
    }

    private int decodeDigit(Bitmap b,int x0,int y0,int x1,int y1) {
        int w=Math.max(1,x1-x0), h=Math.max(1,y1-y0);
        // sample a band around each canonical segment, robust to thick LED bars
        boolean[] q=new boolean[7];
        q[0]=redRatio(b,x0+.22*w,y0+.08*h,x0+.78*w,y0+.24*h)>.075; // a
        q[1]=redRatio(b,x0+.66*w,y0+.16*h,x0+.92*w,y0+.49*h)>.060; // b
        q[2]=redRatio(b,x0+.66*w,y0+.51*h,x0+.92*w,y0+.84*h)>.060; // c
        q[3]=redRatio(b,x0+.22*w,y0+.76*h,x0+.78*w,y0+.94*h)>.075; // d
        q[4]=redRatio(b,x0+.08*w,y0+.51*h,x0+.34*w,y0+.84*h)>.060; // e
        q[5]=redRatio(b,x0+.08*w,y0+.16*h,x0+.34*w,y0+.49*h)>.060; // f
        q[6]=redRatio(b,x0+.22*w,y0+.41*h,x0+.78*w,y0+.61*h)>.070; // g
        int mask=0; for(int i=0;i<7;i++)if(q[i])mask|=(1<<i);
        int[] masks={0x3F,0x06,0x5B,0x4F,0x66,0x6D,0x7D,0x07,0x7F,0x6F};
        int best=-1,dist=8; for(int d=0;d<10;d++){int z=Integer.bitCount(mask^masks[d]);if(z<dist){dist=z;best=d;}}
        return dist<=1?best:-1;
    }
    private double redRatio(Bitmap b,double ax,double ay,double bx,double by){
        int x0=Math.max(0,(int)ax),y0=Math.max(0,(int)ay),x1=Math.min(b.getWidth(),(int)bx),y1=Math.min(b.getHeight(),(int)by);
        int hit=0,n=0; for(int y=y0;y<y1;y+=2)for(int x=x0;x<x1;x+=2){int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);n++;if(r>105&&r>g*1.25&&r>bl*1.15&&r-Math.max(g,bl)>25)hit++;}
        return n==0?0:hit/(double)n;
    }

'''
if marker not in s: raise SystemExit('method marker missing')
s=s.replace(marker,methods+marker,1)
s=s.replace('50 Hz anti-flicker + hızlı çok-kare + kırmızı 7-segment güçlendirme aktif.','50 Hz anti-flicker + doğrudan 7-segment + OCR yedek okuma aktif.')
p.write_text(s,encoding='utf-8')
g=root/'app/build.gradle'; x=g.read_text(encoding='utf-8')
x=x.replace("applicationId 'com.mubel.kantar.v2112camera'","applicationId 'com.mubel.kantar.v2113segment'")
x=x.replace('versionCode 2112','versionCode 2113')
x=x.replace("versionName '2.10.12-CAMERA-BOOST-STABIL'","versionName '2.10.13-SEVEN-SEGMENT-STABIL'")
g.write_text(x,encoding='utf-8')
print('PATCH_2113_OK')

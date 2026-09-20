from pathlib import Path
import sys
root=Path(sys.argv[1]); p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
s=s.replace('private static final long OCR_PERIOD_MS = 120;','private static final long OCR_PERIOD_MS = 80;')
needle='''Bitmap boosted = redLedBoost(composite);
            if(!hasVerifiedDisplay(composite)){
                main.post(() -> {
                    stateText.setText("KANTAR EKRANI ARANIYOR");
                    weightText.setText("-- kg");
                    detailText.setText("Gerçek LED gösterge bulunmadan değer üretilmez.");
                    latestStable = null; lastSeg = null; segHits = 0;
                });
                boosted.recycle(); composite.recycle(); crop.recycle(); frame.recycle(); processing=false; return;
            }
            InputImage img = InputImage.fromBitmap(boosted,0);'''
repl='''Bitmap boosted = redLedBoost(composite);
            final String segValue = hasRealLedDisplay(composite) ? decodeSevenSegmentInstant(composite) : null;
            if (segValue != null && hasVerifiedDisplay(composite)) {
                main.post(() -> {
                    try { pushInstantSegment(Double.parseDouble(segValue), segValue); } catch(Exception ignored) {}
                });
                boosted.recycle(); composite.recycle(); crop.recycle(); frame.recycle(); processing=false; return;
            }
            InputImage img = InputImage.fromBitmap(boosted,0);'''
if needle in s:
    s=s.replace(needle,repl,1)
marker='''    private Bitmap redLedBoost(Bitmap src) {'''
methods=r'''    private boolean hasRealLedDisplay(Bitmap b){
        int w=b.getWidth(),h=b.getHeight(), red=0,minX=w,maxX=-1,minY=h,maxY=-1;
        for(int y=0;y<h;y+=3) for(int x=0;x<w;x+=3) if(isRed(b,x,y)){red++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}
        if(red<18||maxX<0)return false;
        int bw=maxX-minX+1,bh=maxY-minY+1;
        return bw>w*0.06 && bh>h*0.025 && bw>bh*0.35;
    }
    private boolean hasVerifiedDisplay(Bitmap b){
        int w=b.getWidth(),h=b.getHeight(), score=0;
        for(int cy=h/4;cy<3*h/4;cy+=Math.max(6,h/24)){
            int ch=Math.max(28,h/5), y0=Math.max(0,cy-ch/2), y1=Math.min(h-1,cy+ch/2);
            int cw=Math.max(90,w/2), x0=Math.max(0,w/2-cw/2), x1=Math.min(w-1,w/2+cw/2);
            int dark=0,red=0,n=0;
            for(int y=y0;y<=y1;y+=4)for(int x=x0;x<=x1;x+=4){
                int cc=b.getPixel(x,y),r=Color.red(cc),g=Color.green(cc),bl=Color.blue(cc);
                n++; if((r+g+bl)/3<105)dark++; if(isRed(b,x,y))red++;
            }
            if(n>0 && dark/(double)n>0.34 && red/(double)n>0.004 && red/(double)n<0.30) score++;
        }
        return score>=2;
    }

    private String lastSeg=null; private int segHits=0;
    private void pushInstantSegment(double kg,String raw){
        String k=String.valueOf(Math.round(kg*10.0)/10.0);
        if(raw.equals(lastSeg)) segHits++; else { lastSeg=raw; segHits=1; }
        // Direct decoder has priority. Two consecutive frames (~160 ms) are enough.
        if(segHits>=2) {
            latestStable=kg;
            stateText.setText("7-SEGMENT OK");
            weightText.setText(k+" kg");
            detailText.setText("Doğrudan LED okuma: "+raw);
        } else {
            stateText.setText("RAKAM DOĞRULANIYOR");
            weightText.setText(k+" kg");
        }
    }
    private boolean isRed(Bitmap b,int x,int y){
        if(x<0||y<0||x>=b.getWidth()||y>=b.getHeight()) return false;
        int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
        return r>185 && r>g*1.45 && r>bl*1.30 && r-Math.max(g,bl)>55;
    }
    private String decodeSevenSegmentInstant(Bitmap src){
        int w=src.getWidth(),h=src.getHeight();
        // Projection: isolated red LEDs (TARE etc.) are ignored by requiring a wide horizontal LED band.
        int[] rows=new int[h];
        for(int y=0;y<h;y+=2) for(int x=0;x<w;x+=2) if(isRed(src,x,y)) rows[y]++;
        int bestY=-1,best=0;
        for(int y=0;y<h;y+=2){int z=0;for(int yy=Math.max(0,y-h/14);yy<=Math.min(h-1,y+h/14);yy+=2)z+=rows[yy];if(z>best){best=z;bestY=y;}}
        if(bestY<0||best<12)return null;
        int y0=Math.max(0,bestY-h/7),y1=Math.min(h-1,bestY+h/7);
        int[] col=new int[w];
        for(int x=0;x<w;x+=2)for(int y=y0;y<=y1;y+=2)if(isRed(src,x,y))col[x]++;
        // Build x runs, merge segment bars belonging to the same digit using gap relative to band height.
        java.util.ArrayList<int[]> runs=new java.util.ArrayList<>();
        int rs=-1;
        for(int x=0;x<w;x+=2){boolean on=col[x]>=2;if(on&&rs<0)rs=x;if((!on||x>=w-2)&&rs>=0){int e=on?x:x-2;if(e-rs>=2)runs.add(new int[]{rs,e});rs=-1;}}
        if(runs.size()<2)return null;
        java.util.ArrayList<int[]> groups=new java.util.ArrayList<>();
        int gx=runs.get(0)[0],ge=runs.get(0)[1]; int mergeGap=Math.max(10,(y1-y0)/3);
        for(int i=1;i<runs.size();i++){int[] r=runs.get(i);if(r[0]-ge<=mergeGap)ge=r[1];else{groups.add(new int[]{gx,ge});gx=r[0];ge=r[1];}} groups.add(new int[]{gx,ge});
        // If projection merged adjacent digits, split by deep valleys.
        java.util.ArrayList<int[]> digits=new java.util.ArrayList<>();
        for(int[] g:groups){
            int gw=g[1]-g[0]+1;
            if(gw>(y1-y0)*0.78){
                int start=g[0]; int minGap=Math.max(8,(y1-y0)/10);
                int zeroStart=-1;
                for(int x=g[0];x<=g[1];x+=2){if(col[x]==0&&zeroStart<0)zeroStart=x;if((col[x]>0||x>=g[1]-1)&&zeroStart>=0){int end=x-2;if(end-zeroStart>=minGap&&zeroStart-start>(y1-y0)/5){digits.add(new int[]{start,zeroStart-2});start=x;}zeroStart=-1;}}
                if(g[1]-start>3)digits.add(new int[]{start,g[1]});
            } else digits.add(g);
        }
        if(digits.size()==0||digits.size()>6)return null;
        StringBuilder out=new StringBuilder();
        for(int[] d:digits){
            int dx0=Math.max(0,d[0]-4),dx1=Math.min(w-1,d[1]+4);
            // vertical extent per digit, so an isolated TARE lamp cannot stretch digit geometry
            int dy0=y1,dy1=y0,cnt=0;
            for(int y=y0;y<=y1;y+=2)for(int x=dx0;x<=dx1;x+=2)if(isRed(src,x,y)){dy0=Math.min(dy0,y);dy1=Math.max(dy1,y);cnt++;}
            if(cnt<5||dy1-dy0<8)continue;
            int val=decodeDigitFlexible(src,dx0,dy0,dx1,dy1);
            if(val<0)return null; out.append((char)('0'+val));
        }
        return out.length()>0?out.toString():null;
    }
    private int decodeDigitFlexible(Bitmap b,int x0,int y0,int x1,int y1){
        int w=Math.max(1,x1-x0),h=Math.max(1,y1-y0);
        boolean[] q=new boolean[7];
        q[0]=redRatio2(b,x0+.12*w,y0,x0+.88*w,y0+.25*h)>.025;
        q[1]=redRatio2(b,x0+.58*w,y0+.08*h,x1,y0+.50*h)>.035;
        q[2]=redRatio2(b,x0+.58*w,y0+.50*h,x1,y0+.92*h)>.035;
        q[3]=redRatio2(b,x0+.12*w,y0+.75*h,x0+.88*w,y1)>.035;
        q[4]=redRatio2(b,x0,y0+.50*h,x0+.42*w,y0+.92*h)>.035;
        q[5]=redRatio2(b,x0,y0+.08*h,x0+.42*w,y0+.50*h)>.035;
        q[6]=redRatio2(b,x0+.12*w,y0+.36*h,x0+.88*w,y0+.64*h)>.035;
        int mask=0;for(int i=0;i<7;i++)if(q[i])mask|=1<<i;
        int[] m={0x3F,0x06,0x5B,0x4F,0x66,0x6D,0x7D,0x07,0x7F,0x6F};
        int bd=8,bv=-1;for(int d=0;d<10;d++){int z=Integer.bitCount(mask^m[d]);if(z<bd){bd=z;bv=d;}}
        return bd<=1?bv:-1;
    }
    private double redRatio2(Bitmap b,double ax,double ay,double bx,double by){
        int x0=Math.max(0,(int)ax),y0=Math.max(0,(int)ay),x1=Math.min(b.getWidth(),(int)bx),y1=Math.min(b.getHeight(),(int)by),n=0,hit=0;
        for(int y=y0;y<y1;y+=2)for(int x=x0;x<x1;x+=2){n++;if(isRed(b,x,y))hit++;}return n==0?0:hit/(double)n;
    }

'''
if marker not in s: raise SystemExit('marker missing')
s=s.replace(marker,methods+marker,1)
s=s.replace('50 Hz anti-flicker + hızlı çok-kare + kırmızı 7-segment güçlendirme aktif.','50 Hz anti-flicker + EKRAN DOĞRULAMA + ANLIK 7-segment aktif.')
p.write_text(s,encoding='utf-8')
g=root/'app/build.gradle';x=g.read_text(encoding='utf-8')
x=x.replace("applicationId 'com.mubel.kantar.v2112camera'","applicationId 'com.mubel.kantar.v2113instant'")
x=x.replace('versionCode 2112','versionCode 2113')
x=x.replace("versionName '2.10.12-CAMERA-BOOST-STABIL'","versionName '2.10.13-SCREEN-LOCK-STABIL'")
g.write_text(x,encoding='utf-8')
print('PATCH_2113_INSTANT_OK')

# build trigger 2026-09-20

# display-gate-build

# screen-lock build trigger

# rebuild-screenlock

# FINAL 2.10.13 camera gate: replace the exact 2.10.12 OCR entry point.
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
old='''Bitmap boosted = redLedBoost(composite);\n            InputImage img = InputImage.fromBitmap(boosted,0);\n            final Bitmap fFrame=frame, fCrop=crop, fComposite=composite, fBoosted=boosted;'''
new='''Bitmap boosted = redLedBoost(composite);\n            final String direct = decodeSevenSegmentInstant(composite);\n            if (direct != null) {\n                main.post(() -> { try { pushInstantSegment(Double.parseDouble(direct), direct); } catch(Exception ignored) {} });\n            } else {\n                main.post(() -> { stateText.setText("KANTAR EKRANI ARANIYOR"); weightText.setText("-- kg"); detailText.setText("Gerçek LED rakam görülmeden değer üretilmez."); latestStable=Double.NaN; lastSeg=null; segHits=0; });\n            }\n            InputImage img = InputImage.fromBitmap(boosted,0);\n            final Bitmap fFrame=frame, fCrop=crop, fComposite=composite, fBoosted=boosted;'''
if old not in s: raise SystemExit('FINAL camera entry marker missing')
s=s.replace(old,new,1)
# Sunlight can turn red LEDs yellow/orange. Accept bright warm active segments, not the dull red LCD background.
s=s.replace('return r>185 && r>g*1.45 && r>bl*1.30 && r-Math.max(g,bl)>55;', 'return (r>185 && r>g*1.35 && r>bl*1.25 && r-Math.max(g,bl)>42) || (r>205 && g>125 && bl<145 && r-bl>70 && g-bl>28);')
s=s.replace('50 Hz anti-flicker + EKRAN DOĞRULAMA + ANLIK 7-segment aktif.','50 Hz anti-flicker + SADECE GERÇEK LED + ANLIK 7-segment aktif.')
p.write_text(s,encoding='utf-8')
print('FINAL_SCREEN_GATE_OK')

# OCR remains only for frame lifecycle; it must never publish a weight in 2.10.13.
s=s.replace('processResult(text);','/* OCR result intentionally ignored: direct 7-segment only */')
p.write_text(s,encoding='utf-8')

# final-direct-build

# 2.10.13 PHOTO-TESTED decoder v2: active-emission only; inactive red outlines are NOT digits.
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
start=s.index('    private boolean isRed(Bitmap b,int x,int y){')
end=s.index('    private Bitmap redLedBoost(Bitmap src)', start)
if start<0 or end<0: raise SystemExit('decoder block not found')
v2=r'''    private boolean isRed(Bitmap b,int x,int y){
        if(x<0||y<0||x>=b.getWidth()||y>=b.getHeight()) return false;
        int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
        // Active LED under sun becomes yellow/orange (green rises above blue).
        boolean warm = r>190 && g>115 && (g-bl)>12 && (r-bl)>55;
        // In shade an active LED is deep, very bright red. Dim inactive "8" outlines are rejected.
        boolean deep = r>205 && g<112 && bl<112 && r-Math.max(g,bl)>100;
        return warm || deep;
    }
    private String decodeSevenSegmentInstant(Bitmap src){
        int w=src.getWidth(),h=src.getHeight();
        int[] col=new int[w]; int total=0;
        for(int y=0;y<h;y+=2) for(int x=0;x<w;x+=2) if(isRed(src,x,y)){col[x]++;total++;}
        if(total<12)return null;

        java.util.ArrayList<int[]> bars=new java.util.ArrayList<>();
        int rs=-1;
        for(int x=0;x<w;x+=2){
            boolean on=col[x]>=2;
            if(on&&rs<0)rs=x;
            if((!on||x>=w-2)&&rs>=0){int e=on?x:x-2;if(e-rs>=2)bars.add(new int[]{rs,e});rs=-1;}
        }
        if(bars.size()==0)return null;

        // Merge bars into digits. A 7-segment digit is about half as wide as it is tall.
        java.util.ArrayList<int[]> digits=new java.util.ArrayList<>();
        int gx=bars.get(0)[0], ge=bars.get(0)[1];
        for(int i=1;i<bars.size();i++){
            int[] b=bars.get(i);
            int gap=b[0]-ge;
            int[] yr=yRange(src,gx,ge), yr2=yRange(src,b[0],b[1]);
            int hh=Math.max(24,Math.max(yr[1]-yr[0],yr2[1]-yr2[0]));
            if(gap<=Math.max(12,(int)(hh*0.28))) ge=b[1];
            else { digits.add(new int[]{gx,ge}); gx=b[0]; ge=b[1]; }
        }
        digits.add(new int[]{gx,ge});

        StringBuilder out=new StringBuilder();
        for(int[] d:digits){
            int[] yr=yRange(src,d[0],d[1]); int dh=yr[1]-yr[0]+1,dw=d[1]-d[0]+1;
            if(dh<16) continue; // status lamps/reflections
            // Digit 1 has only the two right verticals and is naturally narrow.
            if(dw < dh*0.28){ out.append('1'); continue; }
            int val=decodeDigitFlexible(src,Math.max(0,d[0]-3),Math.max(0,yr[0]-3),Math.min(w-1,d[1]+3),Math.min(h-1,yr[1]+3));
            if(val<0) continue;
            out.append((char)('0'+val));
        }
        return out.length()>0?out.toString():null;
    }
    private int[] yRange(Bitmap b,int x0,int x1){
        int lo=b.getHeight(),hi=-1;
        for(int y=0;y<b.getHeight();y+=2)for(int x=Math.max(0,x0);x<=Math.min(b.getWidth()-1,x1);x+=2)
            if(isRed(b,x,y)){lo=Math.min(lo,y);hi=Math.max(hi,y);}
        return hi<0?new int[]{0,0}:new int[]{lo,hi};
    }
    private int decodeDigitFlexible(Bitmap b,int x0,int y0,int x1,int y1){
        int w=Math.max(1,x1-x0),h=Math.max(1,y1-y0);
        boolean[] q=new boolean[7];
        q[0]=redRatio2(b,x0+.30*w,y0+.02*h,x0+.70*w,y0+.16*h)>.34;
        q[1]=redRatio2(b,x0+.72*w,y0+.08*h,x0+.98*w,y0+.48*h)>.34;
        q[2]=redRatio2(b,x0+.72*w,y0+.52*h,x0+.98*w,y0+.92*h)>.34;
        q[3]=redRatio2(b,x0+.30*w,y0+.84*h,x0+.70*w,y0+.98*h)>.34;
        q[4]=redRatio2(b,x0+.02*w,y0+.52*h,x0+.28*w,y0+.92*h)>.34;
        q[5]=redRatio2(b,x0+.02*w,y0+.08*h,x0+.28*w,y0+.48*h)>.34;
        q[6]=redRatio2(b,x0+.20*w,y0+.30*h,x0+.80*w,y0+.62*h)>.34;
        int mask=0;for(int i=0;i<7;i++)if(q[i])mask|=1<<i;
        int[] m={0x3F,0x06,0x5B,0x4F,0x66,0x6D,0x7D,0x07,0x7F,0x6F};
        int bd=8,bv=-1;for(int d=0;d<10;d++){int z=Integer.bitCount(mask^m[d]);if(z<bd){bd=z;bv=d;}}
        return bd<=1?bv:-1;
    }
    private double redRatio2(Bitmap b,double ax,double ay,double bx,double by){
        int x0=Math.max(0,(int)ax),y0=Math.max(0,(int)ay),x1=Math.min(b.getWidth(),(int)bx),y1=Math.min(b.getHeight(),(int)by),n=0,hit=0;
        for(int y=y0;y<y1;y+=2)for(int x=x0;x<x1;x+=2){n++;if(isRed(b,x,y))hit++;}
        return n==0?0:hit/(double)n;
    }

'''
s=s[:start]+v2+s[end:]
s=s.replace('50 Hz anti-flicker + SADECE GERÇEK LED + ANLIK 7-segment aktif.','50 Hz anti-flicker + FOTO-TESTLİ LED OKUMA + ANLIK 7-segment aktif.')
p.write_text(s,encoding='utf-8')
print('PHOTO_TESTED_DECODER_V2_OK')

# Keep adjacent digits separate: only bridge tiny threshold holes inside one emitted digit.
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
s=s.replace('if(gap<=Math.max(12,(int)(hh*0.28))) ge=b[1];','if(gap<=Math.max(4,(int)(hh*0.055))) ge=b[1];')
p.write_text(s,encoding='utf-8')
print('DIGIT_GAP_FIX_OK')

# 2.10.13 DISPLAY-FRAME decoder v3: locate the physical yellow display frame, then read fixed digit cells.
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
a=s.index('    private String decodeSevenSegmentInstant(Bitmap src){')
b=s.index('    private int[] yRange(',a)
if a<0 or b<0: raise SystemExit('v3 decoder bounds missing')
v3=r'''    private String decodeSevenSegmentInstant(Bitmap src){
        int w=src.getWidth(),h=src.getHeight();
        // Find the yellow bezel of this crane-scale display. It gives us the true digit geometry,
        // so dim unlit 8-shaped LCD outlines cannot move/resize the digit cells.
        int minX=w,minY=h,maxX=-1,maxY=-1,n=0;
        for(int y=0;y<h;y+=2)for(int x=0;x<w;x+=2){
            int c=src.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
            boolean yellow=r>185 && g>135 && bl<150 && r-bl>55 && g-bl>25;
            if(yellow){minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);n++;}
        }
        if(n<35 || maxX-minX<w*.22 || maxY-minY<h*.08) return null;
        int fw=maxX-minX, fh=maxY-minY;
        // Inner red display, safely inside the yellow frame.
        int ix0=minX+(int)(fw*.035), ix1=maxX-(int)(fw*.035);
        int iy0=minY+(int)(fh*.075), iy1=maxY-(int)(fh*.075);
        int iw=ix1-ix0, ih=iy1-iy0;
        if(iw<50||ih<30)return null;

        final int slots=5;
        StringBuilder out=new StringBuilder();
        boolean started=false;
        for(int i=0;i<slots;i++){
            int x0=ix0+(int)(iw*(i/(double)slots));
            int x1=ix0+(int)(iw*((i+1)/(double)slots));
            double activity=redRatio2(src,x0,iy0,x1,iy1);
            if(activity<.018){ if(started){} continue; } // blank leading cells
            int d=decodeDigitFixed(src,x0,iy0,x1,iy1);
            if(d<0) continue;
            started=true; out.append((char)('0'+d));
        }
        return out.length()>0?out.toString():null;
    }
    private int decodeDigitFixed(Bitmap b,int x0,int y0,int x1,int y1){
        double w=x1-x0,h=y1-y0;
        boolean[] q=new boolean[7];
        q[0]=redRatio2(b,x0+.22*w,y0+.01*h,x0+.78*w,y0+.18*h)>.055;
        q[1]=redRatio2(b,x0+.68*w,y0+.08*h,x0+.98*w,y0+.49*h)>.055;
        q[2]=redRatio2(b,x0+.68*w,y0+.51*h,x0+.98*w,y0+.92*h)>.055;
        q[3]=redRatio2(b,x0+.22*w,y0+.82*h,x0+.78*w,y0+.99*h)>.055;
        q[4]=redRatio2(b,x0+.02*w,y0+.51*h,x0+.32*w,y0+.92*h)>.055;
        q[5]=redRatio2(b,x0+.02*w,y0+.08*h,x0+.32*w,y0+.49*h)>.055;
        q[6]=redRatio2(b,x0+.18*w,y0+.40*h,x0+.82*w,y0+.62*h)>.055;
        int mask=0;for(int i=0;i<7;i++)if(q[i])mask|=1<<i;
        int[] m={0x3F,0x06,0x5B,0x4F,0x66,0x6D,0x7D,0x07,0x7F,0x6F};
        int bd=8,bv=-1;for(int d=0;d<10;d++){int z=Integer.bitCount(mask^m[d]);if(z<bd){bd=z;bv=d;}}
        return bd<=1?bv:-1;
    }
'''
s=s[:a]+v3+s[b:]
s=s.replace('50 Hz anti-flicker + FOTO-TESTLİ LED OKUMA + ANLIK 7-segment aktif.','50 Hz anti-flicker + EKRAN ÇERÇEVESİ KİLİTLİ + 7-segment aktif.')
p.write_text(s,encoding='utf-8')
print('DISPLAY_FRAME_DECODER_V3_OK')

# display-frame-v3-build

# 2.10.13 STRICT ACTIVE LED + ZOOM.
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
# Imports/fields for Camera2 sensor crop zoom.
s=s.replace('import android.graphics.RectF;', 'import android.graphics.RectF;\nimport android.graphics.Rect;')
s=s.replace('    private Bitmap history2;', '''    private Bitmap history2;
    private Rect activeArray;
    private float maxZoom=1f, zoom=1f;''')
# Read zoom capability.
s=s.replace('            Boolean fa = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE); flashAvailable = fa != null && fa;',
'''            Boolean fa = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE); flashAvailable = fa != null && fa;
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float mz = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = mz==null?1f:Math.max(1f,mz);''')
# Add zoom row before action row.
needle='''        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL); row2.setPadding(0, dp(9), 0, 0);'''
repl='''        LinearLayout zoomRow = new LinearLayout(this); zoomRow.setOrientation(LinearLayout.HORIZONTAL); zoomRow.setGravity(Gravity.CENTER); zoomRow.setPadding(0, dp(8), 0, 0);
        Button zoomMinus=button("ZOOM −"); Button zoomReset=button("1.0×"); Button zoomPlus=button("ZOOM +");
        LinearLayout.LayoutParams zp=new LinearLayout.LayoutParams(0,dp(46),1f); zp.setMargins(dp(4),0,dp(4),0);
        zoomRow.addView(zoomMinus,zp); zoomRow.addView(zoomReset,zp); zoomRow.addView(zoomPlus,zp); panel.addView(zoomRow);
        zoomMinus.setOnClickListener(v->{ zoom=Math.max(1f,zoom-.25f); applyCapture(); zoomReset.setText(String.format(Locale.US,"%.2f×",zoom)); });
        zoomPlus.setOnClickListener(v->{ zoom=Math.min(maxZoom,zoom+.25f); applyCapture(); zoomReset.setText(String.format(Locale.US,"%.2f×",zoom)); });
        zoomReset.setOnClickListener(v->{ zoom=1f; applyCapture(); zoomReset.setText("1.0×"); });

        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL); row2.setPadding(0, dp(9), 0, 0);'''
if needle not in s: raise SystemExit('zoom UI marker missing')
s=s.replace(needle,repl,1)
# Apply Camera2 crop zoom.
needle='''            previewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure);
            session.setRepeatingRequest(previewBuilder.build(), null, cameraHandler);'''
repl='''            previewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure);
            if(activeArray!=null && zoom>1f){
                int cw=Math.max(2,(int)(activeArray.width()/zoom)), ch=Math.max(2,(int)(activeArray.height()/zoom));
                int cx=activeArray.centerX(), cy=activeArray.centerY();
                previewBuilder.set(CaptureRequest.SCALER_CROP_REGION,new Rect(cx-cw/2,cy-ch/2,cx+cw/2,cy+ch/2));
            } else if(activeArray!=null) previewBuilder.set(CaptureRequest.SCALER_CROP_REGION,activeArray);
            session.setRepeatingRequest(previewBuilder.build(), null, cameraHandler);'''
if needle not in s: raise SystemExit('zoom capture marker missing')
s=s.replace(needle,repl,1)

# Strict decoder: yellow frame gives geometry, but a digit is accepted ONLY when exact active segment mask matches.
a=s.index('    private String decodeSevenSegmentInstant(Bitmap src){')
b=s.index('    private int[] yRange(',a)
if a<0 or b<0: raise SystemExit('strict decoder bounds missing')
strict=r'''    private String decodeSevenSegmentInstant(Bitmap src){
        int w=src.getWidth(),h=src.getHeight();
        int minX=w,minY=h,maxX=-1,maxY=-1,n=0;
        for(int y=0;y<h;y+=2)for(int x=0;x<w;x+=2){
            int c=src.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
            boolean yellow=r>180 && g>125 && bl<165 && r-bl>45 && g-bl>18;
            if(yellow){minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);n++;}
        }
        if(n<30 || maxX-minX<w*.20 || maxY-minY<h*.07) return null;
        int fw=maxX-minX,fh=maxY-minY;
        int ix0=minX+(int)(fw*.035),ix1=maxX-(int)(fw*.035);
        int iy0=minY+(int)(fh*.075),iy1=maxY-(int)(fh*.075);
        int iw=ix1-ix0;
        if(iw<50||iy1-iy0<30)return null;

        StringBuilder out=new StringBuilder();
        final int slots=5;
        for(int i=0;i<slots;i++){
            int x0=ix0+(int)(iw*(i/(double)slots)), x1=ix0+(int)(iw*((i+1)/(double)slots));
            int d=decodeDigitStrict(src,x0,iy0,x1,iy1);
            if(d>=0) out.append((char)('0'+d));
        }
        return out.length()==0?null:out.toString();
    }
    private int decodeDigitStrict(Bitmap b,int x0,int y0,int x1,int y1){
        double w=x1-x0,h=y1-y0;
        double[] z=new double[7];
        z[0]=redRatio2(b,x0+.22*w,y0+.01*h,x0+.78*w,y0+.18*h);
        z[1]=redRatio2(b,x0+.68*w,y0+.08*h,x0+.98*w,y0+.49*h);
        z[2]=redRatio2(b,x0+.68*w,y0+.51*h,x0+.98*w,y0+.92*h);
        z[3]=redRatio2(b,x0+.22*w,y0+.82*h,x0+.78*w,y0+.99*h);
        z[4]=redRatio2(b,x0+.02*w,y0+.51*h,x0+.32*w,y0+.92*h);
        z[5]=redRatio2(b,x0+.02*w,y0+.08*h,x0+.32*w,y0+.49*h);
        z[6]=redRatio2(b,x0+.18*w,y0+.40*h,x0+.82*w,y0+.62*h);
        double mx=0;for(double q:z)mx=Math.max(mx,q);
        if(mx<.10)return -1; // no genuinely lit segment in this cell
        // Adaptive threshold relative to the brightest emitted segment. Dim ghost outlines stay OFF.
        double th=Math.max(.075,mx*.42);
        int mask=0;for(int i=0;i<7;i++)if(z[i]>=th)mask|=1<<i;
        int[] m={0x3F,0x06,0x5B,0x4F,0x66,0x6D,0x7D,0x07,0x7F,0x6F};
        for(int d=0;d<10;d++) if(mask==m[d]) return d; // ZERO tolerance: never invent a missing segment
        return -1;
    }
'''
s=s[:a]+strict+s[b:]
s=s.replace('50 Hz anti-flicker + EKRAN ÇERÇEVESİ KİLİTLİ + 7-segment aktif.','50 Hz anti-flicker + AGRESİF OLMAYAN LED OKUMA + ZOOM aktif.')
p.write_text(s,encoding='utf-8')
print('STRICT_LED_ZOOM_OK')

# rebuild zoom

# 2.10.13 ACTIVE-EMISSION v4: tuned against uploaded 4 kg frame; read up to five lit digits, right-aligned.
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
# Make active LED predicate discriminate emitted yellow/red light from dim ghost outlines.
a=s.index('    private boolean isRed(Bitmap b,int x,int y){')
b=s.index('    private String decodeSevenSegmentInstant(Bitmap src){',a)
pred=r'''    private boolean isRed(Bitmap b,int x,int y){
        if(x<0||y<0||x>=b.getWidth()||y>=b.getHeight()) return false;
        int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
        int mx=Math.max(g,bl);
        // Sunlight: emitted red LED is captured as bright yellow/orange.
        boolean sun = r>=215 && g>=135 && bl<=145 && (r-bl)>=65 && (g-bl)>=18;
        // Shade/indoor: emitted LED remains saturated red.
        boolean red = r>=205 && (r-mx)>=85 && bl<=135;
        return sun || red;
    }
'''
s=s[:a]+pred+s[b:]
# Replace strict digit thresholds with narrow-bar-friendly sampling.
s=s.replace('if(mx<.10)return -1;', 'if(mx<.018)return -1;')
s=s.replace('double th=Math.max(.075,mx*.42);', 'double th=Math.max(.012,mx*.30);')
# Ensure no fuzzy matching remains and expose useful status.
s=s.replace('50 Hz anti-flicker + AGRESİF OLMAYAN LED OKUMA + ZOOM aktif.','50 Hz anti-flicker + AKTİF LED IŞIĞI + 5 HANE + ZOOM aktif.')
p.write_text(s,encoding='utf-8')
print('ACTIVE_EMISSION_V4_OK')

# active-emission-v4-build

# 2.10.13 DUAL-LIGHT v5: tolerate both sun-washed yellow LED and shaded red LED.
# Screen lock is no longer dependent on one exact bezel colour: try yellow frame first, then the 5-digit panel band.
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
a=s.index('    private String decodeSevenSegmentInstant(Bitmap src){')
b=s.index('    private int[] yRange(',a)
v5=r'''    private String decodeSevenSegmentInstant(Bitmap src){
        int w=src.getWidth(),h=src.getHeight();
        // Candidate active-light bounds. Works for red LED in shade and yellow/orange LED in hard sun.
        int lx=w,ly=h,rx=-1,ry=-1,active=0;
        for(int y=(int)(h*.08);y<(int)(h*.88);y+=2)for(int x=(int)(w*.04);x<(int)(w*.96);x+=2){
            if(isRed(src,x,y)){lx=Math.min(lx,x);rx=Math.max(rx,x);ly=Math.min(ly,y);ry=Math.max(ry,y);active++;}
        }
        if(active<12 || rx<0) return null;

        // Find yellow bezel when visible; it is the best five-cell reference.
        int minX=w,minY=h,maxX=-1,maxY=-1,n=0;
        for(int y=0;y<h;y+=2)for(int x=0;x<w;x+=2){
            int c=src.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
            boolean yellow=r>170 && g>105 && bl<175 && r-bl>32 && g-bl>10;
            if(yellow){minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);n++;}
        }

        int ix0,ix1,iy0,iy1;
        if(n>=22 && maxX-minX>w*.18 && maxY-minY>h*.055){
            int fw=maxX-minX,fh=maxY-minY;
            ix0=minX+(int)(fw*.025); ix1=maxX-(int)(fw*.025);
            iy0=minY+(int)(fh*.055); iy1=maxY-(int)(fh*.055);
        } else {
            // Bezel lost in glare: infer the complete 5-cell band from the lit digit height/pitch.
            int ah=Math.max(18,ry-ly), pitch=(int)(ah*.72);
            iy0=Math.max(0,ly-(int)(ah*.08)); iy1=Math.min(h,ry+(int)(ah*.08));
            // The crane display is right-aligned: lit units digit anchors the five-cell panel.
            ix1=Math.min(w,rx+(int)(pitch*.22)); ix0=Math.max(0,ix1-5*pitch);
        }
        int iw=ix1-ix0, ih=iy1-iy0;
        if(iw<45||ih<22)return null;

        StringBuilder out=new StringBuilder();
        final int slots=5;
        for(int i=0;i<slots;i++){
            int x0=ix0+(int)(iw*(i/(double)slots)),x1=ix0+(int)(iw*((i+1)/(double)slots));
            int d=decodeDigitDual(src,x0,iy0,x1,iy1);
            if(d>=0)out.append((char)('0'+d));
        }
        return out.length()==0?null:out.toString();
    }
    private int decodeDigitDual(Bitmap b,int x0,int y0,int x1,int y1){
        double w=x1-x0,h=y1-y0;
        double[] z=new double[7];
        z[0]=redRatio2(b,x0+.18*w,y0+.00*h,x0+.82*w,y0+.20*h);
        z[1]=redRatio2(b,x0+.62*w,y0+.05*h,x0+.99*w,y0+.50*h);
        z[2]=redRatio2(b,x0+.62*w,y0+.50*h,x0+.99*w,y0+.95*h);
        z[3]=redRatio2(b,x0+.18*w,y0+.80*h,x0+.82*w,y0+1.00*h);
        z[4]=redRatio2(b,x0+.01*w,y0+.50*h,x0+.38*w,y0+.95*h);
        z[5]=redRatio2(b,x0+.01*w,y0+.05*h,x0+.38*w,y0+.50*h);
        z[6]=redRatio2(b,x0+.14*w,y0+.38*h,x0+.86*w,y0+.64*h);
        double mx=0;for(double q:z)mx=Math.max(mx,q);
        if(mx<.010)return -1;
        // A lit segment must be significant relative to the strongest emitted segment in its own digit.
        double th=Math.max(.007,mx*.24);
        int mask=0,on=0;for(int i=0;i<7;i++)if(z[i]>=th){mask|=1<<i;on++;}
        if(on<2)return -1;
        int[] m={0x3F,0x06,0x5B,0x4F,0x66,0x6D,0x7D,0x07,0x7F,0x6F};
        // Exact first; one-segment tolerance only when that segment is borderline, never synthesize from ghost cells.
        for(int d=0;d<10;d++)if(mask==m[d])return d;
        int best=-1,dist=8;for(int d=0;d<10;d++){int dd=Integer.bitCount(mask^m[d]);if(dd<dist){dist=dd;best=d;}}
        return dist==1 && mx>.025 ? best : -1;
    }
'''
s=s[:a]+v5+s[b:]
s=s.replace('50 Hz anti-flicker + AKTİF LED IŞIĞI + 5 HANE + ZOOM aktif.','50 Hz anti-flicker + GÜNEŞ/GÖLGE LED + 5 HANE + ZOOM aktif.')
p.write_text(s,encoding='utf-8')
print('DUAL_LIGHT_V5_OK')

# dual-light-v5-build


# 2.10.13 STABLE-LOCK v7 - robust patch: locate pushSample method by braces, independent of following method name.
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
needle='    private void pushSample('
a=s.index(needle)
brace=s.index('{',a)
depth=0; end=-1
for i in range(brace,len(s)):
    if s[i]=='{': depth+=1
    elif s[i]=='}':
        depth-=1
        if depth==0:
            end=i+1; break
if end<0: raise SystemExit('pushSample method end missing')
lock=r'''    private Double lockedKg=null, pendingKg=null;
    private int pendingHits=0;
    private void pushSample(double value,String raw){
        if(value<0 || value>99999) return;
        final double v=Math.rint(value);
        if(lockedKg==null){
            if(pendingKg!=null && Math.abs(pendingKg-v)<0.1) pendingHits++; else {pendingKg=v;pendingHits=1;}
            if(pendingHits>=2){
                lockedKg=v; pendingKg=null; pendingHits=0;
                runOnUiThread(()->{weightText.setText(String.format(Locale.US,"%.0f kg",lockedKg));stateText.setText("SABİT · KİLİTLİ");detailText.setText("Ekran değeri sabitlendi");});
            }
            return;
        }
        if(Math.abs(lockedKg-v)<0.1){
            pendingKg=null; pendingHits=0;
            runOnUiThread(()->{weightText.setText(String.format(Locale.US,"%.0f kg",lockedKg));stateText.setText("SABİT · KİLİTLİ");detailText.setText("Geçici okumalar kilidi bozamaz");});
            return;
        }
        if(pendingKg!=null && Math.abs(pendingKg-v)<0.1) pendingHits++; else {pendingKg=v;pendingHits=1;}
        if(pendingHits>=5){
            lockedKg=v; pendingKg=null; pendingHits=0;
            runOnUiThread(()->{weightText.setText(String.format(Locale.US,"%.0f kg",lockedKg));stateText.setText("YENİ DEĞER · KİLİTLİ");detailText.setText("Yeni değer doğrulandı");});
        } else {
            runOnUiThread(()->{weightText.setText(String.format(Locale.US,"%.0f kg",lockedKg));stateText.setText("SABİT · DOĞRULANIYOR");detailText.setText("Geçici farklı okumalar gösterilmez");});
        }
    }'''
s=s[:a]+lock+s[end:]
s=s.replace('50 Hz anti-flicker + GÜNEŞ/GÖLGE LED + 5 HANE + ZOOM aktif.','50 Hz anti-flicker + GÜNEŞ/GÖLGE + 5 HANE + AKILLI SABİTLEME aktif.')
p.write_text(s,encoding='utf-8')
print('STABLE_LOCK_V7_OK')

# stable-lock-v7-final-build

# v7-ui-name-fix-build

# 2.10.13 V8 - ONE READER ONLY: direct 7-segment owns the weight. OCR may not publish/lock a number.
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
# Route every direct decoder result through the stable-lock gate.
s=s.replace('pushInstantSegment(Double.parseDouble(segValue), segValue)','pushSample(Double.parseDouble(segValue), segValue)')
s=s.replace('pushInstantSegment(Double.parseDouble(direct), direct)','pushSample(Double.parseDouble(direct), direct)')
# Disable OCR text handler completely; it was able to lock 1 while the physical display showed 2.
needle='    private void handleText('
if needle in s:
    a=s.index(needle); brace=s.index('{',a); depth=0; end=-1
    for i in range(brace,len(s)):
        if s[i]=='{': depth+=1
        elif s[i]=='}':
            depth-=1
            if depth==0: end=i+1; break
    sig=s[a:brace]
    s=s[:a]+sig+'{ /* V8: OCR MUST NEVER publish a scale value. Direct 7-segment only. */ }'+s[end:]
# Remove fuzzy one-segment guessing: exact masks only. A wrong digit must never be invented.
s=s.replace('return dist==1 && mx>.025 ? best : -1;','return -1;')
# If a valid number exists it must be one contiguous right-aligned block; blank cells only on the left.
old="""        StringBuilder out=new StringBuilder();
        final int slots=5;
        for(int i=0;i<slots;i++){
            int x0=ix0+(int)(iw*(i/(double)slots)),x1=ix0+(int)(iw*((i+1)/(double)slots));
            int d=decodeDigitDual(src,x0,iy0,x1,iy1);
            if(d>=0)out.append((char)('0'+d));
        }
        return out.length()==0?null:out.toString();"""
new="""        StringBuilder out=new StringBuilder();
        final int slots=5; boolean started=false, gap=false;
        for(int i=0;i<slots;i++){
            int x0=ix0+(int)(iw*(i/(double)slots)),x1=ix0+(int)(iw*((i+1)/(double)slots));
            int d=decodeDigitDual(src,x0,iy0,x1,iy1);
            if(d>=0){
                if(gap) return null;
                started=true; out.append((char)('0'+d));
            } else if(started) gap=true;
        }
        return out.length()==0?null:out.toString();"""
if old in s: s=s.replace(old,new,1)
s=s.replace('50 Hz anti-flicker + GÜNEŞ/GÖLGE + 5 HANE + AKILLI SABİTLEME aktif.','50 Hz + 5 HANE + DOĞRUDAN 7-SEGMENT + AKILLI SABİTLEME aktif.')
p.write_text(s,encoding='utf-8')
print('V8_DIRECT_ONLY_OK')

# v8-direct-only-build

# 2.10.13 V9 - FIX PANEL GEOMETRY: local yellow bezel only; never let the yellow sticker/body expand the 5-digit panel.
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
a=s.index('    private String decodeSevenSegmentInstant(Bitmap src){')
b=s.index('    private int decodeDigitDual(',a)
v9=r'''    private String decodeSevenSegmentInstant(Bitmap src){
        int w=src.getWidth(),h=src.getHeight();
        int lx=w,ly=h,rx=-1,ry=-1,active=0;
        for(int y=(int)(h*.12);y<(int)(h*.78);y+=2)for(int x=(int)(w*.08);x<(int)(w*.92);x+=2){
            if(isRed(src,x,y)){lx=Math.min(lx,x);rx=Math.max(rx,x);ly=Math.min(ly,y);ry=Math.max(ry,y);active++;}
        }
        if(active<8||rx<0)return null;

        // Search bezel ONLY around the LED row. Previous global yellow bbox could include the yellow capacity sticker.
        int sx0=Math.max(0,lx-(int)(h*.55)), sx1=Math.min(w-1,rx+(int)(h*.08));
        int sy0=Math.max(0,ly-(int)(h*.16)), sy1=Math.min(h-1,ry+(int)(h*.16));
        int minX=w,minY=h,maxX=-1,maxY=-1,n=0;
        for(int y=sy0;y<=sy1;y+=2)for(int x=sx0;x<=sx1;x+=2){
            int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
            boolean yellow=r>135&&g>85&&bl<150&&(r-bl)>28&&(g-bl)>8;
            if(yellow){minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);n++;}
        }
        int ix0,ix1,iy0,iy1;
        if(n>12 && maxX-minX>Math.max(70,(rx-lx)*2) && maxY-minY>20 && (maxX-minX)>(maxY-minY)*2.2){
            int fw=maxX-minX,fh=maxY-minY;
            ix0=minX+(int)(fw*.025);ix1=maxX-(int)(fw*.025);
            iy0=minY+(int)(fh*.08);iy1=maxY-(int)(fh*.08);
        } else {
            // Units digit is the rightmost cell. Derive five-cell pitch from its LED height.
            int ah=Math.max(24,ry-ly+1);
            double pitch=ah*.62;
            ix1=Math.min(w,rx+(int)(pitch*.28));
            ix0=Math.max(0,ix1-(int)(5*pitch));
            iy0=Math.max(0,ly-(int)(ah*.08));iy1=Math.min(h,ry+(int)(ah*.08));
        }
        int iw=ix1-ix0,ih=iy1-iy0;if(iw<80||ih<20)return null;

        int[] ds=new int[5];
        for(int i=0;i<5;i++){
            int x0=ix0+(int)(iw*(i/5.0)),x1=ix0+(int)(iw*((i+1)/5.0));
            ds[i]=decodeDigitDual(src,x0,iy0,x1,iy1);
        }
        // Weight is a contiguous right-aligned block. No gaps, no guessed ghost leading digits.
        int first=0;while(first<5&&ds[first]<0)first++;
        if(first==5)return null;
        for(int i=first;i<5;i++)if(ds[i]<0)return null;
        StringBuilder out=new StringBuilder();for(int i=first;i<5;i++)out.append((char)('0'+ds[i]));
        return out.toString();
    }
'''
# correct accidental source variable name before writing Java
v9=v9.replace('int c=b.getPixel(x,y)','int c=src.getPixel(x,y)')
s=s[:a]+v9+s[b:]
# Exact masks only: no Hamming-distance digit invention.
old='''        int best=-1,dist=8;for(int d=0;d<10;d++){int dd=Integer.bitCount(mask^m[d]);if(dd<dist){dist=dd;best=d;}}
        return dist==1 && mx>.025 ? best : -1;'''
if old in s:s=s.replace(old,'        return -1;',1)
s=s.replace('50 Hz + 5 HANE + DOĞRUDAN 7-SEGMENT + AKILLI SABİTLEME aktif.','50 Hz + 5 HANE + PANEL KİLİDİ + DOĞRUDAN 7-SEGMENT aktif.')
p.write_text(s,encoding='utf-8')
print('V9_PANEL_GEOMETRY_OK')

# v9-panel-geometry-build

# 2.10.13 V10 - CAMERA MOTION ROBUST: detect the rightmost active digit directly.
# Do not require yellow bezel or fixed 5-cell geometry for a one/few digit right-aligned weight.
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
a=s.index('    private String decodeSevenSegmentInstant(Bitmap src){')
b=s.index('    private int decodeDigitDual(',a)
v10=r'''    private String decodeSevenSegmentInstant(Bitmap src){
        int w=src.getWidth(),h=src.getHeight();
        // Connected active-light bounds inside guide-friendly central field; isolated STEADY/TARE lamps are rejected by aspect/segment decode.
        boolean[][] seen=new boolean[(h+3)/4][(w+3)/4];
        java.util.ArrayList<int[]> comps=new java.util.ArrayList<>();
        int H=seen.length,W=seen[0].length;
        for(int gy=1;gy<H-1;gy++)for(int gx=1;gx<W-1;gx++){
            if(seen[gy][gx]||!isRed(src,gx*4,gy*4))continue;
            int minx=gx,maxx=gx,miny=gy,maxy=gy,n=0;
            java.util.ArrayDeque<int[]> q=new java.util.ArrayDeque<>();q.add(new int[]{gx,gy});seen[gy][gx]=true;
            while(!q.isEmpty()){
                int[] z=q.removeFirst();int x=z[0],y=z[1];n++;minx=Math.min(minx,x);maxx=Math.max(maxx,x);miny=Math.min(miny,y);maxy=Math.max(maxy,y);
                for(int yy=Math.max(0,y-2);yy<=Math.min(H-1,y+2);yy++)for(int xx=Math.max(0,x-2);xx<=Math.min(W-1,x+2);xx++){
                    if(!seen[yy][xx]&&isRed(src,xx*4,yy*4)){seen[yy][xx]=true;q.add(new int[]{xx,yy});}
                }
            }
            int bw=(maxx-minx+1)*4,bh=(maxy-miny+1)*4;
            if(n>=5&&bh>=14&&bw>=5&&bh>bw*.65)comps.add(new int[]{minx*4,miny*4,(maxx+1)*4,(maxy+1)*4,n});
        }
        if(comps.isEmpty())return null;
        // Rightmost plausible seven-segment component is units digit. Expand bbox because 2/3/7 can fragment at corners.
        comps.sort((u,v)->Integer.compare(v[2],u[2]));
        for(int[] c:comps){
            int bh=c[3]-c[1],bw=c[2]-c[0];
            int padX=Math.max(8,(int)(bh*.28)),padY=Math.max(5,(int)(bh*.10));
            int x0=Math.max(0,c[0]-padX),x1=Math.min(w,c[2]+padX),y0=Math.max(0,c[1]-padY),y1=Math.min(h,c[3]+padY);
            int d=decodeDigitDual(src,x0,y0,x1,y1);
            if(d>=0){
                // Build preceding digits using measured digit height; blanks on left are normal.
                double pitch=Math.max(18,(y1-y0)*.62);
                java.util.ArrayList<Integer> vals=new java.util.ArrayList<>();vals.add(d);
                for(int k=1;k<5;k++){
                    int cx1=(int)(x1-k*pitch),cx0=(int)(x0-k*pitch);
                    if(cx1<=0)break;
                    int pd=decodeDigitDual(src,Math.max(0,cx0),y0,Math.min(w,cx1),y1);
                    if(pd<0)break; vals.add(0,pd);
                }
                StringBuilder out=new StringBuilder();for(int z:vals)out.append((char)('0'+z));
                return out.toString();
            }
        }
        return null;
    }
'''
s=s[:a]+v10+s[b:]
# Slightly widen active LED chroma acceptance for sun/shade while retaining strong emission contrast.
old='''        boolean warm = r>190 && g>115 && (g-bl)>12 && (r-bl)>55;
        // In shade an active LED is deep, very bright red. Dim inactive "8" outlines are rejected.
        boolean deep = r>205 && g<112 && bl<112 && r-Math.max(g,bl)>100;'''
new='''        boolean warm = r>175 && g>90 && (g-bl)>8 && (r-bl)>45;
        // In shade an active LED is deep bright red; require strong red dominance so ghost 8 outlines stay off.
        boolean deep = r>180 && r-Math.max(g,bl)>75;'''
if old in s:s=s.replace(old,new,1)
s=s.replace('50 Hz + 5 HANE + PANEL KİLİDİ + DOĞRUDAN 7-SEGMENT aktif.','50 Hz + HAREKETLİ EKRAN + DOĞRUDAN 7-SEGMENT aktif.')
p.write_text(s,encoding='utf-8')
print('V10_MOTION_ROBUST_OK')

# v10-motion-robust-build

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

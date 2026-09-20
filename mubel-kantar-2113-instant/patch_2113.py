from pathlib import Path
import sys
root=Path(sys.argv[1]); p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
s=s.replace('private static final long OCR_PERIOD_MS = 120;','private static final long OCR_PERIOD_MS = 80;')
needle='''Bitmap boosted = redLedBoost(composite);
            InputImage img = InputImage.fromBitmap(boosted,0);'''
repl='''Bitmap boosted = redLedBoost(composite);
            final String segValue = decodeSevenSegmentInstant(composite);
            if (segValue != null) {
                main.post(() -> {
                    try { pushInstantSegment(Double.parseDouble(segValue), segValue); } catch(Exception ignored) {}
                });
                boosted.recycle(); return;
            }
            InputImage img = InputImage.fromBitmap(boosted,0);'''
if needle not in s: raise SystemExit('needle missing')
s=s.replace(needle,repl,1)
marker='''    private Bitmap redLedBoost(Bitmap src) {'''
methods=r'''    private String lastSeg=null; private int segHits=0;
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
        return r>95 && r>g*1.22 && r>bl*1.12 && r-Math.max(g,bl)>22;
    }
    private String decodeSevenSegmentInstant(Bitmap src){
        int w=src.getWidth(),h=src.getHeight();
        // Projection: isolated red LEDs (TARE etc.) are ignored by requiring a wide horizontal LED band.
        int[] rows=new int[h];
        for(int y=0;y<h;y+=2) for(int x=0;x<w;x+=2) if(isRed(src,x,y)) rows[y]++;
        int bestY=-1,best=0;
        for(int y=0;y<h;y+=2){int z=0;for(int yy=Math.max(0,y-h/14);yy<=Math.min(h-1,y+h/14);yy+=2)z+=rows[yy];if(z>best){best=z;bestY=y;}}
        if(bestY<0||best<25)return null;
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
            if(cnt<8||dy1-dy0<10)continue;
            int val=decodeDigitFlexible(src,dx0,dy0,dx1,dy1);
            if(val<0)return null; out.append((char)('0'+val));
        }
        return out.length()>0?out.toString():null;
    }
    private int decodeDigitFlexible(Bitmap b,int x0,int y0,int x1,int y1){
        int w=Math.max(1,x1-x0),h=Math.max(1,y1-y0);
        boolean[] q=new boolean[7];
        q[0]=redRatio2(b,x0+.12*w,y0,x0+.88*w,y0+.25*h)>.035;
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
s=s.replace('50 Hz anti-flicker + hızlı çok-kare + kırmızı 7-segment güçlendirme aktif.','50 Hz anti-flicker + ANLIK 7-segment okuma + OCR yedek aktif.')
p.write_text(s,encoding='utf-8')
g=root/'app/build.gradle';x=g.read_text(encoding='utf-8')
x=x.replace("applicationId 'com.mubel.kantar.v2112camera'","applicationId 'com.mubel.kantar.v2113instant'")
x=x.replace('versionCode 2112','versionCode 2113')
x=x.replace("versionName '2.10.12-CAMERA-BOOST-STABIL'","versionName '2.10.13-INSTANT-CAMERA-STABIL'")
g.write_text(x,encoding='utf-8')
print('PATCH_2113_INSTANT_OK')

# build trigger 2026-09-20

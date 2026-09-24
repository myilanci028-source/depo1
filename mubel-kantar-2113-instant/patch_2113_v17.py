from pathlib import Path
import sys

root=Path(sys.argv[1])
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')

start=s.index('    private String decodeSevenSegmentInstant(Bitmap src){')
brace=s.index('{',start)
depth=0; end=-1
for i in range(brace,len(s)):
    if s[i]=='{': depth+=1
    elif s[i]=='}':
        depth-=1
        if depth==0:
            end=i+1
            break
if end<0: raise SystemExit('decodeSevenSegmentInstant end not found')

entry=r'''    private String decodeSevenSegmentInstant(Bitmap src){
        String v=decodeV17RelativePanel(src); if(v!=null)return v;
        android.graphics.Matrix m=new android.graphics.Matrix();
        for(int deg:new int[]{90,180,270}){
            Bitmap r=null;
            try{
                m.reset();m.postRotate(deg);
                r=Bitmap.createBitmap(src,0,0,src.getWidth(),src.getHeight(),m,true);
                v=decodeV17RelativePanel(r);
                if(v!=null)return v;
            }catch(Exception ignored){}finally{if(r!=null&&r!=src)r.recycle();}
        }
        return null;
    }
'''
s=s[:start]+entry+s[end:]

marker='    private String decodeV16PhotoPanel(Bitmap b){'
if marker not in s: raise SystemExit('V16 marker missing')

helpers=r'''    private String decodeV17RelativePanel(Bitmap b){
        int[] frame=findSimpleYellowFrame(b);
        if(frame==null)frame=findFrameByYellowBands(b);
        if(frame==null)return null;
        int[] g=refineGhostGridV17(b,frame);
        if(g==null)return null;
        int x0=g[0],y0=g[1],x1=g[2],y1=g[3],W=x1-x0,H=y1-y0;
        if(W<70||H<22)return null;
        double ar=W/(double)Math.max(1,H);
        if(ar<2.4||ar>4.2)return null;
        int medR=medianRedV17(b,x0,y0,x1,y1);
        boolean bright=medR>120;
        int[] digits=new int[5];
        for(int i=0;i<5;i++){
            double cw=W/5.0,sx=x0+i*cw;
            double[][] q={
                {sx+.18*cw,y0+.07*H,sx+.75*cw,y0+.17*H},
                {sx+.72*cw,y0+.16*H,sx+.90*cw,y0+.44*H},
                {sx+.72*cw,y0+.56*H,sx+.90*cw,y0+.84*H},
                {sx+.18*cw,y0+.83*H,sx+.75*cw,y0+.94*H},
                {sx+.08*cw,y0+.56*H,sx+.26*cw,y0+.84*H},
                {sx+.08*cw,y0+.16*H,sx+.26*cw,y0+.44*H},
                {sx+.18*cw,y0+.45*H,sx+.75*cw,y0+.56*H}
            };
            int mask=0;
            for(int k=0;k<7;k++){
                double score=bright?meanGreenV17(b,q[k]):p75RedV17(b,q[k]);
                boolean on=bright?score>140.0:score>120.0;
                if(on)mask|=1<<k;
            }
            digits[i]=mask==0?-1:exactDigitV16(mask);
        }
        int first=0;while(first<5&&digits[first]<0)first++;
        if(first==5)return null;
        for(int i=first;i<5;i++)if(digits[i]<0)return null;
        StringBuilder out=new StringBuilder();for(int i=first;i<5;i++)out.append((char)('0'+digits[i]));
        return out.toString();
    }

    private int[] refineGhostGridV17(Bitmap b,int[] f){
        int fw=f[2]-f[0],fh=f[3]-f[1];
        int sx0=Math.max(0,f[0]-(int)(fw*.05)),sx1=Math.min(b.getWidth(),f[2]+(int)(fw*.05));
        int sy0=Math.max(0,f[1]-(int)(fh*.05)),sy1=Math.min(b.getHeight(),f[3]+(int)(fh*.05));
        int minx=sx1,miny=sy1,maxx=-1,maxy=-1,n=0;
        for(int y=sy0;y<sy1;y++)for(int x=sx0;x<sx1;x++){
            int c=b.getPixel(x,y),r=Color.red(c),gg=Color.green(c),bl=Color.blue(c);
            if(r>55 && r*100>gg*130 && r*100>bl*105){
                n++;minx=Math.min(minx,x);maxx=Math.max(maxx,x);miny=Math.min(miny,y);maxy=Math.max(maxy,y);
            }
        }
        if(n<50||maxx<=minx||maxy<=miny)return null;
        return new int[]{minx,miny,maxx+1,maxy+1};
    }

    private int medianRedV17(Bitmap b,int x0,int y0,int x1,int y1){
        int[] h=new int[256];int n=0;
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){h[Color.red(b.getPixel(x,y))]++;n++;}
        int t=(n+1)/2,s=0;for(int i=0;i<256;i++){s+=h[i];if(s>=t)return i;}return 0;
    }

    private double meanGreenV17(Bitmap b,double[] q){
        int x0=Math.max(0,(int)Math.floor(q[0])),y0=Math.max(0,(int)Math.floor(q[1]));
        int x1=Math.min(b.getWidth(),(int)Math.ceil(q[2])),y1=Math.min(b.getHeight(),(int)Math.ceil(q[3]));
        long sum=0;int n=0;
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){sum+=Color.green(b.getPixel(x,y));n++;}
        return n==0?0:sum/(double)n;
    }

    private double p75RedV17(Bitmap b,double[] q){
        int x0=Math.max(0,(int)Math.floor(q[0])),y0=Math.max(0,(int)Math.floor(q[1]));
        int x1=Math.min(b.getWidth(),(int)Math.ceil(q[2])),y1=Math.min(b.getHeight(),(int)Math.ceil(q[3]));
        int[] h=new int[256];int n=0;
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){h[Color.red(b.getPixel(x,y))]++;n++;}
        if(n==0)return 0;int t=(int)Math.ceil(n*.75),s=0;for(int i=0;i<256;i++){s+=h[i];if(s>=t)return i;}return 0;
    }

'''
s=s.replace(marker,helpers+marker,1)
s=s.replace('50 Hz + GERÇEK PANEL + SÖNÜK 8 FİLTRESİ aktif.','50 Hz + GÖRECELİ PARLAKLIK + 5 HANE 7-SEGMENT aktif.')
p.write_text(s,encoding='utf-8')
print('V17_RELATIVE_PANEL_OK')

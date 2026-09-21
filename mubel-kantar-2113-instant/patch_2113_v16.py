from pathlib import Path
import sys

root=Path(sys.argv[1])
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')

# Replace only the entry method; keep V14/V15 yellow-frame helpers available.
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
        String v=decodeV16PhotoPanel(src); if(v!=null)return v;
        android.graphics.Matrix m=new android.graphics.Matrix();
        for(int deg:new int[]{90,180,270}){
            Bitmap r=null;
            try{
                m.reset();m.postRotate(deg);
                r=Bitmap.createBitmap(src,0,0,src.getWidth(),src.getHeight(),m,true);
                v=decodeV16PhotoPanel(r);
                if(v!=null)return v;
            }catch(Exception ignored){}finally{if(r!=null&&r!=src)r.recycle();}
        }
        return null;
    }
'''
s=s[:start]+entry+s[end:]

marker='    private int decodeDigitDual('
if marker not in s: raise SystemExit('decodeDigitDual marker missing')

helpers=r'''    private String decodeV16PhotoPanel(Bitmap b){
        int[] yellow=findSimpleYellowFrame(b);
        if(yellow==null)yellow=findFrameByYellowBands(b);
        if(yellow==null)return null;
        int[] f=refineRedPanelV16(b,yellow);
        if(f==null)return null;

        int rawH=f[3]-f[1];
        int x0=f[0],x1=f[2];
        int y0=f[1]+(int)(rawH*.07),y1=f[3]-(int)(rawH*.07);
        int W=x1-x0,H=y1-y0;
        if(W<70||H<22)return null;

        int med=panelMedianV16(b,x0,y0,x1,y1);
        boolean bright=med>150;
        int[] d=new int[5];
        double[] th={.15,.15,.15,.18,.15,.15,.15};
        for(int i=0;i<5;i++){
            double sx=x0+W*(i/5.0), sw=W/5.0;
            double lx=sx+.28*sw, rx=sx+.73*sw;
            double yt=y0+.18*H, ym=y0+.53*H, yb=y0+.86*H;
            double t=Math.max(1.0,sw*.055);
            double hx0=sx+.36*sw, hx1=sx+.65*sw;
            double uy0=y0+.24*H,uy1=y0+.47*H,ly0=y0+.60*H,ly1=y0+.82*H;
            double[] z=new double[7];
            z[0]=activeRatioV16(b,hx0,yt-t,hx1,yt+t+1,bright,med);
            z[1]=activeRatioV16(b,rx-t,uy0,rx+t+1,uy1,bright,med);
            z[2]=activeRatioV16(b,rx-t,ly0,rx+t+1,ly1,bright,med);
            z[3]=activeRatioV16(b,hx0,yb-t,hx1,yb+t+1,bright,med);
            z[4]=activeRatioV16(b,lx-t,ly0,lx+t+1,ly1,bright,med);
            z[5]=activeRatioV16(b,lx-t,uy0,lx+t+1,uy1,bright,med);
            z[6]=activeRatioV16(b,hx0,ym-t,hx1,ym+t+1,bright,med);
            int mask=0;for(int k=0;k<7;k++)if(z[k]>=th[k])mask|=1<<k;
            d[i]=mask==0?-1:exactDigitV16(mask);
        }
        if(d[4]<0)return null;
        int first=4;while(first>0&&d[first-1]>=0)first--;
        for(int i=first;i<5;i++)if(d[i]<0)return null;
        StringBuilder out=new StringBuilder();for(int i=first;i<5;i++)out.append((char)('0'+d[i]));
        return out.toString();
    }

    private int[] refineRedPanelV16(Bitmap b,int[] f){
        int fw=f[2]-f[0],fh=f[3]-f[1];
        int sx0=Math.max(0,f[0]-(int)(fw*.08)),sx1=Math.min(b.getWidth(),f[2]+(int)(fw*.08));
        int sy0=Math.max(0,f[1]-(int)(fh*.08)),sy1=Math.min(b.getHeight(),f[3]+(int)(fh*.08));
        int minx=sx1,miny=sy1,maxx=-1,maxy=-1,n=0;
        for(int y=sy0;y<sy1;y++)for(int x=sx0;x<sx1;x++){
            int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
            if(r>90 && r*100>g*120 && r*100>bl*105){
                n++;minx=Math.min(minx,x);maxx=Math.max(maxx,x);miny=Math.min(miny,y);maxy=Math.max(maxy,y);
            }
        }
        if(n<30||maxx<=minx||maxy<=miny)return null;
        int w=maxx-minx+1,h=maxy-miny+1;double ar=w/(double)Math.max(1,h);
        if(w<60||h<18||ar<2.0||ar>5.5)return null;
        return new int[]{minx,miny,maxx+1,maxy+1};
    }

    private int panelMedianV16(Bitmap b,int x0,int y0,int x1,int y1){
        int[] hist=new int[256];int n=0;
        for(int y=y0;y<y1;y+=2)for(int x=x0;x<x1;x+=2){
            int c=b.getPixel(x,y),v=Math.max(Color.red(c),Math.max(Color.green(c),Color.blue(c)));
            hist[v]++;n++;
        }
        int half=(n+1)/2,s=0;for(int v=0;v<256;v++){s+=hist[v];if(s>=half)return v;}return 0;
    }

    private boolean activePixelV16(Bitmap b,int x,int y,boolean bright,int med){
        if(x<0||y<0||x>=b.getWidth()||y>=b.getHeight())return false;
        int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
        if(bright)return r>=200&&g>=130&&(g-bl)>=30&&(r-bl)>=45;
        return r>=180&&(r-Math.max(g,bl))>=80&&r>=med+60;
    }

    private double activeRatioV16(Bitmap b,double ax,double ay,double bx,double by,boolean bright,int med){
        int x0=Math.max(0,(int)Math.floor(ax)),y0=Math.max(0,(int)Math.floor(ay));
        int x1=Math.min(b.getWidth(),(int)Math.ceil(bx)),y1=Math.min(b.getHeight(),(int)Math.ceil(by));
        int n=0,hit=0;
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){n++;if(activePixelV16(b,x,y,bright,med))hit++;}
        return n==0?0:hit/(double)n;
    }

    private int exactDigitV16(int mask){
        int[] m={0x3F,0x06,0x5B,0x4F,0x66,0x6D,0x7D,0x07,0x7F,0x6F};
        for(int d=0;d<10;d++)if(mask==m[d])return d;
        return -1;
    }

'''
s=s.replace(marker,helpers+marker,1)
s=s.replace('50 Hz + 1-5 HANE + KIRMIZI/TURUNCU/SARI SEGMENT aktif.','50 Hz + GERÇEK PANEL + SÖNÜK 8 FİLTRESİ aktif.')
p.write_text(s,encoding='utf-8')
print('V16_PHOTO_PANEL_OK')

from pathlib import Path
import sys
root=Path(sys.argv[1])
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')
a=s.index('    private String decodeSevenSegmentInstant(Bitmap src){')
b=s.index('    private int decodeDigitDual(',a)
v14=r'''    private String decodeSevenSegmentInstant(Bitmap src){
        String v=decodeSimplePanel(src); if(v!=null)return v;
        android.graphics.Matrix m=new android.graphics.Matrix();
        for(int deg:new int[]{90,180,270}){
            Bitmap r=null;
            try{m.reset();m.postRotate(deg);r=Bitmap.createBitmap(src,0,0,src.getWidth(),src.getHeight(),m,true);v=decodeSimplePanel(r);if(v!=null)return v;}
            catch(Exception ignored){}finally{if(r!=null&&r!=src)r.recycle();}
        }
        return null;
    }

    private String decodeSimplePanel(Bitmap b){
        int[] f=findSimpleYellowFrame(b); if(f==null)return null;
        int fw=f[2]-f[0],fh=f[3]-f[1];
        int ix0=f[0]+(int)(fw*.025),ix1=f[2]-(int)(fw*.025);
        int iy0=f[1]+(int)(fh*.07),iy1=f[3]-(int)(fh*.07);
        int iw=ix1-ix0,ih=iy1-iy0;if(iw<70||ih<24)return null;
        int[] d=new int[5];
        for(int i=0;i<5;i++){
            int x0=ix0+(int)(iw*(i/5.0)),x1=ix0+(int)(iw*((i+1)/5.0));
            double w=x1-x0,h=iy1-iy0;
            double[] z=new double[7];
            z[0]=emissionRatio(b,x0+.38*w,iy0+.06*h,x0+.78*w,iy0+.18*h);
            z[1]=emissionRatio(b,x0+.80*w,iy0+.15*h,x0+.99*w,iy0+.46*h);
            z[2]=emissionRatio(b,x0+.80*w,iy0+.54*h,x0+.99*w,iy0+.85*h);
            z[3]=emissionRatio(b,x0+.38*w,iy0+.82*h,x0+.78*w,iy0+.94*h);
            z[4]=emissionRatio(b,x0+.20*w,iy0+.54*h,x0+.45*w,iy0+.85*h);
            z[5]=emissionRatio(b,x0+.20*w,iy0+.15*h,x0+.45*w,iy0+.46*h);
            z[6]=emissionRatio(b,x0+.34*w,iy0+.44*h,x0+.86*w,iy0+.58*h);
            int mask=0;for(int k=0;k<7;k++)if(z[k]>=.09)mask|=1<<k;
            d[i]=exactDigit(mask);
            if(d[i]<0 && Integer.bitCount(mask)>=2)return null;
        }
        if(d[4]<0)return null;
        int first=4;while(first>0&&d[first-1]>=0)first--;
        for(int i=first;i<5;i++)if(d[i]<0)return null;
        StringBuilder out=new StringBuilder();for(int i=first;i<5;i++)out.append((char)('0'+d[i]));
        return out.toString();
    }

    private int exactDigit(int mask){
        int[] m={0x3F,0x06,0x5B,0x4F,0x66,0x6D,0x7D,0x07,0x7F,0x6F};
        for(int d=0;d<10;d++)if(mask==m[d])return d;
        return -1;
    }

    private boolean activeEmission(Bitmap b,int x,int y){
        if(x<0||y<0||x>=b.getWidth()||y>=b.getHeight())return false;
        int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
        boolean warm=r>235&&g>135&&(g-bl)>20&&(r-bl)>50;
        boolean hotRed=r>240&&(r-g)>75&&(r-bl)>65;
        return warm||hotRed;
    }

    private double emissionRatio(Bitmap b,double ax,double ay,double bx,double by){
        int x0=Math.max(0,(int)ax),y0=Math.max(0,(int)ay),x1=Math.min(b.getWidth(),(int)bx),y1=Math.min(b.getHeight(),(int)by),n=0,hit=0;
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){n++;if(activeEmission(b,x,y))hit++;}
        return n==0?0:hit/(double)n;
    }

    private int[] findSimpleYellowFrame(Bitmap b){
        final int step=3,w=b.getWidth(),h=b.getHeight(),W=(w+step-1)/step,H=(h+step-1)/step;
        boolean[][] seen=new boolean[H][W];int[] best=null;int bestScore=-1;
        for(int gy=1;gy<H-1;gy++)for(int gx=1;gx<W-1;gx++){
            if(seen[gy][gx]||!yellowPixel(b,gx*step,gy*step))continue;
            java.util.ArrayDeque<Integer> q=new java.util.ArrayDeque<>();q.add(gy*W+gx);seen[gy][gx]=true;
            int minx=gx,maxx=gx,miny=gy,maxy=gy,n=0;
            while(!q.isEmpty()){
                int z=q.removeFirst(),x=z%W,y=z/W;n++;minx=Math.min(minx,x);maxx=Math.max(maxx,x);miny=Math.min(miny,y);maxy=Math.max(maxy,y);
                for(int yy=Math.max(0,y-2);yy<=Math.min(H-1,y+2);yy++)for(int xx=Math.max(0,x-2);xx<=Math.min(W-1,x+2);xx++){
                    if(seen[yy][xx])continue;
                    int px=Math.min(w-1,xx*step),py=Math.min(h-1,yy*step);
                    if(yellowPixel(b,px,py)){seen[yy][xx]=true;q.add(yy*W+xx);}
                }
            }
            int bw=(maxx-minx+1)*step,bh=(maxy-miny+1)*step;double ar=bw/(double)Math.max(1,bh);
            if(n>=10&&bw>Math.max(60,(int)(w*.12))&&bh>Math.max(18,(int)(h*.025))&&ar>2.0&&ar<5.5){
                int score=n*4+bw+bh;if(score>bestScore){bestScore=score;best=new int[]{minx*step,miny*step,Math.min(w,(maxx+1)*step),Math.min(h,(maxy+1)*step)};}
            }
        }
        return best;
    }

    private boolean yellowPixel(Bitmap b,int x,int y){
        int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
        return r>135&&g>80&&bl<195&&(r-bl)>20&&(g-bl)>-5;
    }
'''
s=s[:a]+v14+s[b:]
s=s.replace('50 Hz + FİZİKSEL ÇERÇEVE + GÖRELİ PARLAKLIK aktif.','50 Hz + BASİT FOTO OKUMA + GERÇEK YANAN SEGMENT aktif.')
p.write_text(s,encoding='utf-8')
print('V14_SIMPLE_EXACT_OK')

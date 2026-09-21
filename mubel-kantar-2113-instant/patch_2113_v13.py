from pathlib import Path
import sys

root=Path(sys.argv[1])
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')

a=s.index('    private String decodeSevenSegmentInstant(Bitmap src){')
b=s.index('    private int decodeDigitDual(',a)

v13=r'''    private String decodeSevenSegmentInstant(Bitmap src){
        String v=decodePanelConsensus(src); if(v!=null)return v;
        android.graphics.Matrix m=new android.graphics.Matrix();
        int[] turns={90,180,270};
        for(int deg:turns){
            Bitmap r=null;
            try{
                m.reset();m.postRotate(deg);
                r=Bitmap.createBitmap(src,0,0,src.getWidth(),src.getHeight(),m,true);
                v=decodePanelConsensus(r);
                if(v!=null)return v;
            }catch(Exception ignored){}finally{if(r!=null&&r!=src)r.recycle();}
        }
        return null;
    }

    private String decodePanelConsensus(Bitmap src){
        int[] f=findYellowDisplayFrame(src);
        if(f==null)return null;
        double[] vins={.10,.12,.14,.16};
        String[] c=new String[vins.length];
        for(int i=0;i<vins.length;i++)c[i]=decodePanelAtInset(src,f,vins[i]);
        String best=null;int hits=0;
        for(int i=0;i<c.length;i++){
            if(c[i]==null)continue;
            int n=0;for(int j=0;j<c.length;j++)if(c[i].equals(c[j]))n++;
            if(n>hits){hits=n;best=c[i];}
        }
        return hits>=2?best:null;
    }

    private int[] findYellowDisplayFrame(Bitmap b){
        final int step=4,w=b.getWidth(),h=b.getHeight();
        final int W=(w+step-1)/step,H=(h+step-1)/step;
        boolean[][] seen=new boolean[H][W];
        int[] best=null;int bestScore=-1;
        for(int gy=1;gy<H-1;gy++)for(int gx=1;gx<W-1;gx++){
            if(seen[gy][gx]||!isYellowFramePixel(b,Math.min(w-1,gx*step),Math.min(h-1,gy*step)))continue;
            java.util.ArrayDeque<Integer> q=new java.util.ArrayDeque<>();
            q.add(gy*W+gx);seen[gy][gx]=true;
            int minx=gx,maxx=gx,miny=gy,maxy=gy,n=0;
            while(!q.isEmpty()){
                int z=q.removeFirst(),x=z%W,y=z/W;n++;
                minx=Math.min(minx,x);maxx=Math.max(maxx,x);miny=Math.min(miny,y);maxy=Math.max(maxy,y);
                for(int yy=Math.max(0,y-1);yy<=Math.min(H-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(W-1,x+1);xx++){
                    if(seen[yy][xx])continue;
                    int px=Math.min(w-1,xx*step),py=Math.min(h-1,yy*step);
                    if(isYellowFramePixel(b,px,py)){seen[yy][xx]=true;q.add(yy*W+xx);}
                }
            }
            int bw=(maxx-minx+1)*step,bh=(maxy-miny+1)*step;
            double ar=bw/(double)Math.max(1,bh);
            if(n>=10 && bw>Math.max(70,(int)(w*.16)) && bh>Math.max(24,(int)(h*.045)) && ar>2.0 && ar<4.6){
                int score=n*5+bw+bh;
                if(score>bestScore){bestScore=score;best=new int[]{minx*step,miny*step,Math.min(w,(maxx+1)*step),Math.min(h,(maxy+1)*step)};}
            }
        }
        return best;
    }

    private boolean isYellowFramePixel(Bitmap b,int x,int y){
        int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
        return r>145 && g>90 && bl<185 && (r-bl)>28 && (g-bl)>4;
    }

    private String decodePanelAtInset(Bitmap b,int[] f,double vin){
        int fw=f[2]-f[0],fh=f[3]-f[1];
        int ix0=f[0]+(int)(fw*.03),ix1=f[2]-(int)(fw*.03);
        int iy0=f[1]+(int)(fh*vin),iy1=f[3]-(int)(fh*vin);
        int iw=ix1-ix0,ih=iy1-iy0;
        if(iw<80||ih<24)return null;
        double[][] z=new double[5][7];double global=0;
        for(int i=0;i<5;i++){
            int x0=ix0+(int)(iw*(i/5.0)),x1=ix0+(int)(iw*((i+1)/5.0));
            double w=x1-x0,h=iy1-iy0;
            z[i][0]=segmentTopEnergy(b,x0+.18*w,iy0+.00*h,x0+.82*w,iy0+.20*h);
            z[i][1]=segmentTopEnergy(b,x0+.62*w,iy0+.05*h,x0+.99*w,iy0+.50*h);
            z[i][2]=segmentTopEnergy(b,x0+.62*w,iy0+.50*h,x0+.99*w,iy0+.95*h);
            z[i][3]=segmentTopEnergy(b,x0+.18*w,iy0+.80*h,x0+.82*w,iy0+1.00*h);
            z[i][4]=segmentTopEnergy(b,x0+.01*w,iy0+.50*h,x0+.38*w,iy0+.95*h);
            z[i][5]=segmentTopEnergy(b,x0+.01*w,iy0+.05*h,x0+.38*w,iy0+.50*h);
            z[i][6]=segmentTopEnergy(b,x0+.14*w,iy0+.38*h,x0+.86*w,iy0+.64*h);
            for(double q:z[i])global=Math.max(global,q);
        }
        if(global<70)return null;
        int[] d=new int[5];for(int i=0;i<5;i++)d[i]=decodeRelativeCell(z[i],global);
        if(d[4]<0)return null;
        int first=4;
        for(int i=3;i>=0;i--){if(d[i]>=0)first=i;else break;}
        for(int i=first;i<5;i++)if(d[i]<0)return null;
        StringBuilder out=new StringBuilder();for(int i=first;i<5;i++)out.append((char)('0'+d[i]));
        return out.toString();
    }

    private int decodeRelativeCell(double[] z,double global){
        double mx=0,mn=1e9,sum=0;for(double q:z){mx=Math.max(mx,q);mn=Math.min(mn,q);sum+=q;}
        double spread=mx-mn,mean=sum/7.0;
        if(mx<Math.max(70,global*.45))return -1;
        // This display's 4 can lose its upper-left bar in glare. Require b,c,g plus dark a,d,e.
        if(z[1]>mn+.55*spread && z[2]>mn+.55*spread && z[6]>mn+Math.min(14,.25*spread)
                && z[0]<mn+.42*spread && z[3]<mn+.42*spread && z[4]<mn+.42*spread)return 4;
        if(spread<20)return mean>=Math.max(95,global*.58)?8:-1;
        double lo=mn,hi=mx;
        for(int k=0;k<5;k++){
            double sl=0,sh=0;int nl=0,nh=0;
            for(double q:z){if(Math.abs(q-lo)<=Math.abs(q-hi)){sl+=q;nl++;}else{sh+=q;nh++;}}
            if(nl>0)lo=sl/nl;if(nh>0)hi=sh/nh;
        }
        double th=(lo+hi)/2.0;int mask=0,on=0;
        for(int i=0;i<7;i++)if(z[i]>=th){mask|=1<<i;on++;}
        if(on<2)return -1;
        int[] m={0x3F,0x06,0x5B,0x4F,0x66,0x6D,0x7D,0x07,0x7F,0x6F};
        for(int d=0;d<10;d++)if(mask==m[d])return d;
        int best=-1,dist=8;for(int d=0;d<10;d++){int dd=Integer.bitCount(mask^m[d]);if(dd<dist){dist=dd;best=d;}}
        return dist<=1 && spread>=24?best:-1;
    }

    private double segmentTopEnergy(Bitmap b,double ax,double ay,double bx,double by){
        int x0=Math.max(0,(int)ax),y0=Math.max(0,(int)ay),x1=Math.min(b.getWidth(),(int)bx),y1=Math.min(b.getHeight(),(int)by);
        if(x1<=x0||y1<=y0)return 0;
        int[] hist=new int[512];int n=0;
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){
            int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);
            int e=Math.max(0,r-bl)+(Math.max(0,g-bl)*4)/5;if(e>511)e=511;hist[e]++;n++;
        }
        int need=Math.max(3,n/12),left=need;long sum=0;
        for(int e=511;e>=0&&left>0;e--){int take=Math.min(left,hist[e]);sum+=(long)e*take;left-=take;}
        return need==0?0:sum/(double)need;
    }
'''

s=s[:a]+v13+s[b:]
s=s.replace('50 Hz + TAM EKRAN BANDI + 1-5 HANE 7-SEGMENT aktif.','50 Hz + FİZİKSEL ÇERÇEVE + GÖRELİ PARLAKLIK aktif.')
s=s.replace('50 Hz + YÖN DÜZELTME + 1-5 HANE 7-SEGMENT aktif.','50 Hz + FİZİKSEL ÇERÇEVE + GÖRELİ PARLAKLIK aktif.')
p.write_text(s,encoding='utf-8')
print('V13_RELATIVE_BRIGHTNESS_OK')

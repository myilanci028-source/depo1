from pathlib import Path
import sys
root=Path(sys.argv[1])
p=root/'app/src/main/java/com/mubel/kantar/CameraLiveActivity.java'
s=p.read_text(encoding='utf-8')

s=s.replace('int[] f=findSimpleYellowFrame(b); if(f==null)return null;','int[] f=findSimpleYellowFrame(b); if(f==null)f=findFrameByYellowBands(b); if(f==null)return null;',1)
s=s.replace('int mask=0;for(int k=0;k<7;k++)if(z[k]>=.09)mask|=1<<k;','double[] th={.13,.05,.05,.05,.05,.05,.08}; int mask=0;for(int k=0;k<7;k++)if(z[k]>=th[k])mask|=1<<k;',1)

marker='    private int[] findSimpleYellowFrame(Bitmap b){'
method=r'''    private int[] findFrameByYellowBands(Bitmap b){
        int w=b.getWidth(),h=b.getHeight();
        int[] row=new int[h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(yellowPixel(b,x,y))row[y]++;
        double best=-1; int bx0=-1,by0=0,bx1=0,by1=0;
        int ys=(int)(h*.28), ye=(int)(h*.68), minSep=Math.max(8,(int)(h*.12));
        for(int y1=ys;y1<ye;y1++){
            if(row[y1]<w*.08)continue;
            int y2max=Math.min((int)(h*.86),y1+(int)(h*.48));
            for(int y2=y1+minSep;y2<y2max;y2++){
                if(row[y2]<w*.08)continue;
                int[] top=new int[w],bot=new int[w];
                for(int yy=Math.max(0,y1-8);yy<Math.min(h,y1+9);yy++)for(int x=0;x<w;x++)if(yellowPixel(b,x,yy))top[x]++;
                for(int yy=Math.max(0,y2-8);yy<Math.min(h,y2+9);yy++)for(int x=0;x<w;x++)if(yellowPixel(b,x,yy))bot[x]++;
                int rs=-1,bestRun=0,rx0=-1,rx1=-1;
                for(int x=0;x<w;x++){
                    boolean on=top[x]>=2&&bot[x]>=2;
                    if(on&&rs<0)rs=x;
                    if((!on||x==w-1)&&rs>=0){int e=on?x:x-1;int len=e-rs+1;if(len>bestRun){bestRun=len;rx0=rs;rx1=e+1;}rs=-1;}
                }
                if(bestRun<=0)continue;
                int sep=y2-y1; double ar=bestRun/(double)Math.max(1,sep);
                if(ar<=2.0||ar>=5.0)continue;
                double score=bestRun*2.0+Math.min(row[y1],row[y2])*.6-Math.abs(ar-3.0)*30.0;
                if(score>best){best=score;bx0=rx0;bx1=rx1;by0=y1;by1=y2+1;}
            }
        }
        if(bx0<0)return null;
        int sep=by1-by0, ya=Math.max(0,by0-(int)(sep*.25)), yb=Math.min(h,by1+(int)(sep*.25));
        int need=Math.max(5,(int)((bx1-bx0)*.08)), fy0=by0,fy1=by1;
        boolean got=false;
        for(int y=ya;y<yb;y++){
            int n=0;for(int x=bx0;x<bx1;x++)if(yellowPixel(b,x,y))n++;
            if(n>=need){if(!got){fy0=y;got=true;}fy1=y+1;}
        }
        return new int[]{bx0,fy0,bx1,fy1};
    }

'''
if marker not in s: raise SystemExit('frame marker missing')
s=s.replace(marker,method+marker,1)
s=s.replace('50 Hz + BASİT FOTO OKUMA + GERÇEK YANAN SEGMENT aktif.','50 Hz + 1-5 HANE + KIRMIZI/TURUNCU/SARI SEGMENT aktif.')
p.write_text(s,encoding='utf-8')
print('V15_REAL_PHOTO_CALIBRATED_OK')

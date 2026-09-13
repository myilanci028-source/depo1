package com.mubel.kantar;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MUBEL KANTAR 2.10.8 - industrial scale camera live reader. */
public class CameraActivity extends ComponentActivity {
    private static final int REQ_CAMERA = 8108;
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d{1,6}(?:[\\.,]\\d{1,2})?");
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    private PreviewView preview;
    private TextView state, live, detail;
    private Button torch, step;
    private ProcessCameraProvider provider;
    private Camera camera;
    private TextRecognizer recognizer;
    private ResultReceiver receiver;
    private boolean torchOn;
    private int stepKg = 2;
    private long lastAnalyze;
    private long lastPublish;
    private double lastPublished = Double.NaN;

    private static final class Sample {
        final double value; final long time;
        Sample(double value,long time){this.value=value;this.time=time;}
    }
    private static final class Candidate {
        final double value; final String raw; final int score;
        Candidate(double value,String raw,int score){this.value=value;this.raw=raw;this.score=score;}
    }

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try{receiver=getIntent().getParcelableExtra("receiver");}catch(Exception ignored){}
        recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        buildUi();
        sendState("KAMERA AÇILIYOR","Arka kamera hazırlanıyor.");
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);
    }

    private void buildUi(){
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        preview=new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        root.addView(new GuideView(this),new FrameLayout.LayoutParams(-1,-1));

        LinearLayout top=new LinearLayout(this); top.setOrientation(LinearLayout.VERTICAL); top.setPadding(dp(14),dp(18),dp(14),dp(8)); top.setBackgroundColor(0xB8121418);
        FrameLayout.LayoutParams tlp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP); root.addView(top,tlp);
        LinearLayout tr=new LinearLayout(this); tr.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=txt("MUBEL KANTAR · KAMERA CANLI VERİ",19,Color.WHITE,true); tr.addView(title,new LinearLayout.LayoutParams(0,-2,1f));
        Button close=btn("KAPAT"); close.setOnClickListener(v->finishCamera()); tr.addView(close); top.addView(tr);
        TextView hint=txt("50 Hz LED titreşim filtresi + çoklu kare oylaması · Rakamları turuncu çerçeveye doldur",12,0xFFD4D7DC,false); top.addView(hint);

        LinearLayout bottom=new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setPadding(dp(14),dp(10),dp(14),dp(18)); bottom.setBackgroundColor(0xD9121418);
        root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        state=txt("VERİ BEKLENİYOR",13,0xFFFF9800,true); bottom.addView(state);
        live=txt("— kg",42,Color.WHITE,true); live.setGravity(Gravity.CENTER); bottom.addView(live);
        detail=txt("Kantar ekranına dokunarak odaklayabilirsin. Yanıp sönen LED rakamlar birkaç kareden birleştirilir.",12,0xFFC4C8CE,false); detail.setGravity(Gravity.CENTER); bottom.addView(detail);

        LinearLayout r1=row();
        torch=btn("FLAŞ: KAPALI"); torch.setOnClickListener(v->toggleTorch()); r1.addView(torch,weighted());
        Button zm=btn("ZOOM −"); zm.setOnClickListener(v->zoom(false)); r1.addView(zm,weighted());
        Button zp=btn("ZOOM +"); zp.setOnClickListener(v->zoom(true)); r1.addView(zp,weighted()); bottom.addView(r1);

        LinearLayout r2=row();
        Button em=btn("PARLAKLIK −"); em.setOnClickListener(v->exposure(-1)); r2.addView(em,weighted());
        Button ep=btn("PARLAKLIK +"); ep.setOnClickListener(v->exposure(1)); r2.addView(ep,weighted());
        step=btn("ADIM: 2 kg"); step.setOnClickListener(v->cycleStep()); r2.addView(step,weighted()); bottom.addView(r2);

        preview.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_UP) focus(e.getX(),e.getY()); return true;});
        setContentView(root);
    }

    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER);r.setPadding(0,dp(6),0,0);return r;}
    private LinearLayout.LayoutParams weighted(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1f);p.setMargins(dp(3),0,dp(3),0);return p;}
    private TextView txt(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);return t;}
    private Button btn(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setAllCaps(false);b.setBackgroundColor(0xFF2B2F35);return b;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}

    private void startCamera(){
        ListenableFuture<ProcessCameraProvider> f=ProcessCameraProvider.getInstance(this);
        f.addListener(()->{
            try{
                provider=f.get();provider.unbindAll();
                Preview.Builder pb=new Preview.Builder();
                try{
                    Camera2Interop.Extender<Preview> x=new Camera2Interop.Extender<>(pb);
                    x.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ);
                    x.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                }catch(Exception ignored){}
                Preview pv=pb.build();pv.setSurfaceProvider(preview.getSurfaceProvider());

                ImageAnalysis.Builder ab=new ImageAnalysis.Builder().setTargetResolution(new Size(1280,720)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
                try{
                    Camera2Interop.Extender<ImageAnalysis> x=new Camera2Interop.Extender<>(ab);
                    x.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ);
                    x.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                }catch(Exception ignored){}
                ImageAnalysis ia=ab.build();ia.setAnalyzer(executor,this::analyze);
                camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,pv,ia);
                boolean flash=camera.getCameraInfo().hasFlashUnit();torch.setEnabled(flash);torch.setText(flash?"FLAŞ: KAPALI":"FLAŞ YOK");
                sendState("KAMERA HAZIR","50 Hz anti-flicker açık · OCS-A için d=2 kg · Ekranı çerçeveye yaklaştır.");
            }catch(Exception e){sendState("KAMERA HATASI",safe(e));}
        },ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy proxy){
        long now=SystemClock.elapsedRealtime();
        if(now-lastAnalyze<120||!busy.compareAndSet(false,true)){proxy.close();return;} lastAnalyze=now;
        Image img=proxy.getImage(); if(img==null){busy.set(false);proxy.close();return;}
        InputImage in=InputImage.fromMediaImage(img,proxy.getImageInfo().getRotationDegrees());
        recognizer.process(in).addOnSuccessListener(t->{
            Candidate c=best(t); if(c!=null)accept(c.value,"OCR · "+c.raw); else runOnUiThread(()->{state.setText("RAKAM ARANIYOR");detail.setText("Ekranı çerçeveye daha çok doldur. Gerekirse ZOOM + veya PARLAKLIK − kullan.");});
        }).addOnFailureListener(e->sendState("OCR HATASI",safe(e))).addOnCompleteListener(t->{busy.set(false);proxy.close();});
    }

    private Candidate best(Text t){
        Candidate best=null;
        for(Text.TextBlock b:t.getTextBlocks()){
            best=better(best,candidate(b.getText(),b.getBoundingBox()));
            for(Text.Line l:b.getLines()){
                best=better(best,candidate(l.getText(),l.getBoundingBox()));
                for(Text.Element e:l.getElements())best=better(best,candidate(e.getText(),e.getBoundingBox()));
            }
        }
        return best;
    }
    private Candidate better(Candidate a,Candidate b){return b!=null&&(a==null||b.score>a.score)?b:a;}
    private Candidate candidate(String raw,Rect box){
        if(raw==null)return null;
        String s=raw.toUpperCase(Locale.ROOT).replace(" ","").replace('O','0');
        if(s.matches(".*\\d.*"))s=s.replace('I','1').replace('L','1').replace('|','1');
        Matcher m=NUMBER.matcher(s);Candidate best=null;
        while(m.find())try{
            String token=m.group().replace(',','.');double v=Double.parseDouble(token);if(!Double.isFinite(v)||Math.abs(v)>100000)continue;
            int area=box==null?1:Math.max(1,box.width()*box.height());int score=area+(s.contains("KG")?2_000_000:0)+Math.min(6,token.length())*1000;
            best=better(best,new Candidate(v,raw,score));
        }catch(Exception ignored){}
        return best;
    }

    private void accept(double raw,String source){
        if(!Double.isFinite(raw))return;double value=snap(raw);long now=SystemClock.elapsedRealtime();
        synchronized(samples){
            samples.addLast(new Sample(value,now));while(!samples.isEmpty()&&(now-samples.getFirst().time>1800||samples.size()>14))samples.removeFirst();
            List<Double> vals=new ArrayList<>();for(Sample s:samples)vals.add(s.value);Collections.sort(vals);double median=vals.get(vals.size()/2);
            double tolerance=Math.max(.55,stepKg*.55);int votes=0;for(Sample s:samples)if(Math.abs(s.value-median)<=tolerance)votes++;
            double stable=snap(median); final int voteCount=votes; final int stepNow=stepKg;
            runOnUiThread(()->{live.setText(fmt(value)+" kg");state.setText(voteCount>=3?"OKUNDU · STABİL":"OKUNUYOR · "+voteCount+"/3");detail.setText(source+" · "+voteCount+" kare onayı · d="+stepNow+" kg");});
            if(votes>=3&&(!Double.isFinite(lastPublished)||Math.abs(stable-lastPublished)>.0001||now-lastPublish>450)){lastPublished=stable;lastPublish=now;sendWeight(stable,source,votes);}
        }
    }
    private double snap(double v){return stepKg>0?Math.rint(v/stepKg)*stepKg:v;}
    private String fmt(double v){return Math.abs(v-Math.rint(v))<.001?String.format(Locale.US,"%.0f",v):String.format(Locale.US,"%.1f",v);}

    private void sendWeight(double kg,String source,int votes){if(receiver==null)return;Bundle b=new Bundle();b.putDouble("kg",kg);b.putString("source",source);b.putInt("votes",votes);b.putString("state","KAMERA CANLI");try{receiver.send(1,b);}catch(Exception ignored){}}
    private void sendState(String s,String d){if(receiver!=null){Bundle b=new Bundle();b.putString("state",s);b.putString("detail",d);try{receiver.send(2,b);}catch(Exception ignored){}}runOnUiThread(()->{if(state!=null)state.setText(s);if(detail!=null&&d!=null&&!d.isEmpty())detail.setText(d);});}

    private void toggleTorch(){if(camera==null||!camera.getCameraInfo().hasFlashUnit())return;torchOn=!torchOn;camera.getCameraControl().enableTorch(torchOn);torch.setText(torchOn?"FLAŞ: AÇIK":"FLAŞ: KAPALI");}
    private void zoom(boolean plus){if(camera==null)return;ZoomState z=camera.getCameraInfo().getZoomState().getValue();if(z==null)return;float n=z.getZoomRatio()*(plus?1.25f:.8f);n=Math.max(z.getMinZoomRatio(),Math.min(z.getMaxZoomRatio(),n));camera.getCameraControl().setZoomRatio(n);detail.setText(String.format(Locale.US,"Zoom %.1fx · Ekranı çerçeveye doldur",n));}
    private void exposure(int delta){if(camera==null)return;ExposureState e=camera.getCameraInfo().getExposureState();int n=e.getExposureCompensationIndex()+delta;n=Math.max(e.getExposureCompensationRange().getLower(),Math.min(e.getExposureCompensationRange().getUpper(),n));camera.getCameraControl().setExposureCompensationIndex(n);detail.setText("Kamera parlaklığı / EV: "+n+" · LED rakamlar patlıyorsa PARLAKLIK − kullan");}
    private void cycleStep(){int[] a={1,2,5,10};int ix=0;for(int i=0;i<a.length;i++)if(a[i]==stepKg)ix=i;stepKg=a[(ix+1)%a.length];samples.clear();step.setText("ADIM: "+stepKg+" kg");detail.setText("Çözünürlük d="+stepKg+" kg. NECKLIFE OCS-A 5T için 2 kg kullan.");}
    private void focus(float x,float y){if(camera==null)return;try{MeteringPoint p=preview.getMeteringPointFactory().createPoint(x,y);FocusMeteringAction a=new FocusMeteringAction.Builder(p,FocusMeteringAction.FLAG_AF|FocusMeteringAction.FLAG_AE).setAutoCancelDuration(3,TimeUnit.SECONDS).build();camera.getCameraControl().startFocusAndMetering(a);detail.setText("Odaklanıyor… kantar ekranını sabit tut.");}catch(Exception ignored){}}

    private void finishCamera(){sendState("KAMERA KAPALI",Double.isFinite(lastPublished)?"Son aktarılan: "+fmt(lastPublished)+" kg":"Henüz stabil kilo aktarılmadı");finish();}
    @Override public void onBackPressed(){finishCamera();}
    @Override public void onRequestPermissionsResult(int req,String[] p,int[] g){super.onRequestPermissionsResult(req,p,g);if(req==REQ_CAMERA){if(g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)startCamera();else sendState("KAMERA İZNİ GEREKİYOR","Kamera canlı kilo okumak için kamera izni verilmeli.");}}
    @Override protected void onDestroy(){try{if(provider!=null)provider.unbindAll();}catch(Exception ignored){}try{if(recognizer!=null)recognizer.close();}catch(Exception ignored){}executor.shutdownNow();super.onDestroy();}
    private static String safe(Throwable e){if(e==null)return"Bilinmeyen hata";String m=e.getMessage();return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m;}

    private static class GuideView extends View{
        private final Paint border=new Paint(Paint.ANTI_ALIAS_FLAG),shade=new Paint(),label=new Paint(Paint.ANTI_ALIAS_FLAG);
        GuideView(android.content.Context c){super(c);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(6);border.setColor(0xFFFF9800);shade.setColor(0x55101419);label.setColor(Color.WHITE);label.setTextSize(34);label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();RectF r=new RectF(w*.07f,h*.29f,w*.93f,h*.64f);c.drawRect(0,0,w,r.top,shade);c.drawRect(0,r.bottom,w,h,shade);c.drawRect(0,r.top,r.left,r.bottom,shade);c.drawRect(r.right,r.top,w,r.bottom,shade);c.drawRoundRect(r,20,20,border);String s="KANTAR RAKAMLARI";c.drawText(s,(w-label.measureText(s))/2,r.top-18,label);}
    }
}

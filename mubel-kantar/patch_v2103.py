from pathlib import Path

JAVA = Path('app/src/main/java/com/mubel/kantar/MainActivity.java')
GRADLE = Path('app/build.gradle')
s = JAVA.read_text(encoding='utf-8')

# Version + injected asset
s = s.replace("v2.10.2", "v2.10.3")
s = s.replace("v2102script", "v2103script").replace("v2102.js", "v2103.js")

# Graphics imports for direct PDF renderer
if 'import android.graphics.Bitmap;' not in s:
    s = s.replace('import android.graphics.Color;\n',
                  'import android.graphics.Bitmap;\nimport android.graphics.BitmapFactory;\nimport android.graphics.Canvas;\nimport android.graphics.Color;\nimport android.graphics.Paint;\nimport android.graphics.RectF;\nimport android.graphics.Typeface;\n')

# New bridge method: structured data -> direct PdfDocument, no hidden WebView
bridge_marker = '''        @JavascriptInterface public void sendMailPdf(String html, String recipient, String subject, String body, String fisNo) {\n            runOnUiThread(() -> createPdfAndSend(prepareHtmlForV210(html, "A4"), recipient, subject, body, fisNo));\n        }\n'''
bridge_insert = bridge_marker + '''\n        @JavascriptInterface public void sendMailPdfData(String json, String recipient, String subject, String body) {\n            new Thread(() -> createDirectPdfAndSend(json, recipient, subject, body)).start();\n        }\n'''
if 'sendMailPdfData(' not in s:
    if bridge_marker not in s:
        raise SystemExit('sendMailPdf bridge marker not found')
    s = s.replace(bridge_marker, bridge_insert, 1)

DIRECT = r'''
    private void mailProgress(String message) {
        js("window.MUBEL_MAIL_STATUS&&window.MUBEL_MAIL_STATUS('PROGRESS'," + q(message) + ");");
    }

    private void createDirectPdfAndSend(String json, String recipient, String subject, String body) {
        if (recipient == null || !recipient.contains("@")) {
            mailError("Geçerli alıcı e-posta adresi giriniz.");
            return;
        }
        PdfDocument doc = null;
        File pdf = null;
        try {
            mailProgress("1/3 · PDF hazırlanıyor…");
            JSONObject r = new JSONObject(json == null ? "{}" : json);
            JSONObject corp = r.optJSONObject("corp");
            if (corp == null) corp = new JSONObject();
            String safeFis = sanitizeFileName(r.optString("fis", "kantar"));
            File dir = new File(getCacheDir(), "mailpdf");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("PDF klasörü oluşturulamadı");
            pdf = new File(dir, "MUBEL_KANTAR_" + safeFis + "_" + System.currentTimeMillis() + ".pdf");

            doc = new PdfDocument();
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(PDF_W, PDF_H, 1).create();
            PdfDocument.Page page = doc.startPage(info);
            Canvas c = page.getCanvas();
            c.drawColor(Color.WHITE);
            drawDirectReport(c, r, corp);
            doc.finishPage(page);
            try (FileOutputStream out = new FileOutputStream(pdf)) {
                doc.writeTo(out);
                out.flush();
            }
            doc.close();
            doc = null;
            if (!pdf.exists() || pdf.length() < 700) throw new Exception("PDF boş veya eksik oluşturuldu");
            mailProgress("2/3 · PDF hazır (" + Math.max(1, pdf.length()/1024) + " KB) · SMTP bağlanıyor…");
            sendSmtp(pdf, recipient, subject, body, safeFis);
        } catch (Exception e) {
            try { if (doc != null) doc.close(); } catch (Exception ignored) {}
            try { if (pdf != null && pdf.exists()) pdf.delete(); } catch (Exception ignored) {}
            mailError("PDF hazırlanamadı: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private void drawDirectReport(Canvas c, JSONObject r, JSONObject corp) {
        final int green = Color.rgb(29,91,39), dark = Color.rgb(23,32,24);
        final float m=58f, right=PDF_W-m, usable=PDF_W-2*m;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(dark); p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        Bitmap logo = decodeLogo(corp.optString("logo", ""));
        float tx=m;
        if (logo != null) {
            float sc=Math.min(90f/logo.getWidth(),86f/logo.getHeight());
            float w=logo.getWidth()*sc,h=logo.getHeight()*sc;
            c.drawBitmap(logo,null,new RectF(m,42f,m+w,42f+h),p);
            tx=m+w+18f;
            try { logo.recycle(); } catch(Exception ignored) {}
        }
        p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD)); p.setTextSize(36f);
        c.drawText(corp.optString("firma","YILANCIOĞLU"),tx,78f,p);
        p.setTypeface(Typeface.DEFAULT); p.setTextSize(17f);
        drawWrapped(c,p,corp.optString("adres",""),tx,108f,690f,2,22f);

        p.setTextAlign(Paint.Align.RIGHT); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(34f);
        c.drawText("TARTIM RAPORU",right,70f,p);
        p.setTypeface(Typeface.DEFAULT); p.setTextSize(17f);
        c.drawText("Fiş: "+r.optString("fis",""),right,101f,p);
        c.drawText(r.optString("tarih",""),right,126f,p);
        p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(16f);
        c.drawText("0530 962 67 93",right,151f,p);
        c.drawText("https://www.yilancioglu.com.tr/",right,175f,p);
        p.setTextAlign(Paint.Align.LEFT); p.setColor(green); p.setStrokeWidth(4f);
        c.drawLine(m,195f,right,195f,p); p.setColor(dark);

        float gap=48f,colW=(usable-gap)/2f,x1=m,x2=m+colW+gap,y=240f;
        drawInfo(c,p,"Cari / Firma",r.optString("cari",""),x1,y,colW);
        drawInfo(c,p,"Malzeme / Ürün",r.optString("urun",""),x2,y,colW); y+=56f;
        drawInfo(c,p,"Araç Plaka",r.optString("plaka",""),x1,y,colW);
        drawInfo(c,p,"İrsaliye / Belge No",r.optString("irsaliye",""),x2,y,colW); y+=56f;
        drawInfo(c,p,"Operatör",r.optString("operator",""),x1,y,colW);
        drawInfo(c,p,"Tartım Tarihi",r.optString("tarih",""),x2,y,colW);

        float rt=405f,rh=110f;
        p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(245,247,245)); c.drawRect(m,rt,right,rt+rh,p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(Color.rgb(210,218,212)); c.drawRect(m,rt,right,rt+rh,p);
        p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(95,105,97)); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(14f);
        c.drawText("GELDİĞİ YER",m+20,rt+30,p); c.drawText("GİDECEĞİ YER",x2+20,rt+30,p);
        p.setColor(dark); p.setTypeface(Typeface.DEFAULT); p.setTextSize(22f);
        drawWrapped(c,p,r.optString("geldigi","-"),m+20,rt+63,colW-30,2,25f);
        drawWrapped(c,p,r.optString("gidecegi","-"),x2+20,rt+63,colW-30,2,25f);

        float bt=545f,bh=150f,bg=22f,bw=(usable-2*bg)/3f;
        drawWeight(c,p,"BRÜT",Math.round(r.optDouble("brut",0)),m,bt,bw,bh,false);
        drawWeight(c,p,"DARA",Math.round(r.optDouble("dara",0)),m+bw+bg,bt,bw,bh,false);
        drawWeight(c,p,"NET",Math.round(r.optDouble("net",0)),m+2*(bw+bg),bt,bw,bh,true);

        float nt=725f,nh=250f;
        p.setColor(Color.rgb(245,247,245)); c.drawRect(m,nt,right,nt+nh,p);
        p.setColor(green); c.drawRect(m,nt,m+7,nt+nh,p);
        p.setColor(dark); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(20f); c.drawText("Açıklama:",m+24,nt+34,p);
        drawAutoNote(c,r.optString("aciklama",""),m+125,nt+34,usable-155,nh-42);

        float sy=1035f,sw=560f; p.setStrokeWidth(1.5f); p.setColor(Color.rgb(70,70,70));
        c.drawLine(m+30,sy,m+30+sw,sy,p); c.drawLine(right-30-sw,sy,right-30,sy,p);
        p.setTextSize(14f); p.setTextAlign(Paint.Align.CENTER);
        c.drawText("Tartımı Yapan / İmza",m+30+sw/2,sy+27,p);
        c.drawText("Teslim Alan / İmza / Kaşe",right-30-sw/2,sy+27,p); p.setTextAlign(Paint.Align.LEFT);

        p.setColor(Color.rgb(190,195,190)); c.drawLine(m,1100,right,1100,p);
        p.setColor(Color.rgb(95,95,95)); p.setTextSize(12f);
        c.drawText(corp.optString("footer","MUBEL KANTAR · Mobil Wi-Fi Tartım Sistemi"),m,1127,p);
        p.setTextSize(10f); c.drawText("Fiş No: "+r.optString("fis","")+" · MUBEL KANTAR v2.10.3",m,1148,p);
    }

    private void drawInfo(Canvas c, Paint p, String label, String value, float x, float y, float w) {
        p.setColor(Color.rgb(23,32,24)); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(16f);
        c.drawText(label+":",x,y,p); float lw=p.measureText(label+": ");
        p.setTypeface(Typeface.DEFAULT); p.setTextSize(fitText(p,value,w-lw,18f,12f)); c.drawText(value==null?"":value,x+lw,y,p);
        p.setColor(Color.rgb(205,210,205)); p.setStrokeWidth(1.2f); c.drawLine(x,y+14,x+w,y+14,p);
    }

    private void drawWeight(Canvas c, Paint p, String label, long kg, float x, float y, float w, float h, boolean net) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(net?3.5f:1.5f); p.setColor(net?Color.rgb(29,91,39):Color.rgb(180,185,180)); c.drawRect(x,y,x+w,y+h,p);
        p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(23,32,24)); p.setTextAlign(Paint.Align.CENTER); p.setTypeface(Typeface.DEFAULT); p.setTextSize(18f); c.drawText(label,x+w/2,y+42,p);
        p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(40f); c.drawText(kg+" kg",x+w/2,y+98,p); p.setTextAlign(Paint.Align.LEFT);
    }

    private float fitText(Paint p, String text, float width, float max, float min) {
        String s=text==null?"":text; for(float z=max;z>=min;z-=1){p.setTextSize(z); if(p.measureText(s)<=width)return z;} return min;
    }

    private List<String> wrapText(Paint p, String text, float width) {
        List<String> out=new ArrayList<>(); String src=text==null?"":text.replace('\r',' ').replace('\n',' ');
        if(src.isEmpty()){out.add("");return out;} String[] words=src.split("\\s+"); StringBuilder line=new StringBuilder();
        for(String word:words){ if(word.isEmpty())continue; String cand=line.length()==0?word:line+" "+word;
            if(p.measureText(cand)<=width){line.setLength(0);line.append(cand);} else {if(line.length()>0){out.add(line.toString());line.setLength(0);} if(p.measureText(word)<=width)line.append(word); else {StringBuilder ch=new StringBuilder(); for(int i=0;i<word.length();i++){String n=ch.toString()+word.charAt(i); if(p.measureText(n)>width&&ch.length()>0){out.add(ch.toString());ch.setLength(0);} ch.append(word.charAt(i));} line.append(ch);}}}
        if(line.length()>0)out.add(line.toString()); return out;
    }

    private void drawWrapped(Canvas c, Paint p, String text, float x, float y, float width, int maxLines, float lh) {
        List<String> lines=wrapText(p,text,width); for(int i=0;i<Math.min(maxLines,lines.size());i++)c.drawText(lines.get(i),x,y+i*lh,p);
    }

    private void drawAutoNote(Canvas c, String text, float x, float y, float width, float height) {
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.rgb(23,32,24)); p.setTypeface(Typeface.DEFAULT);
        List<String> lines=null; float size=21f,lh=25f;
        for(float z=21f;z>=10f;z-=1f){p.setTextSize(z);List<String> test=wrapText(p,text,width);float l=z*1.32f;if(test.size()*l<=height){size=z;lh=l;lines=test;break;}}
        if(lines==null){size=10f;lh=12.5f;p.setTextSize(size);lines=wrapText(p,text,width);} p.setTextSize(size); int max=Math.max(1,(int)(height/lh));
        for(int i=0;i<Math.min(max,lines.size());i++)c.drawText(lines.get(i),x,y+i*lh,p);
    }

    private Bitmap decodeLogo(String dataUrl) {
        try { if(dataUrl==null||dataUrl.isEmpty())return null; int comma=dataUrl.indexOf(','); String b64=comma>=0?dataUrl.substring(comma+1):dataUrl; byte[] bytes=Base64.decode(b64,Base64.DEFAULT); Bitmap b=BitmapFactory.decodeByteArray(bytes,0,bytes.length); if(b==null)return null; int max=Math.max(b.getWidth(),b.getHeight()); if(max>420){float sc=420f/max;Bitmap x=Bitmap.createScaledBitmap(b,Math.max(1,Math.round(b.getWidth()*sc)),Math.max(1,Math.round(b.getHeight()*sc)),true);if(x!=b)b.recycle();b=x;} return b; } catch(Exception e){return null;}
    }
'''

if 'private void createDirectPdfAndSend(' not in s:
    marker = '    private Properties smtpProperties(String host, int port, String security) {'
    if marker not in s:
        raise SystemExit('smtpProperties marker not found')
    s = s.replace(marker, DIRECT + '\n' + marker, 1)

# Add progress update immediately before SMTP transport send
if '3/3 · PDF SMTP sunucusuna gönderiliyor' not in s:
    s = s.replace('        try {\n            Session session = Session.getInstance(smtpProperties(host, port, security), new Authenticator() {',
                  '        try {\n            mailProgress("3/3 · PDF SMTP sunucusuna gönderiliyor…");\n            Session session = Session.getInstance(smtpProperties(host, port, security), new Authenticator() {', 1)

# Faster bounded SMTP timeouts for attachment send
s = s.replace('props.put("mail.smtp.connectiontimeout", "10000");', 'props.put("mail.smtp.connectiontimeout", "8000");')
s = s.replace('props.put("mail.smtp.timeout", "18000");', 'props.put("mail.smtp.timeout", "12000");')
s = s.replace('props.put("mail.smtp.writetimeout", "18000");', 'props.put("mail.smtp.writetimeout", "12000");')

JAVA.write_text(s, encoding='utf-8')

g = GRADLE.read_text(encoding='utf-8')
g = g.replace("versionCode 2102", "versionCode 2103").replace("versionName '2.10.2'", "versionName '2.10.3'")
GRADLE.write_text(g, encoding='utf-8')
print('v2.10.3 patch applied')

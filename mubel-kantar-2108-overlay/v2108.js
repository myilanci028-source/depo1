(()=>{'use strict';
const $=id=>document.getElementById(id);
function toast(t){try{window.MUBEL&&MUBEL.toast(t)}catch(e){}}
function cameraCss(){if($('v2108css'))return;const s=document.createElement('style');s.id='v2108css';s.textContent=`
.cameraLiveCard{border:1px solid rgba(255,152,0,.55)!important;background:linear-gradient(180deg,rgba(17,43,74,.22),rgba(18,20,24,.96))!important}.cameraHead{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:10px}.cameraBadge{font-size:11px;padding:5px 9px;border-radius:999px;background:#112B4A;color:#F6F2E9;border:1px solid #B08D57}.cameraBtn{width:100%;min-height:58px;font-size:17px!important}.cameraState{margin-top:10px;padding:10px 12px;border-radius:10px;background:rgba(17,43,74,.58);font-size:12px;line-height:1.5}.cameraKg{font-weight:800;color:#ff9800}.cameraNote{font-size:11px;color:#aeb3bb;line-height:1.45;margin-top:8px}
`;document.head.appendChild(s)}
function setVersion(){document.querySelectorAll('.ver').forEach(e=>{e.textContent=e.textContent.replace('v2.10.6','v2.10.8').replace('v2.10.7','v2.10.8')});const b=document.querySelector('.tripleBadge');if(b)b.textContent='2.10.7 STABİL TABAN + KAMERA'}
function inject(){const home=$('tab-home');if(!home||$('mubelCameraCard'))return;const card=document.createElement('div');card.id='mubelCameraCard';card.className='card cameraLiveCard';card.innerHTML=`
<div class="cameraHead"><h3 style="margin:0">📷 Kamera Canlı Veri</h3><span class="cameraBadge">OCS‑A · d=2 kg</span></div>
<button id="cameraLiveBtn" class="btn orange cameraBtn">KAMERA CANLI VERİ</button>
<div id="cameraLiveState" class="cameraState"><b>HAZIR</b><br>Kantarın rakamlarını kameradaki turuncu çerçeveye getir. LED rakam yanıp sönse bile 50 Hz anti-flicker + çoklu kare oylaması ile stabil kilo ana ekrana aktarılır.</div>
<div class="cameraNote">Kamera ekranında <b>FLAŞ AÇ/KAPA</b>, zoom, parlaklık/EV ve dokunarak odaklama vardır. Flaş varsayılan kapalıdır; ekran çok karanlıksa aç.</div>`;
const weight=home.querySelector('.weight');if(weight)weight.insertAdjacentElement('afterend',card);else home.insertBefore(card,home.firstChild);
$('cameraLiveBtn').onclick=()=>{try{if(!window.Android||typeof Android.openCameraLive!=='function')return toast('Kamera modülü bulunamadı');$('cameraLiveState').innerHTML='<b>KAMERA AÇILIYOR</b><br>Kamera izni istenirse izin ver.';Android.openCameraLive()}catch(e){toast('Kamera açılamadı: '+e)}};
}
function cfgFactor(){let f=.1;try{const c=JSON.parse(localStorage.getItem('mubel23cfg')||'{}');const n=Number(c.factor);if(n>0)f=n}catch(e){}return f}
function trimNum(n){let s=Number(n).toFixed(6);s=s.replace(/0+$/,'').replace(/\.$/,'');return s||'0'}
window.MUBEL_CAMERA_STATUS=(state,detail)=>{const e=$('cameraLiveState');if(e)e.innerHTML=`<b>${String(state||'KAMERA')}</b><br>${String(detail||'')}`};
window.MUBEL_CAMERA_WEIGHT=(kg,source,votes)=>{const n=Number(kg);if(!Number.isFinite(n))return;const factor=cfgFactor();const raw=n/factor;try{const text=trimNum(raw)+' kg\r\n';MUBEL.onRawB64(btoa(text))}catch(e){return}const el=$('cameraLiveState');if(el)el.innerHTML=`<b>KAMERA CANLI · <span class="cameraKg">${trimNum(n)} kg</span></b><br>${String(source||'OCR')} · ${Number(votes)||0} kare onayı · Stabil çözümleyiciye aktarıldı.`};
function boot(){cameraCss();setVersion();inject();setTimeout(()=>{cameraCss();setVersion();inject()},500);setTimeout(()=>{cameraCss();setVersion();inject()},1600)}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',boot);else boot();
})();

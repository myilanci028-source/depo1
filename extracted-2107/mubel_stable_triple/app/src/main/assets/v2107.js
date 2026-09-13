(()=>{'use strict';
const $=id=>document.getElementById(id);
function toast(t){try{window.MUBEL&&MUBEL.toast(t)}catch(e){}}
function css(){if($('v2107css'))return;const s=document.createElement('style');s.id='v2107css';s.textContent=`
.tripleCard{border:1px solid rgba(176,141,87,.45)!important}.tripleTitle{display:flex;align-items:center;justify-content:space-between;gap:10px}.tripleBadge{font-size:11px;padding:5px 9px;border-radius:999px;background:#112B4A;color:#F6F2E9;border:1px solid #B08D57}.tripleGrid{display:grid;grid-template-columns:repeat(3,1fr);gap:9px;margin-top:10px}.tripleBtn{min-height:48px}.tripleStatus{margin-top:10px;padding:10px 12px;border-radius:10px;background:rgba(17,43,74,.55);font-size:12px;line-height:1.45}.tripleDevices{display:grid;grid-template-columns:1fr auto;gap:8px;margin-top:10px}.tripleNote{font-size:11px;line-height:1.45;color:#aeb3bb;margin-top:10px}.irAdvanced{margin-top:9px;padding-top:9px;border-top:1px dashed rgba(176,141,87,.35)}.irRow{display:grid;grid-template-columns:130px 1fr auto;gap:8px;align-items:end}
@media(max-width:680px){.tripleGrid{grid-template-columns:1fr}.tripleDevices{grid-template-columns:1fr}.irRow{grid-template-columns:1fr}.tripleBtn{width:100%}}
`;document.head.appendChild(s)}
function inject(){const tab=$('tab-settings');if(!tab||$('mubelTripleCard'))return;const cards=tab.querySelectorAll('.card');const card=document.createElement('div');card.id='mubelTripleCard';card.className='card tripleCard';card.innerHTML=`
<div class="tripleTitle"><h3 style="margin:0">Kızılötesi + Bluetooth + Wi‑Fi</h3><span class="tripleBadge">2.10.6 STABİL TABAN</span></div>
<div class="tripleGrid">
 <button id="tripleWifi" class="btn orange tripleBtn">📶 WI‑FI / TCP<br><small>Mevcut profil ile bağlan</small></button>
 <button id="tripleBtScan" class="btn tripleBtn">🔵 BLUETOOTH / BLE<br><small>Cihazları tara</small></button>
 <button id="tripleIr" class="btn tripleBtn">📡 KIZILÖTESİ (IR)<br><small>Telefon donanımını kontrol et</small></button>
</div>
<div class="tripleDevices"><select id="tripleBtDevices"><option value="">Bluetooth cihazı seçin</option></select><button id="tripleBtConnect" class="btn orange">SEÇİLENE BAĞLAN</button></div>
<div class="row" style="margin-top:8px"><button id="tripleBtCut" class="btn">BLUETOOTH KES</button></div>
<div id="tripleStatus" class="tripleStatus">Hazır. Wi‑Fi mevcut stabil TCP motorunu kullanır; Bluetooth Classic SPP ve BLE cihazları bu ekrandan taranır.</div>
<div class="tripleNote"><b>Önemli:</b> Telefonlardaki IR donanımı normalde <b>vericidir</b>; kumanda kodu gönderir. Kilo verisinin IR ile telefona alınabilmesi için kantarın çift yönlü IR veri protokolü ve telefonda erişilebilir IR alıcısı gerekir. Wi‑Fi ve Bluetooth kilo verisi doğrudan stabil çözümleyiciye aktarılır.</div>
<div class="irAdvanced"><div class="irRow"><div class="field"><label>IR frekans (Hz)</label><input id="irFreq" type="number" value="38000"></div><div class="field"><label>Ham IR darbe dizisi (µs, virgülle)</label><input id="irPattern" placeholder="9000,4500,560,560,..."></div><button id="irSend" class="btn">HAM IR GÖNDER</button></div></div>`;
if(cards.length>=2)cards[1].insertAdjacentElement('afterend',card);else tab.appendChild(card);
$('tripleWifi').onclick=()=>{const b=$('connect');if(b){b.click();setStatus('WI-FI','BAĞLANIYOR','Mevcut IP/port profili ile stabil TCP bağlantısı başlatıldı.')}else toast('Wi‑Fi bağlantı düğmesi bulunamadı')};
$('tripleBtScan').onclick=()=>{try{if(!window.MubelTriple)return toast('Bluetooth servisi bulunamadı');$('tripleBtDevices').innerHTML='<option value="">Taranıyor…</option>';MubelTriple.scanBluetooth()}catch(e){toast('Bluetooth taraması başlatılamadı: '+e)}};
$('tripleBtConnect').onclick=()=>{const s=$('tripleBtDevices'),o=s&&s.options[s.selectedIndex];if(!o||!o.value)return toast('Önce bir Bluetooth cihazı seçin');try{MubelTriple.connectBluetooth(o.value,o.dataset.type||'CLASSIC')}catch(e){toast('Bluetooth bağlantısı başlatılamadı: '+e)}};
$('tripleBtCut').onclick=()=>{try{MubelTriple.btDisconnect()}catch(e){}};
$('tripleIr').onclick=()=>{try{const r=JSON.parse(MubelTriple.irInfo()||'{}');if(r.hasEmitter)toast('IR verici hazır');else toast('Telefonda kullanılabilir IR vericisi yok')}catch(e){toast('IR kontrolü yapılamadı')}};
$('irSend').onclick=()=>{const f=parseInt($('irFreq').value)||38000,p=$('irPattern').value.trim();if(!p)return toast('Ham IR darbe dizisini yazın');if(!confirm('Bu ham IR kodunu göndermek istiyor musunuz?'))return;try{MubelTriple.irTransmitRaw(f,p)}catch(e){toast('IR gönderilemedi: '+e)}};
}
function setStatus(type,state,detail){const e=$('tripleStatus');if(e)e.innerHTML=`<b>${String(type||'')}</b> · ${String(state||'')}<br>${String(detail||'')}`}
window.MUBEL_TRIPLE_STATUS=(type,state,detail)=>setStatus(type,state,detail);
window.MUBEL_TRIPLE_DEVICES=(json)=>{let a=[];try{a=JSON.parse(json||'[]')}catch(e){}const s=$('tripleBtDevices');if(!s)return;s.innerHTML='<option value="">Bluetooth cihazı seçin</option>'+a.map(x=>`<option value="${String(x.address||'').replace(/"/g,'&quot;')}" data-type="${x.type||'CLASSIC'}">${x.type||''} · ${x.name||'Adsız cihaz'} · ${x.address||''}${x.rssi?` · ${x.rssi} dBm`:''}</option>`).join('');setStatus('BLUETOOTH','HAZIR',a.length?`${a.length} cihaz bulundu. Seçip BAĞLAN'a basın.`:'Cihaz bulunamadı. Classic cihaz için önce telefon Bluetooth ayarlarından eşleştirin.')};
function boot(){css();inject();setTimeout(()=>{css();inject()},500);setTimeout(()=>{css();inject()},1500)}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',boot);else boot();
})();

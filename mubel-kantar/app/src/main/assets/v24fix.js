(function(){
'use strict';
if(window.__MUBEL_V24_LOADED__) return;
window.__MUBEL_V24_LOADED__=true;

const $=id=>document.getElementById(id);
const KEY='mubel_v24_parser';
const def={marker:1,offset:1,division:2,capacity:10000,decimals:0,stableRepeats:3};
let pcfg=Object.assign({},def,JSON.parse(localStorage.getItem(KEY)||'{}'));
function sane(){
  pcfg.marker=Number.isFinite(Number(pcfg.marker))?Number(pcfg.marker):1;
  pcfg.offset=Math.max(1,Math.min(20,parseInt(pcfg.offset,10)||1));
  pcfg.division=Math.max(0.001,Number(pcfg.division)||2);
  pcfg.capacity=Math.max(pcfg.division,Number(pcfg.capacity)||10000);
  pcfg.decimals=Math.max(0,Math.min(3,parseInt(pcfg.decimals,10)||0));
  pcfg.stableRepeats=Math.max(2,Math.min(8,parseInt(pcfg.stableRepeats,10)||3));
}
sane();
const persist=()=>localStorage.setItem(KEY,JSON.stringify(pcfg));

let streamBuf='';
let afterMarker=-1;
let markerPacket=[];
let candidateHistory=[];
let rawBytes=0;
let learning=false;
let learnTarget=4;
let learnValues=[];
let learnTimer=null;
let lastCandidate=null;
let lastTokenRaw='';

function text(id,v){const e=$(id); if(e)e.textContent=v;}
function fmt(v){return Number(v).toFixed(pcfg.decimals);}
function validWeight(v){
  if(!Number.isFinite(v)||v<0||v>pcfg.capacity)return false;
  const q=v/pcfg.division;
  return Math.abs(q-Math.round(q))<1e-7;
}
function reverseDigits(s){return parseInt(s.split('').reverse().join(''),10)||0;}
function decodeToken(body){
  let sign=1,digits=body;
  if(/[+-]$/.test(body)){
    sign=body.endsWith('-')?-1:1;
    digits=body.slice(0,-1);
  }
  if(!/^\d{7,8}$/.test(digits))return null;
  return sign*reverseDigits(digits);
}
function updateDiag(msg){
  text('decoded',msg);
  text('frameInfo',msg);
  const p=$('packetDiag'); if(p)p.textContent=msg;
}
function acceptWeight(v,why){
  if(!validWeight(v))return;
  lastCandidate=v;
  candidateHistory.push(v);
  if(candidateHistory.length>12)candidateHistory.shift();
  const recent=candidateHistory.slice(-pcfg.stableRepeats);
  const stable=recent.length===pcfg.stableRepeats && recent.every(x=>Math.abs(x-v)<0.0001);
  text('live',fmt(v));
  text('liveWeight',fmt(v));
  const st=$('stable')||$('stableText');
  if(st){st.textContent=stable?'STABİL':'HAREKETLİ';st.classList.toggle('ok',stable);st.classList.toggle('warn',!stable);st.style.color=stable?'#80d13f':'#f28c00';}
  updateDiag(`DENSİ v2.4: ${fmt(v)} kg · ${why}`);
}
function onDecoded(v,raw){
  lastTokenRaw=raw;
  if(learning)learnValues.push(v);
  if(v===pcfg.marker){
    afterMarker=0;
    markerPacket=[v];
    return;
  }
  if(afterMarker>=0){
    afterMarker++;
    markerPacket.push(v);
    if(afterMarker===pcfg.offset){
      acceptWeight(v,`marker ${pcfg.marker} + alan ${pcfg.offset} · ${raw}`);
    }
    if(afterMarker>24){afterMarker=-1;markerPacket=[];}
  }
}
function feedDensi(txt){
  streamBuf=(streamBuf+txt).slice(-32768);
  while(true){
    const eq=streamBuf.indexOf('=');
    if(eq<0){streamBuf=streamBuf.slice(-16);break;}
    if(eq>0)streamBuf=streamBuf.slice(eq);
    if(streamBuf.length<9)break;
    let body=null,consume=0;
    if(streamBuf.length>=9 && /^=\d{7}[+-]/.test(streamBuf.slice(0,9))){body=streamBuf.slice(1,9);consume=9;}
    else if(/^=\d{8}/.test(streamBuf.slice(0,9))){body=streamBuf.slice(1,9);consume=9;}
    else {streamBuf=streamBuf.slice(1);continue;}
    const v=decodeToken(body);
    const raw='='+body;
    streamBuf=streamBuf.slice(consume);
    if(v!==null)onDecoded(v,raw);
  }
}
function latin1FromB64(b64){let bin=atob(b64),s='';for(let i=0;i<bin.length;i++)s+=String.fromCharCode(bin.charCodeAt(i)&255);return {s,n:bin.length};}
function onRawB64V24(b64){
  let x;try{x=latin1FromB64(b64);}catch(e){return;}
  rawBytes+=x.n;
  text('bytes',rawBytes+' byte'); text('byteCount',rawBytes+' byte');
  const raw=$('raw');if(raw){raw.textContent=(raw.textContent+x.s).slice(-12000);raw.scrollTop=raw.scrollHeight;}
  feedDensi(x.s);
}
function bestMarkerOffset(values,target){
  const score=new Map();
  for(let i=0;i<values.length;i++){
    if(Math.abs(values[i]-target)>0.0001)continue;
    for(let d=1;d<=8 && i-d>=0;d++){
      const marker=values[i-d];
      if(marker===target || !Number.isFinite(marker) || Math.abs(marker)>1000000)continue;
      const k=marker+'|'+d;
      score.set(k,(score.get(k)||0)+1);
    }
  }
  let best=null,bestN=0;
  for(const [k,n] of score){if(n>bestN){bestN=n;best=k;}}
  if(!best)return null;
  const [marker,offset]=best.split('|').map(Number);
  return {marker,offset,hits:bestN};
}
function stopLearning(){
  if(learnTimer){clearTimeout(learnTimer);learnTimer=null;}
  learning=false;
  const res=bestMarkerOffset(learnValues,learnTarget);
  if(res && res.hits>=2){
    pcfg.marker=res.marker;pcfg.offset=res.offset;persist();
    afterMarker=-1;candidateHistory=[];
    const m=$('v24Marker');if(m)m.value=pcfg.marker;
    const o=$('v24Offset');if(o)o.value=pcfg.offset;
    text('v24LearnStatus',`ÖĞRENİLDİ ✓ ${learnTarget} kg → marker ${pcfg.marker}, alan ${pcfg.offset} (${res.hits} eşleşme)`);
    updateDiag(`ÖĞRENİLDİ: gerçek ${learnTarget} kg · marker ${pcfg.marker} + alan ${pcfg.offset}`);
  }else{
    text('v24LearnStatus',`Öğrenme tamamlanamadı. Kantar ${learnTarget} kg'da sabitken tekrar bas.`);
  }
}
function startLearning(){
  const inp=$('v24Known');
  learnTarget=Number(inp?inp.value:4);
  if(!Number.isFinite(learnTarget)||learnTarget<0){text('v24LearnStatus','Geçerli gerçek kg gir.');return;}
  learnValues=[];learning=true;
  text('v24LearnStatus',`ÖĞRENİYOR… Kantar ${learnTarget} kg'da SABİT kalsın (6 sn)`);
  learnTimer=setTimeout(stopLearning,6000);
}
function saveParserSettings(){
  const m=$('v24Marker'),o=$('v24Offset'),d=$('v24Division'),c=$('v24Capacity');
  pcfg.marker=Number(m?m.value:1);pcfg.offset=parseInt(o?o.value:1,10);pcfg.division=Number(d?d.value:2);pcfg.capacity=Number(c?c.value:10000);sane();persist();
  afterMarker=-1;candidateHistory=[];
  text('v24LearnStatus',`Kaydedildi ✓ marker ${pcfg.marker}, ağırlık alanı ${pcfg.offset}`);
}
function addV24UI(){
  document.querySelectorAll('.ver').forEach(e=>e.textContent='v2.4 · Android · DENSİ CX Akıllı Protokol');
  const settings=$('tab-settings');
  if(settings && !$('v24Panel')){
    const card=document.createElement('div');card.id='v24Panel';card.className='card';
    card.innerHTML=`<div class="sectionTitle"><b>DENSİ AKILLI PROTOKOL v2.4</b></div>
      <div class="notice">37 kg gibi yanlış değerlerin sebebi, paketteki başka alanların kilo sanılmasıydı. Bu bölüm gerçek ağırlık alanını bir kez öğrenir ve kalıcı saklar.</div>
      <div class="grid2" style="margin-top:10px">
        <div class="field"><label>Marker / paket başlangıcı</label><input id="v24Marker" type="number" value="${pcfg.marker}"></div>
        <div class="field"><label>Ağırlık alanı (marker sonrası)</label><input id="v24Offset" type="number" min="1" max="20" value="${pcfg.offset}"></div>
        <div class="field"><label>Taksimat (kg)</label><input id="v24Division" type="number" value="${pcfg.division}"></div>
        <div class="field"><label>Kapasite (kg)</label><input id="v24Capacity" type="number" value="${pcfg.capacity}"></div>
      </div>
      <button id="v24Save" class="btn" type="button">PROTOKOLÜ KAYDET</button>
      <div style="margin-top:14px;padding:12px;border:1px solid #3a3f46;border-radius:12px;background:#15171a">
        <div class="sectionTitle">Tek Seferlik Akıllı Öğretme</div>
        <div class="notice">Vinç ekranındaki gerçek kiloyu yaz. Vinç sabitken <b>BU KG'Yİ ÖĞRET</b>'e bas ve 6 saniye bekle. Sonra tekrar APK değiştirmeden ağırlık alanını kendisi kullanır.</div>
        <div class="row" style="margin-top:9px"><div class="grow"><label>Vinç ekranındaki gerçek kg</label><input id="v24Known" type="number" value="4"></div><button id="v24Learn" class="btn primary" type="button">BU KG'Yİ ÖĞRET</button></div>
        <div id="v24LearnStatus" class="notice" style="margin-top:8px">Hazır · varsayılan marker ${pcfg.marker}, alan ${pcfg.offset}</div>
      </div>`;
    const oldCards=settings.querySelectorAll('.card');
    if(oldCards.length>1)settings.insertBefore(card,oldCards[1]); else settings.appendChild(card);
    $('v24Learn').onclick=startLearning;$('v24Save').onclick=saveParserSettings;
  }
  const diag=$('tab-diag');
  if(diag && !$('packetDiag')){
    const p=document.createElement('div');p.id='packetDiag';p.className='pill';p.style.marginBottom='8px';p.textContent=`v2.4 hazır · marker ${pcfg.marker} + alan ${pcfg.offset}`;
    const card=diag.querySelector('.card');if(card)card.insertBefore(p,card.querySelector('.raw')||card.firstChild);
  }
  const factor=$('factor');if(factor){factor.value='1';factor.disabled=true;factor.title='DENSİ v2.4 protokolünde çarpan 1 sabittir';}
  const dec=$('decimals');if(dec){dec.value='0';}
}
function install(){
  addV24UI();
  if(!window.MUBEL){setTimeout(install,100);return;}
  window.MUBEL.onRawB64=onRawB64V24;
  const clear=$('clearRaw');if(clear && !clear.__v24){clear.__v24=true;clear.addEventListener('click',()=>{streamBuf='';afterMarker=-1;candidateHistory=[];rawBytes=0;learnValues=[];text('packetDiag',`v2.4 hazır · marker ${pcfg.marker} + alan ${pcfg.offset}`);});}
}
install();
})();

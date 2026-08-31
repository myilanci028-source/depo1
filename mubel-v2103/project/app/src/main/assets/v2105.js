(()=>{'use strict';
const $=id=>document.getElementById(id);
let selectedRecordId=null,lastImport='';
const esc=s=>String(s??'').trim();
function toast(t){if(window.MUBEL&&typeof MUBEL.toast==='function')MUBEL.toast(t)}
function setVersion(){document.querySelectorAll('.ver').forEach(e=>{e.textContent=e.textContent.replace(/v2\.[0-9]+(?:\.[0-9]+)?/g,'v2.10.5')})}
function css(){if($('v2105css'))return;const s=document.createElement('style');s.id='v2105css';s.textContent=`
.transferBtn{background:#24547a!important;color:#fff!important}.transferNote{font-size:12px;color:#aeb3bb;margin-top:9px;line-height:1.45}.transferFlash{outline:2px solid #4aa35b!important;box-shadow:0 0 0 4px rgba(74,163,91,.18)!important;transition:.25s}.transferBanner{display:none;margin:12px 0;padding:14px 16px;border:1px solid #3b8f46;background:#122617;border-radius:14px;color:#fff;line-height:1.45}.transferBanner.show{display:block}.transferBanner b{display:block;font-size:17px;margin-bottom:4px}
@media(max-width:680px){#shareCurrentV2105{width:100%}.transferNote{font-size:11px}}
`;document.head.appendChild(s)}
function currentData(){const g=id=>$(id)?$(id).value:'';return{cari:g('cari'),urun:g('urun'),plaka:g('plaka'),irsaliye:g('irsaliye'),geldigi:g('geldigi'),gidecegi:g('gidecegi'),aciklama:g('aciklama')}}
function payload(r){return{type:'mubel-kantar-transfer',schema:1,fromVersion:'2.10.5',cari:esc(r.cari),urun:esc(r.urun),plaka:esc(r.plaka),irsaliye:esc(r.irsaliye),geldigi:esc(r.geldigi),gidecegi:esc(r.gidecegi),aciklama:String(r.aciklama??'')}}
function summary(p){return `MUBEL KANTAR BİLGİ AKTARIMI\nCari / Firma: ${p.cari||'-'}\nMalzeme / Ürün: ${p.urun||'-'}\nAraç Plaka: ${p.plaka||'-'}\nİrsaliye / Belge: ${p.irsaliye||'-'}\nGeldiği Yer: ${p.geldigi||'-'}\nGideceği Yer: ${p.gidecegi||'-'}\nAçıklama: ${p.aciklama||'-'}`}
function shareRecord(r){try{if(!window.Transfer||typeof Transfer.shareTransfer!=='function'){toast('Hızlı aktarım servisi bulunamadı');return}const p=payload(r);Transfer.shareTransfer(JSON.stringify(p),summary(p));toast('Paylaşım ekranı açılıyor…')}catch(e){toast('Paylaşım açılamadı: '+(e&&e.message?e.message:e))}}
function installButtons(){const formCard=$('save')?.closest('.card');if(formCard&&!$('shareCurrentV2105')){const row=$('save').closest('.row');const b=document.createElement('button');b.id='shareCurrentV2105';b.className='btn transferBtn';b.textContent='📤 BİLGİLERİ PAYLAŞ / AKTAR';b.onclick=()=>shareRecord(currentData());row.appendChild(b);const note=document.createElement('div');note.className='transferNote';note.textContent='Hızlı aktarım: Cari/Firma, Ürün, Plaka, İrsaliye, Geldiği/Gideceği Yer ve Açıklama gönderilir. Fiş, tarih, ağırlıklar ve operatör karşı kantarda yeni oluşur.';row.insertAdjacentElement('afterend',note);const banner=document.createElement('div');banner.id='transferBannerV2105';banner.className='transferBanner';banner.innerHTML='<b>✓ HIZLI AKTARIM ALINDI</b><span id="transferBannerText"></span>';note.insertAdjacentElement('afterend',banner)}
const actions=document.querySelector('#printModal .modalActions');if(actions&&!$('recordShareV2105')){const b=document.createElement('button');b.id='recordShareV2105';b.className='btn transferBtn';b.textContent='📤 BİLGİLERİ PAYLAŞ / AKTAR';b.onclick=()=>{const r=window.MUBEL&&typeof MUBEL.findRecord==='function'?MUBEL.findRecord(selectedRecordId):null;if(!r)return toast('Kayıt bulunamadı');shareRecord(r)};actions.insertBefore(b,$('recordCancel'))}
document.addEventListener('click',e=>{const p=e.target&&e.target.closest?e.target.closest('[data-print]'):null;if(p)selectedRecordId=Number(p.dataset.print)},true)}
function applyTransfer(raw){if(!raw||raw===lastImport)return;let p;try{p=JSON.parse(raw)}catch(e){return}if(p.type!=='mubel-kantar-transfer'||Number(p.schema)!==1)return;lastImport=raw;const main=$('main');if(!main||main.classList.contains('hidden'))return;
const home=document.querySelector('[data-tab="home"]');if(home)home.click();if($('new'))$('new').click();
const fields=['cari','urun','plaka','irsaliye','geldigi','gidecegi','aciklama'];fields.forEach(k=>{if($(k))$(k).value=String(p[k]??'')});
// Fiş/Tarih ve ağırlıklar newForm() ile yeni oluştu; Operatör mevcut kullanıcı olarak korunur.
const banner=$('transferBannerV2105'),text=$('transferBannerText');if(text)text.textContent=`${p.cari||'-'} · ${p.urun||'-'} · ${p.plaka||'-'} bilgileri forma aktarıldı.`;if(banner){banner.classList.add('show');setTimeout(()=>banner.classList.remove('show'),9000)}
['cari','urun','plaka','irsaliye','geldigi','gidecegi','aciklama'].forEach(k=>{const e=$(k);if(e){e.classList.add('transferFlash');setTimeout(()=>e.classList.remove('transferFlash'),1800)}});
toast('✓ Hızlı aktarım forma yüklendi');setTimeout(()=>{$('cari')?.scrollIntoView({behavior:'smooth',block:'center'})},120)}
function poll(){try{const main=$('main');if(!main||main.classList.contains('hidden'))return;if(window.Transfer&&typeof Transfer.consumeTransfer==='function'){const raw=Transfer.consumeTransfer();if(raw)applyTransfer(raw)}}catch(e){}}
function boot(){css();setVersion();installButtons();setTimeout(()=>{setVersion();installButtons();poll()},450);setInterval(poll,750)}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',boot);else boot();
})();

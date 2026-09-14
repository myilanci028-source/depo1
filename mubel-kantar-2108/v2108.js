(()=>{'use strict';
const $=id=>document.getElementById(id);
function boot(){
  document.querySelectorAll('.ver').forEach((e,i)=>{e.textContent=i===0?'v2.10.8':'v2.10.8 · Android'});
  const actions=document.querySelector('#tab-home .actions');
  if(actions&&!$('cameraLiveBtn')){
    const b=document.createElement('button');
    b.id='cameraLiveBtn';b.className='btn orange';b.innerHTML='📷 KAMERA CANLI VERİ';
    const a4=$('a4'); if(a4)actions.insertBefore(b,a4); else actions.appendChild(b);
    b.onclick=()=>{try{if(window.Android&&typeof Android.startCameraLive==='function')Android.startCameraLive();else MUBEL.toast('Kamera servisi bulunamadı')}catch(e){MUBEL.toast('Kamera açılamadı: '+e)}};
  }
  if($('mubelTripleCard')){
    const badge=$('mubelTripleCard').querySelector('.tripleBadge');
    if(badge)badge.textContent='2.10.7 STABİL + KAMERA 2.10.8';
  }
}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',boot);else boot();
setTimeout(boot,700);setTimeout(boot,1600);
})();

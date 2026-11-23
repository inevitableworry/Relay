"use strict";
(() => {
  let pin = "";
  const $ = id => document.getElementById(id);
  const announce = (message, error=false) => { $("notice").textContent=message; $("notice").style.color=error?"#ffa8a8":"#d3c7ff"; };
  async function post(endpoint, body={}) {
    const response=await fetch(endpoint,{method:"POST",headers:{"Content-Type":"application/json","X-Relay-Setup-Pin":pin},body:JSON.stringify(body),cache:"no-store"});
    const value=await response.json();
    if(!response.ok) throw new Error(value.message||value.error||String(response.status));
    return value;
  }
  async function refresh() {
    const status=await fetch("/api/status",{cache:"no-store"}).then(r=>r.json());
    $("host-status").textContent=(status.paired?"Paired":"Unpaired")+" — "+status.status;
    $("social-origin").textContent=status.social;
  }
  document.querySelectorAll("nav button").forEach(button=>button.addEventListener("click",()=>{
    const tab=button.dataset.tab;
    document.querySelectorAll(".panel").forEach(p=>p.classList.toggle("hidden",p.id!==tab));
    document.querySelectorAll("nav button").forEach(b=>b.classList.toggle("selected",b===button));
    $("heading").textContent=tab[0].toUpperCase()+tab.slice(1);
  }));
  $("unlock").addEventListener("click",async()=>{
    pin=$("setup-pin").value.trim();
    if(!/^[0-9]{6,12}$/.test(pin)) return announce("Enter the local setup PIN.",true);
    $("auth-overlay").classList.add("hidden");
    try{await refresh();announce("Local setup unlocked; capture remains disabled.");}catch(e){announce(e.message,true);}
  });
  $("start-pair").addEventListener("click",async()=>{
    try {
      const result=await post("/api/pair/start",{name:$("console-name").value.trim()});
      $("pair-output").classList.remove("hidden");
      $("pair-code").textContent=result.code;
      $("pair-url").textContent=result.url; $("pair-url").href=result.url;
      $("pair-qr").src=result.qrDataUri||"";$("pair-qr").style.display=result.qrDataUri?"block":"none";
      announce("Pairing created; approve on Relay Social.");
    }catch(e){announce(e.message,true);}
  });
  $("check-pair").addEventListener("click",async()=>{
    try{const result=await post("/api/pair/status",{});announce(result.status==="paired"?"Profile paired.":"Waiting for approval.");await refresh();}
    catch(e){announce(e.message,true);}
  });
  $("save-privacy").addEventListener("click",async()=>{
    try{const result=await post("/api/settings",{privacy:{
      streaming_enabled:false,auto_profile_sync:$("sync").checked,
      friends_can_request:$("friends").checked}});announce(result.warning||"Settings saved.");}
    catch(e){announce(e.message,true);}
  });
  refresh().catch(e=>announce(e.message,true));
})();

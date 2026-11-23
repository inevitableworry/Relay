/* Relay Windows 11 and ROG Xbox Ally: bridge to read-only native PBS probe.
   Never changes the PS5 capture profile or controller ownership. */
(function () {
  'use strict';
  var pending=null;
  var webview=window.chrome && window.chrome.webview;
  window.RelayWindowsBridge={
    check:function(host,callback){
      if(!webview){callback('Windows WebView2 host is unavailable.');return;}
      pending=callback;
      webview.postMessage({type:'pbs-config',host:host});
    }
  };
  if(webview){
    webview.addEventListener('message',function(event){
      var info=event.data;
      if(!info || info.type!=='pbs-config-result' || !pending)return;
      var callback=pending;pending=null;
      var text=info.status || 'PS5 capture service returned no status.';
      if(info.config && info.config.ready){
        text += ' '+info.config.width+' × '+info.config.height+' at '+info.config.fps+' FPS.';
      }
      callback(text);
    });
  }
  // WebView2 supports the W3C Gamepad API on devices/controllers exposed by
  // Windows. Feed the same keyboard navigation as Xbox Relay's JS UWP target.
  var lastButtons=0;
  var keyCodes={0:13,1:27,4:37,5:39,9:77};
  function dispatch(keyCode){
    var event=document.createEvent('Event');
    event.initEvent('keydown',true,true);
    try{Object.defineProperty(event,'keyCode',{get:function(){return keyCode;}});}catch(_){}
    document.dispatchEvent(event);
  }
  window.setInterval(function(){
    try{
      var sources=navigator.getGamepads && navigator.getGamepads();
      var pad=sources && Array.prototype.find.call(sources,function(p){return !!p;});
      if(!pad){lastButtons=0;return;}
      var bits=0;
      for(var i in keyCodes)if(pad.buttons[Number(i)] && pad.buttons[Number(i)].pressed)
        bits |= (1<<Number(i));
      for(var b in keyCodes){var bit=1<<Number(b);
        if((bits&bit) && !(lastButtons&bit))dispatch(keyCodes[b]);}
      lastButtons=bits;
    }catch(_){ }
  },50);
})();

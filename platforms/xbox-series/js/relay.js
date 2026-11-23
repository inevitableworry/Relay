/* SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
 * Xbox Series S/X UWP JavaScript Relay developer edition.
 * Reimplements the existing PortalBackdropView's radial shader stops,
 * 2600 seeded particles, 19 clouds, 0.075 rad/s orbit and optical bloom.
 * Main UI and audio cues are from the Android Relay project, not another port.
 */
(function () {
    'use strict';
    var pbs = window.RelayPbs;
    var data = window.RELAY_PARTICLES;
    var canvas = document.getElementById('sphere');
    var ctx = canvas.getContext('2d', { alpha:false });
    var started = Date.now(), tab = 0, quick = false, panel = false, gamepadLast = 0;
    var host = localStorage.getItem('relay.xbox.ps5host') || '';
    var isXboxWinRT = typeof Windows !== 'undefined' && Windows.Web && Windows.Web.Http;
    var lastFrameAt = 0;
    var imageWidth = 0, imageHeight = 0;
    var musicStarted = false, focusIndex = 0;
    var buttons = ['tab-remote','tab-cloud','tab-search','connect-button','quick-toggle'];
    var audioCues = {focus:'sound-focus',confirm:'sound-confirm',back:'sound-back',
        open:'sound-open',close:'sound-close',reveal:'sound-reveal'};
    var tabs = [document.getElementById('tab-remote'),document.getElementById('tab-cloud'),
        document.getElementById('tab-search')];
    var panelElement = document.getElementById('panel');

    function playCue(name) {
        var element = document.getElementById(audioCues[name]);
        if (!element) return;
        try { element.currentTime=0; var play=element.play(); if(play && play.catch)play.catch(function(){}); } catch (_) {}
    }
    function startMusic() {
        if (musicStarted) return;
        musicStarted = true;
        var music = document.getElementById('sound-music');
        music.volume = .55;
        try {var result=music.play(); if(result && result.catch) result.catch(function(){});}catch(_){}
    }
    function resetHome() {
        for(var i=0;i<tabs.length;i++){
            tabs[i].className = 'tab'+(i===tab?' selected':'');
            tabs[i].setAttribute('aria-current',i===tab?'page':'false');
        }
        document.getElementById('nav-artwork').src='Assets/portal_nav_'+['remote','cloud','search'][tab]+'.png';
        var headings=['Connect to your PS5','Cloud Streaming','Search'];
        var subtitles=['Remote Play JB  ·  '+host,'PlayStation Plus cloud services','Search for a console'];
        document.getElementById('home-heading').textContent=headings[tab];
        document.getElementById('home-subtitle').textContent=subtitles[tab];
        document.getElementById('connect-button').textContent = tab===0?'Connect':tab===1?'Cloud Status':'Search';
    }
    function chooseTab(next) {
        if(quick || panel) return;
        next=(next+3)%3;
        if(next===tab)return;
        tab=next;playCue('focus');resetHome();
    }
    function setQuick(open) {
        if(panel)return;
        quick=open;
        var menu=document.getElementById('quick-menu');
        menu.className=quick?'quick-menu':'quick-menu hidden';
        menu.setAttribute('aria-hidden',quick?'false':'true');
        playCue(open?'open':'close');
        if(open){focusIndex=0; var first=menu.querySelector('button');if(first)first.focus();}
        else document.getElementById('quick-toggle').focus();
    }
    function closePanel() {
        panel=false;
        panelElement.className='dialog hidden';panelElement.setAttribute('aria-hidden','true');
        playCue('back');
        document.getElementById('connect-button').focus();
    }
    function showPanel(title,html,primaryLabel,click) {
        if(quick){setQuick(false);}
        panel=true;
        document.getElementById('panel-heading').textContent=title;
        document.getElementById('panel-body').innerHTML=html;
        document.getElementById('panel-status').textContent='';
        var action=document.getElementById('panel-primary');
        action.textContent=primaryLabel;
        action.onclick=click;
        panelElement.className='dialog';panelElement.setAttribute('aria-hidden','false');
        playCue('open');
        action.focus();
    }
    function setStatus(message) {document.getElementById('panel-status').textContent=message;}
    function escapeHtml(value) {
        return String(value).replace(/&/g,'&amp;').replace(/</g,'&lt;')
            .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }
    function checkConfig() {
        var input=document.getElementById('ps5host');
        var value=input?input.value.trim():host;
        if(!pbs.validHost(value)){setStatus('Enter a valid PS5 LAN address or hostname.');return;}
        host=value;localStorage.setItem('relay.xbox.ps5host',host);resetHome();
        setStatus('Checking the PS5 PBS service at '+host+':9999. No console settings will be changed.');
        if(!isXboxWinRT){
            setStatus('Windows UWP network APIs are required for this check; browser preview cannot contact the PS5.');
            return;
        }
        var client = new Windows.Web.Http.HttpClient();
        var url = new Windows.Foundation.Uri('http://'+host+':9999/config.json');
        client.getStringAsync(url).then(function (body) {
            try {
                var report=pbs.parseConfig(body);
                if(!report.ready){
                    setStatus('PS5 PBS was found, but H.264 capture is not ready. Do not bypass the existing PS5 recovery lock.');
                }else{
                    setStatus('PS5 capture is available at '+report.width+' × '+report.height+
                        ' / '+report.fps+' FPS ('+report.codec+'). Live playback has not been enabled in this UWP development build.');
                }
            } catch(ex) {setStatus('The endpoint did not return a compatible PBS configuration: '+ex.message);}
            client.close();
        }, function (error) {
            setStatus('Cannot reach the PS5 capture service. Verify the console and PBS payload are running. '+
                (error && error.message?error.message:''));
            client.close();
        });
    }
    function openConnect() {
        startMusic();playCue('confirm');
        if(tab!==0){
            showPanel(tab===1?'Cloud Streaming':'Console Discovery',
                '<p>This developer edition preserves the Relay UI, but no cloud-session or PSN device-discovery backend is connected yet.</p>',
                'Close',closePanel);
            return;
        }
        showPanel('Remote Play JB — Xbox Developer Mode',
            '<label for="ps5host">PS5 LAN address</label><input id="ps5host" type="text" value="'+escapeHtml(host)+'" />'+
            '<p>The network check reads Relay’s original PBS <code>/config.json</code>. It does not acquire controller ownership, '+
            'change the PS5 encoder, override capture recovery, or start video streaming.</p>'+ 
            '<p class="subtle">This is a developer build for Xbox Series S/X. Native video playback and gamepad transmission require Xbox hardware testing.</p>',
            'Check PS5',checkConfig);
    }
    function onMenuAction(name) {
        setQuick(false);startMusic();playCue('confirm');
        if(name==='connect'){openConnect();return;}
        if(name==='close')return;
        if(name==='settings'){
            showPanel('Relay Settings',
                '<p>Platform: Xbox Series S/X UWP Developer Mode. LAN PBS endpoint: '+escapeHtml(host)+':9999.</p>'+ 
                '<p>The original Relay layout, music, focus sounds and sphere effects are included. '+
                'No new menu theme or replacement animations were introduced.</p>',
                'Close',closePanel);
        }else{
            showPanel(name==='quality'?'Video Quality':'Audio Output',
                '<p>This is a development-only view. Video and audio settings are not applied to the PS5 yet.</p>',
                'Close',closePanel);
        }
    }
    function back() {
        if(panel){closePanel();return;}
        if(quick){setQuick(false);return;}
        if(tab!==0){chooseTab(0);return;}
        playCue('back');
    }
    function canvasResize() {
        var w=Math.max(1,window.innerWidth),h=Math.max(1,window.innerHeight);
        if(w===imageWidth && h===imageHeight)return;
        imageWidth=w;imageHeight=h;
        // CSS pixel backing avoids enormous upscales on 4K TVs.
        canvas.width=w;canvas.height=h;
    }
    function gradientStops(g,stops) {
        for(var i=0;i<stops.length;i++)g.addColorStop(stops[i][0],stops[i][1]);
        return g;
    }
    function paintSphere(t) {
        canvasResize();
        var w=canvas.width,h=canvas.height;
        ctx.setTransform(1,0,0,1,0,0);
        ctx.clearRect(0,0,w,h);
        ctx.scale(w/1920,h/1080);
        var bg=gradientStops(ctx.createLinearGradient(0,0,0,1080),[
            [0,'#030307'],[.67,'#1a1a22'],[1,'#23232b']]);
        ctx.fillStyle=bg;ctx.fillRect(0,0,1920,1080);
        var cx=960,cy=540,base=Math.min(1080*.3315,1920*.40);
        var g=Math.min(1,Math.max(0,(t-.1)/.9));g=1-(1-g)*(1-g);
        var radius=base*(.04+.96*g);
        var rim=Math.min(1,Math.max(0,(2.7-t)/1.8));
        ctx.save();ctx.translate(cx,1080*.916);ctx.scale(base*1.4,base*.085);
        ctx.beginPath();ctx.arc(0,0,1,0,Math.PI*2);
        ctx.fillStyle=gradientStops(ctx.createRadialGradient(0,0,0,0,0,1),[
            [0,'rgba(77,78,170,.204)'],[1,'rgba(77,78,170,0)']]);ctx.fill();ctx.restore();
        ctx.save();ctx.translate(cx,cy);ctx.scale(radius,radius);
        ctx.globalAlpha=(215+rim*40)/255;
        ctx.fillStyle=gradientStops(ctx.createRadialGradient(.12,-.06,0,.12,-.06,1.85),[
            [0,'rgba(46,26,145,0)'],[.49,'rgba(51,35,186,.455)'],
            [.70,'rgba(39,35,103,.29)'],[1,'rgba(0,0,0,0)']]);
        ctx.beginPath();ctx.arc(0,0,1.85,0,Math.PI*2);ctx.fill();
        ctx.globalAlpha=g;
        ctx.fillStyle=gradientStops(ctx.createRadialGradient(0,0,0,0,0,1.018),[
            [0,'#170e3e'],[.72,'#160e38'],[.968,'#1b1248'],[1,'rgba(27,18,72,0)']]);
        ctx.beginPath();ctx.arc(0,0,1.018,0,Math.PI*2);ctx.fill();ctx.restore();
        var c=Math.cos(t*.075),s=Math.sin(t*.075);
        ctx.save();ctx.beginPath();ctx.arc(cx,cy,radius,0,Math.PI*2);ctx.clip();
        for(var cl=0;cl<data.clusters.length;cl++){
            var cluster=data.clusters[cl];
            var x=cluster[0]*c+cluster[2]*s,depth=-cluster[0]*s+cluster[2]*c;
            var y=cluster[1],spread=.12+cluster[3]*1.45;
            var xx=cx+(x*.974+y*.225)*.94*radius;
            var yy=cy+(y*.974-x*.225)*.94*radius;
            ctx.save();ctx.translate(xx,yy);ctx.scale(radius*spread,radius*spread*1.6);
            ctx.globalAlpha=Math.min(1,Math.max(0,(40+(depth+1)*34)*g/255));
            ctx.fillStyle=gradientStops(ctx.createRadialGradient(0,0,0,0,0,1),[
                [0,'rgba(60,32,255,.40)'],[.36,'rgba(36,11,232,.16)'],[1,'rgba(0,0,0,0)']]);
            ctx.beginPath();ctx.arc(0,0,1,0,Math.PI*2);ctx.fill();ctx.restore();
        }
        ctx.restore();
        var scale=radius/358;
        for(var p=0;p<data.points.length;p++){
            var point=data.points[p];
            var x1=point[0]*c+point[2]*s,depth=-point[0]*s+point[2]*c;
            var y1=point[1]+.014*Math.sin(t*.55+point[3])*(1-point[1]*point[1]);
            var px=cx+(x1*.974+y1*.225)*radius;
            var py=cy+(y1*.974-x1*.225)*radius;
            var twinkle=.81+.19*Math.sin(t*1.3+point[3]);
            var strength=point[4]*(.52+.48*(depth+1)*.5)*twinkle;
            var bin=Math.max(0,Math.min(7,Math.floor(strength*7.99)));
            if(bin>=2){
                ctx.fillStyle='rgba(30,18,255,'+( (2+bin*.6)*g/255)+')';
                ctx.beginPath();ctx.arc(px,py,(10+bin*1.4)*scale*.5,0,Math.PI*2);ctx.fill();
                ctx.fillStyle='rgba(30,18,255,'+((5+bin*1.5)*g/255)+')';
                ctx.beginPath();ctx.arc(px,py,(5.5+bin*.9)*scale*.5,0,Math.PI*2);ctx.fill();
                ctx.fillStyle='rgba(30,18,255,'+((8+bin*2)*g/255)+')';
                ctx.beginPath();ctx.arc(px,py,(2.6+bin*.45)*scale*.5,0,Math.PI*2);ctx.fill();
            }
            ctx.fillStyle='rgba('+(20+bin*3)+','+(22+bin*3)+',255,'+
                (Math.min(255,(48+bin*25)*g)/255)+')';
            ctx.beginPath();ctx.arc(px,py,(1.05+bin*.22)*scale*.5,0,Math.PI*2);ctx.fill();
            if(p%31===0 && depth>-.4){
                var glow=(6+strength*11)*scale;
                ctx.save();ctx.globalAlpha=(190+65*strength)*g/255;
                ctx.fillStyle=gradientStops(ctx.createRadialGradient(px,py,0,px,py,glow),[
                    [0,'#ecf0ff'],[.08,'#bfcaff'],[.19,'#6675ff'],
                    [.38,'rgba(47,37,255,.50)'],[1,'rgba(0,0,0,0)']]);
                ctx.beginPath();ctx.arc(px,py,glow,0,Math.PI*2);ctx.fill();ctx.restore();
            }
        }
        if(t<2.7){ctx.save();ctx.globalAlpha=rim;
            ctx.strokeStyle='#dfe1ff';ctx.lineWidth=radius*.005;
            ctx.beginPath();ctx.arc(cx,cy,radius,(t*570-90)*Math.PI/180,
                (t*570-90+Math.min(g*430,360))*Math.PI/180);ctx.stroke();ctx.restore();}
    }

    function frame(timestamp) {
        if(!lastFrameAt || timestamp-lastFrameAt>=24){
            paintSphere((Date.now()-started)/1000);
            lastFrameAt=timestamp;
        }
        window.requestAnimationFrame(frame);
    }
    function pollGamepad() {
        try {
            var win=typeof Windows!=='undefined' && Windows.Gaming && Windows.Gaming.Input;
            if(win){
                var gamepads=Windows.Gaming.Input.Gamepad.gamepads;
                if(gamepads.length){
                    var r=gamepads[0].getCurrentReading(), mask=r.buttons;
                    var b=Windows.Gaming.Input.GamepadButtons;
                    var newly=mask&~gamepadLast;
                    if(newly & b.leftShoulder)chooseTab(tab-1);
                    if(newly & b.rightShoulder)chooseTab(tab+1);
                    if(newly & b.menu)setQuick(!quick);
                    if(newly & b.b)back();
                    if(newly & b.a){
                        if(panel)document.getElementById('panel-primary').click();
                        else if(quick){var all=document.querySelectorAll('.quick-menu .menu-item');
                            if(all[focusIndex])all[focusIndex].click();}
                        else openConnect();
                    }
                    if(quick && (newly & b.dPadDown)){
                        focusIndex=Math.min(4,focusIndex+1);
                        document.querySelectorAll('.quick-menu .menu-item')[focusIndex].focus();playCue('focus');
                    }
                    if(quick && (newly & b.dPadUp)){
                        focusIndex=Math.max(0,focusIndex-1);
                        document.querySelectorAll('.quick-menu .menu-item')[focusIndex].focus();playCue('focus');
                    }
                    gamepadLast=mask;
                }
            }
        }catch(_){ /* Physical gamepads are optional while viewing via a PC. */ }
        window.setTimeout(pollGamepad,45);
    }
    function startup() {
        resetHome();
        tabs.forEach(function (button,i) {button.onclick=function(){startMusic();chooseTab(i);};});
        document.getElementById('connect-button').onclick=openConnect;
        document.getElementById('quick-toggle').onclick=function(){startMusic();setQuick(!quick);};
        Array.prototype.forEach.call(document.querySelectorAll('.menu-item'),function (button){
            button.onclick=function(){onMenuAction(button.getAttribute('data-action'));};
        });
        document.getElementById('panel-secondary').onclick=closePanel;
        document.addEventListener('keydown',function(e){
            if(e.keyCode===27||e.keyCode===8){back();e.preventDefault();}
            if(e.keyCode===37 || e.keyCode===81){chooseTab(tab-1);e.preventDefault();}
            if(e.keyCode===39 || e.keyCode===69){chooseTab(tab+1);e.preventDefault();}
            if(e.keyCode===77){setQuick(!quick);e.preventDefault();}
            if(e.keyCode===13 && !panel && !quick){openConnect();e.preventDefault();}
        });
        var clock=function () {
            var now=new Date();
            document.getElementById('clock').textContent=now.toLocaleTimeString(undefined,{hour:'numeric',minute:'2-digit'});
        };
        clock();setInterval(clock,15000);
        window.requestAnimationFrame(frame);
        pollGamepad();
    }
    if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',startup);
    else startup();
})();

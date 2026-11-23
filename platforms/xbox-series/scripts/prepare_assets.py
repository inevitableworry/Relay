"""Copy original Relay artwork and audio for the Xbox UWP developer package.

No other remote-play UI assets are used. OGG cues are converted into PCM WAV
because Xbox's legacy JavaScript UWP WebView may not support Ogg Vorbis.
The menu music loop is wrapped from the original s16le PCM bitstream losslessly.
"""
from pathlib import Path
import hashlib
import json
import math
import shutil
import struct
import subprocess
import wave
from PIL import Image

ROOT=Path(__file__).resolve().parents[3]
BASE=ROOT/'platforms/xbox-series'
ASSETS=BASE/'Assets'
ANDROID=ROOT/'android/app/src/main'
SWITCH=ROOT/'platforms/switch1/romfs/relay/nav'


def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):
            h.update(chunk)
    return h.hexdigest()


class JavaRandom:
    """Java.util.Random, including the same cached polar Box-Muller sequence."""
    def __init__(self, seed):
        self.state=(seed^0x5DEECE66D)&((1<<48)-1)
        self.cached=None

    def nextbits(self,bits):
        self.state=(self.state*0x5DEECE66D+0xB)&((1<<48)-1)
        return self.state>>(48-bits)

    def nextfloat(self):
        return self.nextbits(24)/float(1<<24)

    def nextdouble(self):
        return ((self.nextbits(26)<<27)+self.nextbits(27))/float(1<<53)

    def gaussian(self):
        if self.cached is not None:
            g=self.cached
            self.cached=None
            return g
        while True:
            v1=2*self.nextdouble()-1
            v2=2*self.nextdouble()-1
            s=v1*v1+v2*v2
            if 0<s<1:break
        m=math.sqrt(-2*math.log(s)/s)
        self.cached=v2*m
        return v1*m


def particles():
    random=JavaRandom(0x504F5254414C)
    clusters=[]
    for i in range(19):
        latitude=-.91+i*1.82/18
        angle=i*2.39996+random.nextfloat()*.45
        circle=math.sqrt(1-latitude*latitude)
        clusters.append([math.cos(angle)*circle,latitude,math.sin(angle)*circle,
                         .055+random.nextfloat()*.105])
    points=[]
    for i in range(2600):
        cl=clusters[i%19]
        if i%11==0:
            latitude=random.nextfloat()*2-1
            angle=random.nextfloat()*2*math.pi
            circle=math.sqrt(1-latitude*latitude)
            x=math.cos(angle)*circle
            y=latitude
            z=math.sin(angle)*circle
        else:
            x=cl[0]+random.gaussian()*cl[3]
            y=cl[1]+random.gaussian()*cl[3]*1.8
            z=cl[2]+random.gaussian()*cl[3]
        shell=.87+random.nextfloat()*.12
        length=math.sqrt(x*x+y*y+z*z)
        points.append([x/length*shell,y/length*shell,z/length*shell,
                       random.nextfloat()*2*math.pi,.42+random.nextfloat()*.58])
    out=BASE/'js/particles.js'
    out.write_text('/* Generated from Relay Android PortalBackdropView.kt seeded particle layout. */\n'+
                   'window.RELAY_PARTICLES='+json.dumps({'clusters':clusters,'points':points},
                       separators=(',',':'))+';\n',encoding='utf-8')
    return out


def prepare():
    ASSETS.mkdir(parents=True,exist_ok=True)
    provenance={}
    for name in ('portal_nav_remote.png','portal_nav_cloud.png','portal_nav_search.png',
                 'portal_status_wifi.png','portal_status_sliders.png','portal_status_battery.png'):
        # Same Relay artwork already rasterized from the original Android
        # drawable XMLs by our local vector conversion script.
        src=SWITCH/name
        if not src.is_file():raise RuntimeError(f'Missing source-derived icon: {src}')
        shutil.copyfile(src,ASSETS/name)
        provenance[str(src.relative_to(ROOT))]=sha(src)
    font=ANDROID/'assets/fonts/inter-variable.ttf'
    shutil.copyfile(font,ASSETS/font.name)
    provenance[str(font.relative_to(ROOT))]=sha(font)
    for name in ('menu_back','menu_close','menu_confirm','menu_focus',
                 'menu_open','menu_reveal','update_complete','update_start'):
        src=ANDROID/'assets/portal_audio'/f'{name}.ogg'
        dest=ASSETS/f'{name}.wav'
        subprocess.run(['ffmpeg','-y','-hide_banner','-loglevel','error',
                        '-i',str(src),'-c:a','pcm_s16le',str(dest)],check=True)
        provenance[str(src.relative_to(ROOT))]=sha(src)
    music=ANDROID/'assets/portal_audio/portal_home_loop.pcm'
    with wave.open(str(ASSETS/'portal_home_loop.wav'),'wb') as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(48000)
        with music.open('rb') as f:
            while True:
                b=f.read(1024*1024)
                if not b:break
                if len(b)%4:raise RuntimeError('Truncated original stereo PCM')
                output.writeframesraw(b)
    provenance[str(music.relative_to(ROOT))]=sha(music)
    icon=ROOT/'docs/branding/relay-icon-original.png'
    source=Image.open(icon).convert('RGBA')
    sizes={'Square150x150Logo.png':(150,150),'Square44x44Logo.png':(44,44),
           'Wide310x150Logo.png':(310,150),'SplashScreen.png':(620,300)}
    for name,(w,h) in sizes.items():
        target=Image.new('RGBA',(w,h),(3,3,7,255))
        resized=source.copy()
        side=int(min(w,h)*.82)
        resized.thumbnail((side,side),Image.Resampling.LANCZOS)
        target.alpha_composite(resized,((w-resized.width)//2,(h-resized.height)//2))
        target.convert('RGB').save(ASSETS/name)
    provenance[str(icon.relative_to(ROOT))]=sha(icon)
    provenance[str(ANDROID.relative_to(ROOT))+'/java/com/metallic/chiaki/main/PortalBackdropView.kt']=sha(
        ANDROID/'java/com/metallic/chiaki/main/PortalBackdropView.kt')
    (BASE/'ASSET_PROVENANCE.json').write_text(json.dumps(provenance,indent=2,sort_keys=True)+'\n',encoding='utf8')
    result=particles()
    print(f'RELAY XBOX assets: {len(provenance)} source file hashes; {len(list(ASSETS.iterdir()))} packaged assets')
    print('Particle seed generated:',result,'bytes',result.stat().st_size)


if __name__=='__main__':prepare()

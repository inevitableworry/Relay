"""Prepare *unchanged-source* Relay artwork and a Vita-friendly radial shader atlas.

VitaSDK's native 2D engine cannot use Android Paint shaders, so their exact
colour stops are rasterised to transparent textures for the Vita's GXM GPU.
Original assets and SHA256 references remain untouched.
"""
import hashlib
import json
import math
import shutil
import subprocess
from pathlib import Path
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[3]
VITA=ROOT/'platforms/vita'
OUT=VITA/'assets'
NAV=ROOT/'platforms/switch1/romfs/relay/nav'
ORIGINAL=ROOT/'android/app/src/main'

def hash_file(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def ramp(stops,at):
    for i in range(len(stops)-1):
        start, a=stops[i]
        end, b=stops[i+1]
        if at<=end:
            t=max(0.,min(1.,(at-start)/(end-start)))
            return tuple(round(a[n]+(b[n]-a[n])*t) for n in range(4))
    return stops[-1][1]

def radial(out_path,size,stops,offset=(0,0)):
    im=Image.new('RGBA',(size,size))
    pix=im.load()
    for y in range(size):
        for x in range(size):
            dx=(2*(x+.5)/size-1)-offset[0]
            dy=(2*(y+.5)/size-1)-offset[1]
            d=min(1, math.hypot(dx,dy))
            pix[x,y]=ramp(stops,d)
    im.save(out_path,optimize=True)

def shade_orb():
    """Rasterize the material lighting Vita2D cannot express with a fragment shader.

    The rotating particle positions remain the original Android coordinates. This
    illumination is a static surface light with a soft edge, not an extra orb.
    """
    size=512
    shadow=Image.new('RGBA',(size,size))
    gloss=Image.new('RGBA',(size,size))
    a=shadow.load()
    b=gloss.load()
    for y in range(size):
        for x in range(size):
            nx=2*(x+.5)/size-1
            ny=2*(y+.5)/size-1
            r2=nx*nx+ny*ny
            if r2>=1:
                continue
            nz=math.sqrt(1-r2)
            illumination=max(0,nx*(-.48)+ny*(-.54)+nz*.72)
            edge=min(1.,(1.-math.sqrt(r2))*38.)
            ambient=.11+.60*illumination
            shade=max(0.,1.-ambient)
            a[x,y]=(6,3,27,round(min(145.,shade*155.)*edge))
            highlight=math.exp(-((nx+.37)**2/.23+(ny+.45)**2/.20))
            fresnel=(1-nz)**2.8
            edgeLight=max(0.,(-nx*.49-ny*.80))*fresnel
            blue=max(0.,min(1.,highlight*.51+edgeLight*.63))
            b[x,y]=(125,118,255,round(105*blue*edge))
    shadow.save(OUT/'sphere_shadow.png',optimize=True)
    gloss.save(OUT/'sphere_gloss.png',optimize=True)

def glass_panel(path,w,h,fill=(17,21,42),radius=20,border=(188,179,255,56),
                shadow=False):
    im=Image.new('RGBA',(w,h))
    pixels=im.load()
    for y in range(h):
        for x in range(w):
            grad=.12*(1-y/max(1,h-1))+.055*(1-x/max(1,w-1))
            pixels[x,y]=tuple(min(255,round(c*(1+grad))) for c in fill)+(244,)
    mask=Image.new('L',(w,h),0)
    draw=ImageDraw.Draw(mask)
    draw.rounded_rectangle((1,1,w-2,h-2),radius=radius,fill=255)
    im.putalpha(Image.frombytes('L',(w,h),bytes(mask.tobytes())))
    d=ImageDraw.Draw(im)
    d.rounded_rectangle((1,1,w-2,h-2),radius=radius,outline=border,width=2)
    im.save(path,optimize=True)

def style_ui():
    # Menu surfaces have one retained Vita2D textured draw each.
    glass_panel(OUT/'quick_panel.png',328,544,(24,26,49),0,(72,68,120,65))
    glass_panel(OUT/'dialog_panel.png',785,232,(25,29,59),23,(173,164,240,82))
    glass_panel(OUT/'focus_row.png',280,47,(76,70,129),12,(214,208,255,123))
    w,h=180,52
    im=Image.new('RGBA',(w,h),(0,0,0,0))
    mask=Image.new('L',(w,h),0)
    ImageDraw.Draw(mask).rounded_rectangle((1,1,w-2,h-2),radius=12,fill=255)
    pix=im.load()
    for y in range(h):
        t=y/(h-1)
        for x in range(w):
            pix[x,y]=(round(250-18*t),round(251-19*t),255,255)
    im.putalpha(mask)
    ImageDraw.Draw(im).rounded_rectangle((1,1,w-2,h-2),radius=12,
                                          outline=(255,255,255,255),width=2)
    im.save(OUT/'connect_button.png',optimize=True)

def prepare_pcm():
    ffmpeg=shutil.which('ffmpeg')
    if not ffmpeg:
        raise RuntimeError('ffmpeg is required to convert menu OGG files to Vita PCM')
    for name in ['menu_back','menu_close','menu_confirm','menu_focus',
                 'menu_open','menu_reveal']:
        subprocess.run([ffmpeg,'-nostdin','-loglevel','error','-y',
                       '-i',str(OUT/f'{name}.ogg'),'-ac','2','-ar','48000',
                       '-f','s16le',str(OUT/f'{name}.pcm')],check=True)

def save_header(points,clusters):
    out=VITA/'generated/relay_particles.h'
    out.parent.mkdir(parents=True,exist_ok=True)
    with out.open('w',encoding='ascii') as f:
        f.write('/* Exact original Relay Android Java RNG particle coordinates. */\n')
        f.write('#ifndef RELAY_VITA_PARTICLES_H\n#define RELAY_VITA_PARTICLES_H\n')
        f.write('#define RELAY_PARTICLE_COUNT 2600\n')
        f.write('static const float relay_clusters[19][4]={\n')
        for c in clusters:f.write('{'+','.join(repr(float(v))+'f' for v in c)+'},\n')
        f.write('};\n')
        f.write('static const float relay_particle_data[2600][5]={\n')
        for p in points:
            f.write('{'+','.join(repr(float(v))+'f' for v in p)+'},\n')
        f.write('};\n#endif\n')
    return out

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    provenance={}
    for name in ['portal_nav_remote.png','portal_nav_cloud.png','portal_nav_search.png',
                 'portal_status_wifi.png','portal_status_battery.png',
                 'portal_status_sliders.png']:
        src=NAV/name
        if not src.is_file():raise FileNotFoundError(src)
        (OUT/name).write_bytes(src.read_bytes())
        provenance[str(src.relative_to(ROOT))]=hash_file(src)
    src=ORIGINAL/'assets/fonts/inter-variable.ttf'
    (OUT/'inter-variable.ttf').write_bytes(src.read_bytes())
    provenance[str(src.relative_to(ROOT))]=hash_file(src)
    for name in ['menu_back','menu_close','menu_confirm','menu_focus',
                 'menu_open','menu_reveal']:
        src=ORIGINAL/'assets/portal_audio'/f'{name}.ogg'
        (OUT/src.name).write_bytes(src.read_bytes())
        provenance[str(src.relative_to(ROOT))]=hash_file(src)
    radial(OUT/'sphere.png',512,[(0,(23,14,62,255)),(.72,(22,14,56,255)),
                                    (.968,(27,18,72,255)),(1,(27,18,72,0))])
    radial(OUT/'halo.png',512,[(0,(46,26,145,0)),(.49,(51,35,186,116)),
                                  (.70,(39,35,103,74)),(1,(0,0,0,0))])
    radial(OUT/'cloud.png',128,[(0,(60,32,255,101)),(.36,(36,11,232,41)),(1,(0,0,0,0))])
    radial(OUT/'dot.png',64,[(0,(236,240,255,255)),(.08,(191,202,255,255)),
                                (.19,(102,117,255,255)),(.38,(47,37,255,128)),(1,(0,0,0,0))])
    radial(OUT/'floor.png',256,[(0,(77,78,170,52)),(1,(77,78,170,0))])
    shade_orb()
    style_ui()
    prepare_pcm()
    bg=Image.new('RGBA',(4,544))
    px=bg.load()
    for y in range(544):
        t=y/544
        if t<=.67:u=t/.67;a=(3,3,7);b=(26,26,34)
        else:u=(t-.67)/.33;a=(26,26,34);b=(35,35,43)
        color=tuple(round(a[i]+(b[i]-a[i])*u) for i in range(3))+(255,)
        for x in range(4):px[x,y]=color
    bg.save(OUT/'background.png',optimize=True)
    src=ROOT/'platforms/xbox-series/js/particles.js'
    model=json.loads(src.read_text(encoding='utf8').split('window.RELAY_PARTICLES=',1)[1].strip().rstrip(';'))
    assert len(model['points'])==2600
    save_header(model['points'],model['clusters'])
    android_source=ORIGINAL/'java/com/metallic/chiaki/main/PortalBackdropView.kt'
    provenance[str(android_source.relative_to(ROOT))]=hash_file(android_source)
    (VITA/'ASSET_PROVENANCE.json').write_text(json.dumps(provenance,indent=2,sort_keys=True)+'\n')
    print('Relay Vita source assets',len(provenance),'particle count',len(model['points']))

if __name__=='__main__':main()

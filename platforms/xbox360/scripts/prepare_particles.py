"""Convert the already-verified Relay Android particle distribution to C data."""
from pathlib import Path
import json

root=Path(__file__).resolve().parents[3]
original=root/'platforms/xbox-series/js/particles.js'
source=original.read_text(encoding='utf-8')
marker='window.RELAY_PARTICLES='
assert marker in source
model=json.loads(source.split(marker,1)[1].strip().rstrip(';'))
points=model['points']
assert len(points)==2600
out=Path(__file__).resolve().parents[1]/'generated/relay_particles.h'
out.parent.mkdir(parents=True,exist_ok=True)
with out.open('w',encoding='ascii') as f:
    f.write('// Original Relay PortalBackdropView seeded particle positions.\n')
    f.write('#ifndef RELAY_XBOX360_PARTICLES_H\n#define RELAY_XBOX360_PARTICLES_H\n')
    f.write('#define RELAY_POINTS 2600\nstatic const float relay_particles[RELAY_POINTS][5] = {\n')
    for point in points:
        f.write('{'+','.join(repr(float(v))+'f' for v in point)+'},\n')
    f.write('};\n#endif\n')
print('Prepared native Relay Xbox 360 particle data',out,'points',len(points))

if __name__=='__main__':
    prepare=None
    root=Path(__file__).resolve().parents[3]
    source=(root/'platforms/xbox-series/js/particles.js').read_text(encoding='utf-8')
    data=json.loads(source.split('window.RELAY_PARTICLES=',1)[1].strip().rstrip(';'))
    points=data['points']
    assert len(points)==2600
    out=Path(__file__).resolve().parents[1]/'generated/relay_particles.h'
    out.parent.mkdir(parents=True,exist_ok=True)
    with out.open('w',encoding='ascii') as f:
        f.write('// Original Android Relay particle distribution.\n#ifndef RELAY_XBOX360_PARTICLES_H\n#define RELAY_XBOX360_PARTICLES_H\n#define RELAY_POINTS 2600\nstatic const float relay_particles[RELAY_POINTS][5]={\n')
        for p in points:f.write('{'+','.join(repr(float(v))+'f' for v in p)+'},\n')
        f.write('};\n#endif\n')
    print('Generated',out,len(points))

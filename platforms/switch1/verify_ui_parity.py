from pathlib import Path
import hashlib,json,sys
root=Path(__file__).resolve().parents[2]
manifest=json.loads((Path(__file__).parent/'ui_audio_animation_lock.json').read_text(encoding='utf-8'))
wrong=[]
for name,hash_ in manifest['files'].items():
    src=root/name
    actual=hashlib.sha256(src.read_bytes()).hexdigest() if src.is_file() else 'MISSING'
    if actual!=hash_:
        wrong.append(name)
print(f'Locked Relay UI/audio/animation references: {len(manifest["files"])}; changed/missing: {len(wrong)}')
for name in wrong:print('CHANGED OR MISSING:',name)
sys.exit(1 if wrong else 0)

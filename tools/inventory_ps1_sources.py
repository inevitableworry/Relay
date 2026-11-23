from pathlib import Path
import os,json
root=Path(r'H:\Samsung Android\PSPortalAndroid')
roots=['scripts','tools','services','platforms','android','cli']
skip={'.git','.tools','node_modules','third-party','external','.gradle','.cxx','build','dist','target','bin','obj','research','vendor','upstream','nca-title-output','__pycache__','.dart_tool'}
names=[]
for rel in roots:
 p=root/rel
 if not p.exists():continue
 for base,dirs,files in os.walk(p,followlinks=False):
  folder=Path(base)
  dirs[:]=[d for d in dirs if d not in skip and not (folder/d).is_symlink() and not getattr(folder/d,'is_junction',lambda:False)()]
  for name in files:
   if name.lower().endswith('.ps1'):names.append(str(folder/name))
out=root/'docs/RELAY_LOCAL_PS1_SCAN_LIST.json'
out.write_text(json.dumps(names,indent=2)+'\n',encoding='utf8')
print('PowerShell source files',len(names),out)

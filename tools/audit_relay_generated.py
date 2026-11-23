from pathlib import Path
import os,collections,json,re
ROOT=Path(r'H:\Samsung Android\PSPortalAndroid')
SKIP={'.git','node_modules','.gradle','.idea','.vscode','__pycache__','.venv','.next','.cache','.dart_tool'}
records=[]
for base,dirs,files in os.walk(ROOT):
 p=Path(base);rel=p.relative_to(ROOT);depth=len(rel.parts)
 if depth>7:
  dirs[:]=[];continue
 if any(item in SKIP for item in rel.parts):
  dirs[:]=[];continue
 for d in dirs:
  dr=Path(base)/d
  if d.lower() in ('build','dist','debug','release','releases','outputs','bin','obj','target','cache','artifacts','intermediates','organized','local-development') or d.lower().startswith('cmake-build'):
   rec={'path':str(dr),'rel':str(dr.relative_to(ROOT)),'depth':depth+1,'category':d}
   total=0;count=0;big=[]
   for pb,subdirs,fs in os.walk(dr):
    subdirs[:]=[x for x in subdirs if x not in SKIP]
    for file in fs:
     fp=os.path.join(pb,file)
     try:size=os.stat(fp).st_size
     except OSError:continue
     total+=size;count+=1
     if size>10000000:big.append([str(Path(fp).relative_to(ROOT)),size])
   rec.update(bytes=total,files=count,biggest=sorted(big,key=lambda e:e[1],reverse=True)[:4])
   records.append(rec)
 # don't omit build dirs from scan, but don't chase .git
 dirs[:]=[x for x in dirs if x not in SKIP]
print('CANDIDATE GENERATED DIRECTORIES',len(records))
for x in sorted(records,key=lambda x:x['bytes'],reverse=True)[:85]:
 print(f'{x["bytes"]/1048576:9.1f} MiB {x["files"]:6d} files {x["rel"]}')
print('TOTAL CANDIDATE SIZE GiB',round(sum(x['bytes'] for x in records)/1073741824,2),'nested double counts possible')
for root in [Path(r'H:\Relay Handheld Builds'),Path(r'H:\Relay Xbox Series Dev Builds'),Path(r'H:\Relay Social\public')]:
 total=count=0
 extensions=collections.Counter()
 big=[]
 for p in root.rglob('*'):
  if not p.is_file():continue
  if 'node_modules' in p.parts:continue
  size=p.stat().st_size
  total+=size;count+=1;extensions[p.suffix.lower()]+=1
  if size>10000000:big.append((p.name,size))
 print('OTHER',str(root),'files',count,'MiB',round(total/1048576,1),'types',extensions.most_common(9),'large',sorted(big,key=lambda x:x[1],reverse=True)[:7])
out=Path(r'H:\Samsung Android\PSPortalAndroid\docs\GITHUB_PREP_ARTIFACT_INVENTORY.json')
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(records,indent=2),encoding='utf8')
print('INVENTORY',out)

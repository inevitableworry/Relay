"""Review exactly the files Git would consider for the next commit.

This is read-only and does not stage, alter history, push or print credentials.
"""
from pathlib import Path
import subprocess,json,collections
root=Path(r'H:\Samsung Android\PSPortalAndroid')
def paths(*args):
 result=subprocess.run(['git','-C',str(root),*args],capture_output=True,timeout=150)
 if result.returncode!=0:raise RuntimeError(result.stderr.decode('utf8','replace')[:900])
 return [p for p in result.stdout.decode('utf8','replace').split('\0') if p]
untracked=paths('ls-files','--others','--exclude-standard','-z')
tracked=paths('ls-files','--cached','-z')
def investigate(names):
 rows=[]
 for n in names:
  p=root/n
  if not p.is_file():continue
  try:size=p.stat().st_size
  except FileNotFoundError:continue
  rows.append({'path':n.replace('\\','/'),'size':size,'ext':p.suffix.lower()})
 return rows
u=investigate(untracked);t=investigate(tracked)
u_big=sorted(u,key=lambda v:v['size'],reverse=True)
t_big=sorted(t,key=lambda v:v['size'],reverse=True)
suspicious_ext={'.apk','.aab','.exe','.elf','.nsp','.nro','.vpk','.ipa','.appx','.msix','.wgt','.xex','.p12','.pfx','.jks','.keystore','.sqlite','.db','.key','.pem','.zip','.tar','.gz','.7z','.rar','.bin'}
suspicious=[x for x in u if x['ext'] in suspicious_ext]
data={
 'note':'Only files Git would consider: untracked ignoring .gitignore, plus tracked; DOES NOT inspect production Social.',
 'untracked_count':len(u),'tracked_count':len(t),
 'untracked_over_90MiB':[x for x in u_big if x['size']>=90*1024*1024],
 'untracked_over_25MiB':[x for x in u_big if x['size']>=25*1024*1024][:80],
 'largest_untracked':u_big[:55],
 'largest_tracked':t_big[:30],
 'untracked_binary_or_sensitive_extensions':sorted(suspicious,key=lambda v:v['size'],reverse=True)[:140],
 'ext_counts_untracked':collections.Counter(x['ext'] for x in u).most_common(32)
}
out=root/'docs/GITHUB_PREP_GIT_RISKS.json'
out.write_text(json.dumps(data,indent=2)+'\n',encoding='utf8')
print('GIT READINESS',len(u),'untracked files',len(t),'tracked files',flush=True)
print('UNTRACKED >=90MiB',len(data['untracked_over_90MiB']))
for x in data['untracked_over_25MiB'][:35]:
 print('LARGE',round(x['size']/1048576,1),'MiB',x['path'])
print('BINARY/SENSITIVE EXT CANDIDATES',len(suspicious))
for x in sorted(suspicious,key=lambda v:v['size'],reverse=True)[:28]:
 print('CANDIDATE',round(x['size']/1048576,2),'MiB',x['path'])
print('REPORT',out)

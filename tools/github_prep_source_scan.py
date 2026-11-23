"""Relay GitHub readiness: read-only first-party source scan.
Never prints credential values. Treat findings as possible leaks to review, not
proof of active valid tokens. Excludes all build caches and third-party clones.
Does NOT scan or modify H:\\Relay Social production.
"""
from pathlib import Path
import json,os,re,collections
ROOT=Path(r'H:\Samsung Android\PSPortalAndroid')
TARGETS=[ROOT,ROOT/'services/relay-social']
SKIP_DIR={'.git','.svn','.hg','node_modules','.gradle','.cxx','.tools','.idea',
 'build','dist','releases','debug','release','target','bin','obj',
 '__pycache__','.cache','.venv','venv','output','outputs','intermediates',
 'external','third-party','research','upstream','sdk-cache','.dart_tool',
 'nca-title-output','title-stage','exefs-dev','archived-experiments'}
SCAN_EXT={'.md','.txt','.json','.json5','.py','.ps1','.sh','.mjs','.js','.ts',
 '.tsx','.java','.kt','.kts','.xml','.html','.css','.gradle','.toml','.yml',
 '.yaml','.cmake','.c','.cc','.cpp','.h','.hpp','.cs','.swift','.rs','.properties',
 '.svelte','.gitignore','.env.example','.dockerignore','.bat','.cmd'}
HIGH=[
 ("private-key",re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----")),
 ("github-token",re.compile(r"(?:gh[pousr]_[A-Za-z0-9_]{25,}|github_pat_[A-Za-z0-9_]{35,})")),
 ("openai-token",re.compile(r"sk-(?:proj-)?[A-Za-z0-9_-]{28,}")),
 ("aws-access-key",re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
 ("google-api-key",re.compile(r"\bAIza[A-Za-z0-9_-]{32,}\b")),
 ("cloudflare-token",re.compile(r"\beyJ[A-Za-z0-9_-]{100,}\.[A-Za-z0-9_-]{30,}\.[A-Za-z0-9_-]{30,}\b"))
]
SENSITIVE_NAME=re.compile(r'(?i)(?:^\.env$|\.env\.(?!example$)|\.pfx$|\.p12$|\.key$|\.pem$|\.jks$|\.keystore$|password|secret|token|credential|id_rsa|social\.sqlite|\.db$|\.sqlite$|\.bak$)')
FIRST_PARTY=['android','platforms','services','scripts','tools','lib','gui','cli','steamdeck_native','docs','.github','assets','switch','setsu']
def scan():
 findings=[]
 counters=collections.Counter()
 scanned=0
 for root in TARGETS[:1]:
  for base,dirs,files in os.walk(root):
   folder=Path(base);rel=folder.relative_to(root)
   if any(v in SKIP_DIR for v in rel.parts):dirs[:]=[];continue
   dirs[:]=[x for x in dirs if x not in SKIP_DIR and not x.startswith('cmake-build')
            and not (folder/x).is_symlink()
            and not getattr(folder/x,'is_junction',lambda:False)()]
   for name in files:
    p=folder/name
    if p.is_symlink() or getattr(p,'is_junction',lambda:False)():continue
    relname=str(p.relative_to(root)).replace('\\','/')
    if SENSITIVE_NAME.search(name):
     findings.append({'path':relname,'type':'sensitive-filename','severity':'review'})
    if p.suffix.lower() not in SCAN_EXT and name not in ['CMakeLists.txt','Makefile','Dockerfile','Gemfile']:
     counters['binary-or-other-files']+=1;continue
    if p.stat().st_size>1000000:counters['oversize-skipped']+=1;continue
    try:s=p.read_text(encoding='utf-8',errors='replace')
    except OSError:continue
    scanned+=1;counters['text-files']+=1
    for label,pattern in HIGH:
     matches=pattern.findall(s)
     if matches:
      findings.append({'path':relname,'type':label,'severity':'high','count':len(matches)})
  # Explicitly check nested Relay Social development env name, never values.
 dev=ROOT/'services/relay-social'
 for path in ['.env','data/social.sqlite','data/.master-key','media']:
  if (dev/path).exists():
   findings.append({'path':str((dev/path).relative_to(ROOT)).replace('\\','/'),'type':'exclude-from-repo','severity':'review'})
 out=ROOT/'docs/GITHUB_PREP_SECURITY_SCAN.json'
 out.write_text(json.dumps({'purpose':'Read-only source scan; no values included','scanned_text_files':scanned,
   'stats':dict(counters),'findings':findings},indent=2),encoding='utf-8')
 print('READ-ONLY SCAN:',scanned,'text files; flags',len(findings),
       'high-confidence',sum(f['severity']=='high' for f in findings))
 for f in findings[:35]:
  print('REVIEW',f['severity'],f['type'],f['path'])
 print('FULL REPORT',out)
if __name__=='__main__':scan()

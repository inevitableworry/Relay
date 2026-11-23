"""Parse first-party Relay Python and PowerShell sources without builds.
PowerShell syntax checking is performed independently in a second command.
"""
from pathlib import Path
import ast,json,os
root=Path(r'H:\Samsung Android\PSPortalAndroid')
roots=['scripts','tools','services','platforms','android','cli','lib']
skip={'.git','.tools','node_modules','third-party','external','.gradle','.cxx','build','dist','target','bin','obj','research','vendor','upstream','nca-title-output','__pycache__','.dart_tool'}
checked=[];bad=[]
for rel in roots:
 path=root/rel
 if not path.exists():continue
 for base,dirs,files in os.walk(path,followlinks=False):
  folder=Path(base)
  dirs[:]=[d for d in dirs if d not in skip and not (folder/d).is_symlink() and not getattr(folder/d,'is_junction',lambda:False)()]
  for name in files:
   if not name.endswith('.py'):continue
   src=folder/name
   if src.is_symlink():continue
   try:
    ast.parse(src.read_text(encoding='utf-8-sig'),filename=str(src))
    checked.append(str(src.relative_to(root)))
   except (SyntaxError,UnicodeError) as e:
    bad.append({'file':str(src.relative_to(root)),'error':f'{type(e).__name__} at {getattr(e,"lineno","")}'})
out=root/'docs/RELAY_PYTHON_SOURCE_SYNTAX.json'
out.write_text(json.dumps({'checked':len(checked),'errors':bad},indent=2)+'\n',encoding='utf8')
print('FIRST-PARTY PYTHON SYNTAX',len(checked),'checked',len(bad),'errors')
for x in bad[:30]:print('ERROR',x['file'],x['error'])

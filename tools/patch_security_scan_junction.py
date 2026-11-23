from pathlib import Path
p=Path(r'H:\Samsung Android\PSPortalAndroid\tools\github_prep_source_scan.py')
s=p.read_text(encoding='utf8')
old="""   dirs[:]=[x for x in dirs if x not in SKIP_DIR and not x.startswith('cmake-build')]
   for name in files:"""
new="""   dirs[:]=[x for x in dirs if x not in SKIP_DIR and not x.startswith('cmake-build')
            and not (folder/x).is_symlink()
            and not getattr(folder/x,'is_junction',lambda:False)()]
   for name in files:"""
assert s.count(old)==1
s=s.replace(old,new)
old="""    p=folder/name
    relname=str(p.relative_to(root)).replace('\\\\','/')"""
new="""    p=folder/name
    if p.is_symlink() or getattr(p,'is_junction',lambda:False)():continue
    relname=str(p.relative_to(root)).replace('\\\\','/')"""
assert s.count(old)==1
s=s.replace(old,new)
p.write_text(s,encoding='utf8')
print('Fixed security scanner: never follow NTFS reparse points or relocated cloned repos.')

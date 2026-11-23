from pathlib import Path
p=Path(r'H:\Samsung Android\PSPortalAndroid\tools\clean_relay_generated_artifacts.py')
s=p.read_text(encoding='utf8')
old="""def folder_data(p):
 total=count=0
 binaries=[]
 for base,dirs,files in os.walk(p,followlinks=False):
  folder=Path(base)
  for d in list(dirs):
   sub=folder/d
   if sub.is_symlink() or getattr(sub,'is_junction',lambda:False)():
    raise RuntimeError('REFUSE DIRECTORY WITH REPARSE CONTENT: '+str(sub))
  for filename in files:
   path=folder/filename
   if path.is_symlink():raise RuntimeError('REFUSE FILE SYMLINK IN BUILD OUTPUT: '+str(path))
   try:size=path.stat().st_size
   except FileNotFoundError:continue
   total+=size;count+=1
   if path.suffix.lower() in PACKAGES:
    binaries.append({'path':str(path),'bytes':size,'type':path.suffix.lower()})
 return {'bytes':total,'files':count,'packages':binaries}
"""
new="""def folder_data(p):
 total=count=0
 binaries=[]
 links=[]
 for base,dirs,files in os.walk(p,followlinks=False):
  folder=Path(base)
  for d in list(dirs):
   sub=folder/d
   if sub.is_symlink() or getattr(sub,'is_junction',lambda:False)():
    # Never follow external dependency symlinks. Save path to detach the LINK
    # itself, leaving the original target unchanged.
    links.append(str(sub))
    dirs.remove(d)
  for filename in files:
   path=folder/filename
   if path.is_symlink() or getattr(path,'is_junction',lambda:False)():
    links.append(str(path))
    continue
   try:size=path.stat().st_size
   except FileNotFoundError:continue
   total+=size;count+=1
   if path.suffix.lower() in PACKAGES:
    binaries.append({'path':str(path),'bytes':size,'type':path.suffix.lower()})
 return {'bytes':total,'files':count,'packages':binaries,'links':links}
"""
assert s.count(old)==1,'missing folder_data'
s=s.replace(old,new)
old="folders.append({'path':str(p),'relative':name,'files':x['files'],'bytes':x['bytes']})"
new="folders.append({'path':str(p),'relative':name,'files':x['files'],'bytes':x['bytes'],'reparse_links':x['links']})"
assert s.count(old)==1
s=s.replace(old,new)
old="""  print('REMOVE FOLDER',d['relative'],'files',d['files'],'MiB',round(d['bytes']/1048576,1),flush=True)
  shutil.rmtree(target)"""
new="""  print('REMOVE FOLDER',d['relative'],'files',d['files'],'MiB',round(d['bytes']/1048576,1),'links',len(d['reparse_links']),flush=True)
  for linktext in sorted(d['reparse_links'],key=lambda t:len(Path(t).parts),reverse=True):
   link=Path(linktext)
   # Validate the PARENT only: the link intentionally points outside build.
   ensure_safe(link.parent)
   if getattr(link,'is_junction',lambda:False)():
    os.rmdir(link)   # Windows rmdir removes only the junction.
   elif link.is_symlink():
    try:os.unlink(link)
    except OSError:
     if link.is_dir():os.rmdir(link)
     else:raise
   elif link.exists():
    raise RuntimeError('Reparse point changed during cleanup; refuse: '+str(link))
  shutil.rmtree(target)"""
assert s.count(old)==1,'missing execute section'
s=s.replace(old,new)
p.write_text(s,encoding='utf8')
print('Modified cleanup safety: reparse links are listed in manifest and unlinked without touching targets.')

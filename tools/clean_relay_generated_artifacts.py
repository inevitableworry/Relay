"""Remove only verified Relay generated debug/release artifacts.

Safety:
- Canonical Git root and two explicitly authorized external package folders only
- No reparse-point/junction traversal
- Never touch H:\\Relay Social or live service state
- Preserve source, QA images, QA logs, signed identity/config, license, SDKs
- Save manifest before deleting anything
"""
from pathlib import Path
import os,sys,json,shutil,hashlib
ROOT=Path(r'H:\Samsung Android\PSPortalAndroid')
DOCS=ROOT/'docs'
OUTSIDES=[Path(r'H:\Relay Handheld Builds'),Path(r'H:\Relay Xbox Series Dev Builds')]
EXECUTE='--execute' in sys.argv
DIRS=[
 'android/app/build','android/app/.cxx','android/build',
 'services/relay-social/android-native/app/build',
 'services/relay-social/android-native/build',
 'releases',
 'platforms/windows11/build','platforms/windows11/dist',
 'platforms/windows11/bin','platforms/windows11/obj',
 'platforms/steamdeck/dist',
 'platforms/xbox-series/build','platforms/xbox-series/dist',
 'platforms/ps5-social-link/dist',
 'platforms/apple/build','platforms/apple/dist',
 'platforms/3ds/build','platforms/3ds/dist',
 'platforms/switch1/title-stage','platforms/switch1/exefs-dev',
 'platforms/switch1/nca-title-output/nca',
 'platforms/samsung-tizen/.buildResult','platforms/samsung-tizen/.wgt-input'
]
FILE_TARGETS=[
 'platforms/samsung-tizen/Relay.wgt',
 'platforms/samsung-tizen/.manifest.tmp',
 'platforms/roku/Relay-Roku-UI-preview.zip'
]
for stem in ['relay-switch1','relay-switch1-exefs-dev']:
 for ext in ['.nro','.elf','.nsp','.nacp']:
  FILE_TARGETS.append('platforms/switch1/'+stem+ext)
for ext in ['.3dsx','.elf','.smdh','.cia','.cci']:
 FILE_TARGETS.append('platforms/3ds/relay_3ds'+ext)
PACKAGES={'.apk','.aab','.appx','.msix','.vpk','.nsp','.nro','.3dsx','.cia','.cci','.ipa','.wgt','.xex','.pkg','.elf','.zip','.exe'}
SWITCH_BINARY={'.nsp','.nca','.nacp','.npdm','.bin','.elf','.nro','.cci','.cia'}
SWITCH_BUILD_EXT={'.o','.d','.lst','.map','.elf','.nro','.nsp','.a','.so'}
FOLDERS=[
 ('platforms/switch1/nca-title-output',SWITCH_BINARY),
 ('platforms/switch1/build',SWITCH_BUILD_EXT),
 ('platforms/3ds/archived-experiments',{'.cci','.cia','.3dsx','.elf'}),
 ('platforms/vita/dist',{'.vpk','.elf','.self','.pkg'})
]
root_string=str(ROOT).rstrip('\\').casefold()
def ensure_safe(p):
 p=Path(os.path.abspath(str(p)))
 raw=str(p).casefold()
 permitted=raw.startswith(root_string+'\\')
 if not permitted:
  # Flat artifact folders only, NEVER root directory itself.
  permitted=any(raw.startswith(str(out).casefold().rstrip('\\')+'\\') for out in OUTSIDES)
 if not permitted:raise RuntimeError('UNAUTHORIZED DELETE PATH: '+str(p))
 for par in [p,*p.parents]:
  if par==ROOT or par in OUTSIDES:break
  if getattr(par,'is_junction',lambda:False)() or par.is_symlink():
   raise RuntimeError('REFUSE TARGET THROUGH JUNCTION/SYMLINK: '+str(par))
 return p
def folder_data(p):
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
def enumerate_deletions():
 folders=[]
 allfiles=[]
 packages=[]
 for name in DIRS:
  p=ensure_safe(ROOT/Path(name))
  if not p.exists():continue
  if not p.is_dir():raise RuntimeError('Expected build directory: '+str(p))
  x=folder_data(p)
  folders.append({'path':str(p),'relative':name,'files':x['files'],'bytes':x['bytes'],'reparse_links':x['links']})
  packages.extend(x['packages'])
 for name in FILE_TARGETS:
  p=ensure_safe(ROOT/Path(name))
  if p.is_file():allfiles.append({'path':str(p),'relative':name,'bytes':p.stat().st_size,'type':p.suffix.lower()})
 for name,extensions in FOLDERS:
  parent=ROOT/Path(name)
  if not parent.is_dir():continue
  # Package removal only: preserve screenshots, logs, config backups and tests.
  for p in parent.rglob('*'):
   if not p.is_file() or p.suffix.lower() not in extensions:continue
   p=ensure_safe(p)
   allfiles.append({'path':str(p),'relative':str(p.relative_to(ROOT)),'bytes':p.stat().st_size,'type':p.suffix.lower()})
 for p in OUTSIDES:
  if not p.exists():continue
  extension={'.apk'} if p.name=='Relay Handheld Builds' else {'.appx'}
  for child in p.iterdir():
   if child.is_file() and child.suffix.lower() in extension:
    child=ensure_safe(child)
    allfiles.append({'path':str(child),'relative':'EXTERNAL:'+child.name,'bytes':child.stat().st_size,'type':child.suffix.lower()})
 packages.extend(x for x in allfiles if x.get('type') in PACKAGES)
 return folders,allfiles,packages
folders,files,packages=enumerate_deletions()
record={
 'operation':'Remove generated debug/release artifacts only',
 'live_social_site_protected':'H:\\Relay Social (not inspected or modified by deletion)',
 'update_service_state_protected':str(ROOT/'services/relay-update/state'),
 'excluded_protected':'social site static downloads, node_modules, uploads, database, credentials, tests, screenshots and logs',
 'dirs':folders,'individual_files':files,
 'package_list':packages,
 'byte_total':sum(d['bytes'] for d in folders)+sum(f['bytes'] for f in files),
 'count_dirs':len(folders),
 'count_individual_files':len(files),
 'mode':'execute' if EXECUTE else 'dry-run'
}
if EXECUTE:
 # Preserve prior generated index for traceability.
 source_index=ROOT/'releases/organized/artifact-index.json'
 if source_index.exists():
  shutil.copy2(source_index,DOCS/'RELAY_PRE_CLEANUP_ARTIFACT_INDEX.json')
 # Write manifest before deleting so no packages are lost from audit trail.
 (DOCS/'RELAY_DEBUG_RELEASE_CLEANUP_MANIFEST.json').write_text(
  json.dumps(record,indent=2)+'\n',encoding='utf8')
 for d in folders:
  target=ensure_safe(Path(d['path']))
  if not target.exists():continue
  print('REMOVE FOLDER',d['relative'],'files',d['files'],'MiB',round(d['bytes']/1048576,1),'links',len(d['reparse_links']),flush=True)
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
  shutil.rmtree(target)
 for f in files:
  target=ensure_safe(Path(f['path']))
  if target.exists():
   target.unlink()
   print('REMOVE FILE',f['relative'],round(f['bytes']/1048576,2),'MiB',flush=True)
 # Record actual remaining paths for audit.
 leftovers=[d['path'] for d in folders if Path(d['path']).exists()]
 leftovers.extend(f['path'] for f in files if Path(f['path']).exists())
 receipt={'timestamp':'2026-11-23','mode':'execute','deleted_dirs':len(folders),'deleted_files':len(files),
          'bytes_selected':record['byte_total'],'remaining_targets':leftovers,
          'protected_live_social':'unchanged; no files under H:\\Relay Social modified'}
 (DOCS/'RELAY_DEBUG_RELEASE_CLEANUP_RECEIPT.json').write_text(
  json.dumps(receipt,indent=2)+'\n',encoding='utf8')
 print('CLEANUP RECEIPT',json.dumps(receipt),flush=True)
else:
 (DOCS/'RELAY_DEBUG_RELEASE_CLEANUP_DRYRUN.json').write_text(
  json.dumps(record,indent=2)+'\n',encoding='utf8')
 print('DRY RUN: dirs',len(folders),'single files',len(files),'packages',len(packages),'GiB',
  round(record['byte_total']/1073741824,3),flush=True)
 for d in sorted(folders,key=lambda v:v['bytes'],reverse=True):
  print('DIR',d['relative'],'count',d['files'],'MiB',round(d['bytes']/1048576,1))
 for f in sorted(files,key=lambda v:v['bytes'],reverse=True)[:40]:
  print('FILE',f['relative'],'MiB',round(f['bytes']/1048576,2))

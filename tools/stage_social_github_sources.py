"""Prepare a source-only Relay Social GitHub staging tree without modifying
its live production directory or any runtime state. Always copy to a
separate path outside the existing Relay Git working tree.
"""
from pathlib import Path
import hashlib,shutil,json,os
live=Path(r'H:\Relay Social')
dev=Path(r'H:\Samsung Android\PSPortalAndroid\services\relay-social')
dest=Path(r'H:\Relay GitHub Staging\Relay-Social')
assert live.is_dir() and (live/'server.mjs').is_file()
assert not dest.is_relative_to(live) and not dest.is_relative_to(dev)
if dest.exists():raise RuntimeError('Refusing to overwrite existing Relay Social staging directory')
skip_dirs={'data','media','node_modules','.git','.gradle','build','dist','coverage','logs','downloads','.cache','intermediates','outputs','tmp'}
skip_files={'.env','.master-key','secrets.json','credentials.json'}
copied=[]
def copy_tree(src,target,base_rel):
 for parent,dirs,files in os.walk(src):
  parent=Path(parent)
  dirs[:]=[d for d in dirs if d not in skip_dirs and not d.lower().startswith('build-')]
  relative=parent.relative_to(src)
  (target/relative).mkdir(parents=True,exist_ok=True)
  for filename in files:
   if filename in skip_files or filename.lower().endswith(('.apk','.aab','.exe','.appx','.ipa','.zip','.vpk','.nsp','.msix','.jks','.keystore','.p12','.pfx','.sqlite','.db')):continue
   path=parent/filename
   if path.is_symlink():continue
   output=target/relative/filename
   shutil.copy2(path,output)
   copied.append((str(Path(base_rel)/relative/filename).replace('\\','/'),path,output))
# Read original current production JS/site assets, but not users' runtime data.
dest.mkdir(parents=True)
for filename in ['server.mjs','database.mjs','security.mjs','media-storage.mjs',
                 'package.json','package-lock.json','README.md','API.md',
                 'site features.md','projects.md','.env.example','.gitignore']:
 src=live/filename
 if src.is_file():
  shutil.copy2(src,dest/filename)
  copied.append((filename,src,dest/filename))
for name in ['public','test','tools']:
 if (live/name).is_dir():copy_tree(live/name,dest/name,name)
# Android is a separate client source tree, not part of the production web server.
android=dev/'android-native'
if android.is_dir():copy_tree(android,dest/'android-native','android-native')
ignore='''# Source-only Relay Social repo; live data must NEVER be committed.
.env
.env.*
!.env.example
data/
media/
node_modules/
coverage/
*.log
*.sqlite
*.sqlite-*
*.db
*.db-wal
*.db-shm
.master-key
**/.gradle/
**/build/
**/dist/
**/node_modules/
**/*.apk
**/*.aab
**/*.jks
**/*.keystore
**/*.p12
**/*.pfx
public/downloads/
**/local.properties
.idea/
.vscode/
'''
(dest/'.gitignore').write_text(ignore,encoding='utf8')
description='''# Relay Social — GitHub source-only staging

This is a **snapshot** of the live Relay Social source, copied read-only
from H:\\Relay Social on November 23, 2026. The live server was not
stopped, restarted, or modified. Static public assets and server source are
present; persistent accounts/database, encryption keys, uploaded media,
node_modules, download APKs, logs and production .env are intentionally absent.

The Android-native client source is copied from
H:\\Samsung Android\\PSPortalAndroid\\services\\relay-social\\android-native
and is not part of the production web server root.

This is NOT automatically a deployed website or a connected Git repository.
Do not run against live data without configuration. Do not push until source
review/licensing and account-specific remote destination are chosen.

This snapshot uses production versions of server.mjs and public/index.html,
which differed from the development versions at preparation time.
'''
(dest/'GITHUB_PREP.md').write_text(description,encoding='utf8')
def hashfile(p):
 h=hashlib.sha256()
 with p.open('rb') as fh:
  for b in iter(lambda:fh.read(1<<20),b''):h.update(b)
 return h.hexdigest()
index=[{'path':rel,'bytes':out.stat().st_size,'sha256':hashfile(out)} for rel,_src,out in copied]
(dest/'SOURCE_INVENTORY.json').write_text(json.dumps(index,indent=2)+'\n',encoding='utf8')
print('Relay Social snapshot CREATED:',dest)
print('SOURCE FILES:',len(copied),'SIZE MiB:',round(sum(x['bytes'] for x in index)/1048576,2))
print('PROTECTED PRODUCTION FOLDERS NOT COPIED:',', '.join(sorted(skip_dirs)))
print('Live folder:',live,'READ-ONLY')

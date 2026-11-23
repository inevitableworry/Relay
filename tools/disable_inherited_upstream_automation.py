"""Disable inherited upstream Chiaki release automation in Relay GitHub prep.
Save historical metadata separately. Never trigger GitHub workflows.
"""
from pathlib import Path
import shutil
root=Path(r'H:\Samsung Android\PSPortalAndroid')
github=root/'.github';archive=root/'docs/upstream-github-config'
archive.mkdir(parents=True,exist_ok=True)
for name in ['CODEOWNERS','funding.yml']:
 src=github/name;dst=archive/name
 if src.is_file():
  if not dst.exists():shutil.copy2(src,dst)
  src.unlink()
  print('Archived upstream-only ownership/funding config:',name)
changes={
 'build-release.yaml':("on:\n  push:\n    tags: [ 'v*.*.*' ]","on:\n  workflow_dispatch:"),
 'build-weekly.yaml':("on:\n  workflow_dispatch:\n  schedule:\n    - cron: \"30 1 * * 1\"","on:\n  workflow_dispatch:"),
 'github-prereleases-discord.yml':("on:\n  release:\n    types: [prereleased]","on:\n  workflow_dispatch:"),
 'github-releases-to-discord.yml':("on:\n  release:\n    types: [released]","on:\n  workflow_dispatch:"),
 'stale-issues.yaml':("on:\n  schedule:\n    - cron: \"30 1 * * *\"","on:\n  workflow_dispatch:")
}
disabled='    if: '+chr(36)+'{{ false }}'
for filename,(old,new) in changes.items():
 path=github/'workflows'/filename
 original=path.read_bytes()
 out=archive/filename
 if not out.exists():out.write_bytes(original)
 s=original.decode('utf8').replace('\r\n','\n')
 if old in s:s=s.replace(old,new,1)
 elif new not in s:raise RuntimeError('Unknown workflow trigger: '+filename)
 if filename=='build-release.yaml':
  s=s.replace('name: Create chiaki-ng release','name: Upstream chiaki-ng release template (disabled for Relay)',1)
  marker='  create-release:\n'
  if disabled not in s:
   assert marker in s
   s=s.replace(marker,marker+disabled+' # Keep release disabled until Relay approval.\n',1)
 elif filename.startswith('github-') and filename.endswith('discord.yml'):
  marker='  github-releases-to-discord:\n'
  if marker in s and disabled not in s:
   s=s.replace(marker,marker+disabled+' # No inherited upstream Discord notifications.\n',1)
 if 'Relay fork safety' not in s:
  s=s.replace('\n\non:\n','\n\n# Relay fork safety: upstream automation manual-only.\non:\n',1)
 path.write_bytes(s.encode('utf8'))
 print('Deactivated automatic workflow trigger:',filename)
print('No GitHub workflow ran or published.')

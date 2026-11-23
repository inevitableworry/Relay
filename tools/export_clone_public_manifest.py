from pathlib import Path
import json
root=Path(r'H:\Samsung Android\PSPortalAndroid')
local=json.loads((root/'docs/RELAY_LOCAL_CLONES_MANIFEST.json').read_text(encoding='utf-8-sig'))
public=[]
for obj in local:
 rel=obj['repo_relative_path']
 url=obj.get('remote')
 commit=obj.get('clone_head')
 kind='git-clone'
 if rel=='third-party/jxrlib':
  source=json.loads(Path(r'H:\Relay Source Clones\third-party\jxrlib\SOURCE.json').read_text(encoding='utf8'))
  url=source['repository'];commit=source['commit'];kind='source-archive'
 if not (url and url.startswith('https://github.com/') and commit and len(commit)==40):
  raise RuntimeError('Cannot publicly pin a clone: '+rel)
 public.append({'path':rel,'repository':url,'commit':commit,'source_kind':kind,
                'note':('Existing local clone contains uncommitted changes not included in public manifest' if obj.get('uncommitted_file_count') else '')})
out=root/'docs/RELAY_EXTERNAL_SOURCE_MANIFEST.json'
out.write_bytes((json.dumps({'purpose':'Public clone definitions only: actual sources stay outside Git','entries':public},indent=2)+'\n').encode('utf8'))
print('Public external source manifest',len(public),'entries',out)

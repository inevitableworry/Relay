from pathlib import Path
root=Path(r'H:\Samsung Android\PSPortalAndroid')
p=root/'.gitignore'
text=p.read_text(encoding='utf8')
manifest=__import__('json').loads((root/'docs/RELAY_LOCAL_CLONES_MANIFEST.json').read_text(encoding='utf-8-sig'))
for item in manifest:
 relative=item['repo_relative_path']
 before=relative+'/'
 # Only exact anchored clone patterns in the GitHub-prep section.
 if '\n'+before+'\n' in text:
  text=text.replace('\n'+before+'\n','\n'+relative+'\n',1)
p.write_text(text,encoding='utf8')
print('Adjusted ignore patterns for 16 directory junctions (Git treats junctions like symlinks).')

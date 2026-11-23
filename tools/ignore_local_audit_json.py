from pathlib import Path
p=Path(r'H:\Samsung Android\PSPortalAndroid\.gitignore')
b=p.read_bytes().decode('utf8')
if '# BEGIN RELAY GITHUB PREP — LOCAL AUDIT REPORTS' not in b:
 b+='''
# BEGIN RELAY GITHUB PREP — LOCAL AUDIT REPORTS
# Local absolute paths, package inventories and scanned filenames are not part
# of the public source release. Their summarized Markdown is stored separately.
docs/GITHUB_PREP_*.json
docs/RELAY_DEBUG_RELEASE_CLEANUP_*.json
docs/RELAY_LOCAL_CLONES_MANIFEST.json
docs/RELAY_PRE_CLEANUP_ARTIFACT_INDEX.json
# END RELAY GITHUB PREP — LOCAL AUDIT REPORTS
'''
 p.write_bytes(b.replace('\r\n','\n').encode('utf8'))
 print('Excluded raw local audit JSON paths from future GitHub upload.')
else:print('Local audit already ignored')

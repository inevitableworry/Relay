from pathlib import Path
p=Path(r'H:\Samsung Android\PSPortalAndroid\.gitignore')
t=p.read_bytes().decode('utf-8').replace('\r\n','\n')
snippet='''docs/RELAY_LOCAL_PS1_SCAN_LIST.json
docs/RELAY_POWERSHELL_SOURCE_SYNTAX.json
docs/RELAY_PYTHON_SOURCE_SYNTAX.json
'''
if 'docs/RELAY_LOCAL_PS1_SCAN_LIST.json' not in t:
 t=t.replace('# END RELAY GITHUB PREP — LOCAL AUDIT REPORTS',snippet+'# END RELAY GITHUB PREP — LOCAL AUDIT REPORTS')
 p.write_bytes(t.encode('utf8'))
 print('Ignored local-only source syntax scan inventories.')
else:print('Syntax scan inventories already ignored.')

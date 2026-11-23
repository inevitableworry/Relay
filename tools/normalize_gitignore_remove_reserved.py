from pathlib import Path
root=Path(r'H:\Samsung Android\PSPortalAndroid')
p=root/'.gitignore'
b=p.read_bytes()
normalized=b.replace(b'\r\n',b'\n').replace(b'\r',b'\n')
if normalized!=b:
 p.write_bytes(normalized)
 print('Normalized Relay .gitignore to LF; preserved lines and ignore patterns.')
else: print('Already LF')
nul=Path('\\\\?\\H:\\Samsung Android\\PSPortalAndroid\\NUL')
if nul.exists():
 data=nul.read_bytes()
 assert len(data)==56,'Reserved file unexpectedly changed, refusing deletion'
 nul.unlink()
 print('Deleted stray untracked reserved-name NUL artifact (56 bytes).')
else:print('Reserved name artifact absent')

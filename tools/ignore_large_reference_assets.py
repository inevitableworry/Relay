from pathlib import Path
p=Path(r'H:\Samsung Android\PSPortalAndroid\.gitignore')
text=p.read_text(encoding='utf8')
mark='# BEGIN RELAY GITHUB PREP — LOCAL REFERENCE MEDIA'
if mark not in text:
 text+='''
# BEGIN RELAY GITHUB PREP — LOCAL REFERENCE MEDIA
# Large, third-party or account-specific research inputs stay on BEN-PC.
# Source, build scripts, icons and normal docs remain visible to Git.
docs/portal-reference/video/
docs/portal-reference/audio/.models/
docs/**/*.mp4
docs/**/*.mkv
docs/**/*.mov
docs/**/*.webm
docs/**/*.raw
research/asobi/
research/asobi-android-2.0-installed/
**/.models/
**/target/
# Experimental native payload is a build input/deployment asset, NOT source.
# Rebuild or supply it privately when packaging the jailbroken-PS5 feature.
android/app/src/main/assets/jb/pbs-ps5.elf
# END RELAY GITHUB PREP — LOCAL REFERENCE MEDIA
'''
 p.write_text(text,encoding='utf8')
 print('Excluded large third-party references and local media from Git, files retained physically.')
else:print('Media exclusion already present')

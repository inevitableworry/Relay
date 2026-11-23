from pathlib import Path
root=Path(r'H:\Samsung Android\PSPortalAndroid')
p=root/'.gitignore'
text=p.read_text(encoding='utf-8')
anchor='# BEGIN RELAY GITHUB PREP — LOCAL CLONES AND GENERATED ARTIFACTS'
if anchor not in text:
    text += '''

# BEGIN RELAY GITHUB PREP — LOCAL CLONES AND GENERATED ARTIFACTS
# Third-party full clones live outside this Git worktree at H:\\Relay Source Clones.
# Junctions remain at original paths solely for local build compatibility.
# Keep tracked git submodules in .gitmodules — those are Git commit pointers, not clones.
.external-clones/
.local-clones/
.tools/pbs-upstream/
external/InstantCaption/
third-party/InstantCaption/
third-party/jxrlib/
platforms/ps5-social-link/vendor/qrcodegen-upstream/
platforms/switch1/tools/packaging/hacbrewpack-src/
platforms/switch1/tools/packaging/nx-tools/
platforms/windows11/research/Playnite-FSE-Launcher/
platforms/xbox360/toolchain/xsdk/
research/design-skills/anti-slop-design/
research/design-skills/no-slop-ui/
research/design-skills/unslop-ui-skill/
research/design-skills/ux-ui-skills/
research/ios-build-tools/TheosWin/
research/ios-build-tools/WindowsIPABuilder/
research/playtranslate-upstream/

# Generated build products across supported targets (not source).
**/.cxx/
**/.gradle/
**/.dart_tool/
**/node_modules/
**/target/debug/
**/target/release/
**/build/
**/bin/Debug/
**/bin/Release/
**/obj/Debug/
**/obj/Release/
**/dist/
**/Debug/
**/Release/
**/outputs/
releases/
android/app/build/
android/app/.cxx/
platforms/switch1/exefs-dev/
platforms/switch1/nca-title-output/
platforms/switch1/title-stage/
platforms/switch1/*.nro
platforms/switch1/*.elf
platforms/switch1/*.nsp
platforms/switch1/*.nacp
platforms/switch1/*.npdm
platforms/3ds/*.3dsx
platforms/3ds/*.cia
platforms/3ds/*.cci
platforms/3ds/*.elf
platforms/3ds/*.smdh
platforms/samsung-tizen/.buildResult/
platforms/samsung-tizen/.wgt-input/
platforms/samsung-tizen/.manifest.tmp
platforms/samsung-tizen/*.wgt
platforms/roku/*-preview.zip
platforms/apple/*.ipa
*.apk
*.aab
*.appx
*.msix
*.vpk
*.3dsx
*.cci
*.cia
*.nsp
*.nro
*.wgt
*.xex
*.pdb
*.ilk
*.tlog
*.obj
*.o
*.class

# Private production settings, keys, user accounts and local service data.
**/.env
**/.env.*
!**/.env.example
**/*.jks
**/*.keystore
**/*.p12
**/*.pfx
**/*.sqlite
**/*.sqlite-*
**/*.db
**/*.db-wal
**/*.db-shm
**/.master-key
**/credentials.json
**/credential.json
**/secrets.json
**/secret.json
**/private.key
**/private.pem
services/relay-social/data/
services/relay-social/media/
services/relay-social/public/downloads/
services/relay-update/state/
# END RELAY GITHUB PREP
'''
    p.write_text(text,encoding='utf-8')
    print('Appended GitHub preparation ignore policy')
else:
    print('Ignore policy already present')

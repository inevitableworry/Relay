from pathlib import Path
root=Path(r'H:\Samsung Android\PSPortalAndroid')
readme=root/'README.md'
upstream=root/'docs/UPSTREAM_CHIAKI_NG_README.md'
if not upstream.exists():upstream.write_bytes(readme.read_bytes())
text='''# Relay

Relay is an independent, experimental PlayStation Remote Play client and
PS Portal-inspired interface. Android is the canonical UI/interaction
reference; additional native and cross-platform implementations are under
development. **Most non-Android platforms remain prototypes**, and a
successful source build does not prove a working PS5 stream, decoded audio,
controller forwarding, or visual parity.

Relay contains code derived from the open-source Chiaki / chiaki-ng projects.
The original chiaki-ng repository and attribution are preserved below. It is
not affiliated with or endorsed by Sony Interactive Entertainment.

## Code organization

| Project | Location in this repository | Purpose |
| --- | --- | --- |
| Android, Android TV, foldables and gaming handhelds | android/ | Canonical Relay UI and Android client |
| Switch, 3DS, PS Vita, Windows, Xbox, Apple, Roku and Tizen | platforms/ | Separate native/experimental platform implementations |
| Shared client, UI, audio and runtime libraries | lib/, gui/, assets/, cmake/ | First-party changes and inherited reusable code |
| Relay update service | services/relay-update/ | Source-only update delivery backend |
| Integration and helper scripts | scripts/, tools/, cli/ | Rebuild, validate and debug; not bundled releases |
| Design, test evidence and per-platform status | docs/, RELAY_BUILD_PARITY_MASTER.md | Platform acceptance gates and known gaps |
| Third-party tracked submodules | third-party/, test/, switch/borealis/ | Git submodule pointers only |

Relay Social is prepared as **a separate source-only repository** at
H:\\Relay GitHub Staging\\Relay-Social. The live Social website at
H:\\Relay Social is NOT part of the Relay Git tree and is never used as
a build/cache destination. Its database, keys, private uploads, environment,
and downloads are omitted from the source snapshot.

## Third-party clones are outside Git

16 standalone source clones and vendored dependency copies are stored in
H:\\Relay Source Clones, OUTSIDE this repository. Their original local paths
are ignored NTFS junctions for build compatibility; their full source
is not staged for GitHub. See docs/RELAY_EXTERNAL_CLONES_SETUP.md and
docs/RELAY_EXTERNAL_SOURCE_MANIFEST.json for pinned public upstream URLs
and bootstrap instructions. Git-tracked upstream submodules remain
gitlink commit references in .gitmodules.

## Debug/release builds

On November 23, 2026, local generated debug/release builds and package
collections were removed so they will not be uploaded accidentally.
**No compiled package is included as a release in this source tree.**
The build scripts and shared source remain. Prior build results documented
in RELAY_BUILD_PARITY_MASTER.md are historical, not installed build files.

Rebuild the needed platform using its own build script and follow
docs/RELAY_ARTIFACT_POLICY.md. Keep generated artifacts local until a
specific GitHub release is approved.

## Development checks

Before publishing or tagging, review:
- docs/GITHUB_READINESS_2026-11-23.md — source audit, safety and GitHub gates.
- RELAY_BUILD_PARITY_MASTER.md — full cross-platform inventory.
- docs/RELAY_ARTIFACT_POLICY.md — artifact naming, cleanup and validation.
- AGENTS.md — contribution and UI parity rules.
- SECURITY.md and CONTRIBUTOR_GUIDE.md — security and contributor expectations.

Run these source checks after changes:

    python tools/check_relay_ui_parity.py
    python platforms/switch1/verify_ui_parity.py
    git diff --check

The PS3/PS4/PS5 firmware, console pairing credentials, runtime signing
keys, personal information and private Relay Social production data must
not be committed. A reference asset or passing build is not evidence of
functional gameplay streaming or licensing to redistribute proprietary art.

## Original Chiaki/chiaki-ng attribution

Relay builds on work from:
- Chiaki: https://github.com/thestr4ng3r/chiaki
- chiaki-ng: https://github.com/chiaki-ng/chiaki-ng

Its existing GPL source license, COPYING, LICENSES and third-party notices
remain in place. Keep all upstream copyright notices and license
requirements when distributing or publishing modified code. Relay is an
unofficial independent project; PlayStation and PS Portal are trademarks
of their respective owners.

No GitHub destination or release has been configured or pushed as part
of the source cleanup.
'''
readme.write_bytes(text.encode('utf8'))
print('Updated Relay project README; original upstream README preserved in docs.')
doc=root/'docs/RELAY_EXTERNAL_CLONES_SETUP.md'
body='''# Relay external source clones — local-only dependency setup

The standalone clones are physically stored outside the Git repository,
in H:\\Relay Source Clones on BEN-PC. Ignored junctions preserve the old
project-relative paths so local scripts that reference them keep working.

- Source clone manifest: docs/RELAY_EXTERNAL_SOURCE_MANIFEST.json (16 pinned
  GitHub repo URLs/commits). This manifest is safe to commit because it lists
  provenance only, not full source trees or private data.
- Local-only manifest: docs/RELAY_LOCAL_CLONES_MANIFEST.json (ignored in Git,
  includes absolute device paths and pre-existing dirty working tree state).
- Setup script: tools/bootstrap_local_clones.ps1, default DRY RUN. It only
  downloads public sources into an external directory when invoked with
  -Execute. It refuses to overwrite existing clone worktrees or alter
  unexpected commits.

Example on a fresh Windows checkout:

    powershell -NoProfile -ExecutionPolicy Bypass -File tools/bootstrap_local_clones.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File tools/bootstrap_local_clones.ps1 -Execute -CloneBase "H:\\Relay Source Clones"
    git submodule update --init --recursive

On a different Windows PC, omit -CloneBase to use the user's LOCALAPPDATA
Relay/Source Clones path. This script never installs or deploys Relay.

**Special cases:** third-party/jxrlib is preserved as an upstream source
archive at a known public commit and includes SOURCE.json with provenance.
The PBS upstream clone had **nine pre-existing locally modified tracked
files** when relocated; those changes were preserved in the external clone,
but are not reproduced by fetching its upstream commit. Review and migrate
the changes separately if that tool is required for a reproducible build.

Tracked submodules listed in .gitmodules (such as nanopb, curl, jerasure,
oboe and borealis) are intentionally retained as Git commit pointers;
GitHub will not receive their full code as repository files.

Never force-add ignored local junctions. Do not upload full vendor clones,
private credentials, signing keys, production databases or model caches
when creating the Relay GitHub repository.
'''
doc.write_bytes(body.encode('utf8'))
print('Wrote external clone setup documentation.')
policy=root/'docs/RELAY_ARTIFACT_POLICY.md'
t=policy.read_bytes().decode('utf8').replace('\r\n','\n')
notice='''**GitHub preparation (2026-11-23):** All locally generated Relay
debug/release packages and verified build-output directories were cleaned
from the working tree before publication (9.37 GiB selected). Source,
build scripts, canonical assets and QA screenshots were retained.
Build locations and hashes in earlier sections are historical, not promises
that package files remain present. Consult docs/GITHUB_READINESS_2026-11-23.md
and the ignored local cleanup receipt before rebuilding.

'''
if 'GitHub preparation (2026-11-23)' not in t:
 idx=t.find('\n\n')
 t=t[:idx+2]+notice+t[idx+2:]
 policy.write_bytes(t.encode('utf8'))
 print('Updated artifact policy with explicit source-only cleanup notice.')
master=root/'RELAY_BUILD_PARITY_MASTER.md'
m=master.read_bytes().decode('utf8').replace('\r\n','\n')
notice='''**GitHub pre-push state — November 23, 2026:** The generated packages and
binary build caches below were removed from the local source tree. Paths
in the existing platform inventory record historical output locations
and prior tests; they are NOT current installable artifacts. Rebuild a
specific platform and record fresh evidence before claiming a new release.
The live Relay Social website and private data are untouched. Original
third-party cloned repositories are external and Git-ignored.

'''
if 'GitHub pre-push state — November 23, 2026' not in m:
 insert=m.find('\n\n',m.find('**Last audited:**'))
 m=m[:insert+2]+notice+m[insert+2:]
change='''| 2026-11-23 | GitHub source organization and generated-build cleanup (no feature implementation) | Android UI source retained unchanged; no new visual parity claim | Removed 9.37 GiB generated debug/release material, retained build scripts and test evidence, moved 16 standalone cloned sources outside Git with ignored compatibility junctions, created a separate source-only Relay Social staging repository without modifying the live site. Cross-platform source asset test passed; the Switch baseline hash-lock still flags PortalMainActivity.kt and PortalUpdateActivity.kt for intentional-change review. No commit/push or new native build. See docs/GITHUB_READINESS_2026-11-23.md. |
'''
if 'GitHub source organization and generated-build cleanup' not in m:
 marker='| Date | Change | Android baseline | Platform synchronization and validation |\n|---|---|---|---|\n'
 if marker in m:m=m.replace(marker,marker+change,1)
 else:m+='\n'+change
master.write_bytes(m.encode('utf8'))
print('Updated master parity change log and historical build status.')

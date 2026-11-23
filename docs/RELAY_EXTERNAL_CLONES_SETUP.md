# Relay external source clones — local-only dependency setup

The standalone clones are physically stored outside the Git repository,
in H:\Relay Source Clones on BEN-PC. Ignored junctions preserve the old
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
    powershell -NoProfile -ExecutionPolicy Bypass -File tools/bootstrap_local_clones.ps1 -Execute -CloneBase "H:\Relay Source Clones"
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

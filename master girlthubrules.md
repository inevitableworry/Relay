# Relay — Master GitHub and Release Rules

**Authority:** These rules govern the public Relay repository, all maintained platform builds, GitHub Actions, release assets, visual demonstrations, source credits, and future release decisions. The Android Relay UI remains the visual source of truth. See `RELAY_BUILD_PARITY_MASTER.md` for per-platform runtime evidence.

## Repository and privacy

- Keep the primary Relay source repository **public** specifically under **`inevitableworry/Relay`** (`https://github.com/inevitableworry/Relay`). **Never** create, push, or publish Relay to the `nahalewski` account. Verify the GitHub account and destination before any mutation. Retain the original Chiaki/chiaki-ng upstream as a separate read-only remote. Never push changes to that upstream.
- Do not include the live Relay Social source tree, site URLs or domains, production server credentials, player data, account records, pairing codes, profile identifiers, access keys, personal media, console addresses, local paths, or private backups. Relay Social is a separate future repository/project.
- Exclude fully cloned external/vendor repositories, temporary toolchains, caches, archived research, deployment secrets, debug builds, keystores, signing files and any source archives that are not redistributable. Preserve legitimate Git submodule pointers and document their upstream origins.
- Maintain the original open-source licenses, full copyright notices, source attribution and required license text. Include original upstream repository links in `CREDITS.md`; never remove a legally required attribution or falsely claim exclusive authorship.
- Do not insert optional assistant/service authorship banners, chat logs, generative-tool credits or other irrelevant development provenance into code or public release notes. Functional provider/API names and genuine contributor credits are not authorship banners and must remain accurate.
- Check the Git diff, staged file manifest, secrets, large files, source licenses, executable/binary files, unexpected vendor clones, and unwanted authorship markers **before every push and release**. Any high-confidence leak blocks publication.

## Versioning — one monotonic number across Relay

- Use `Relay 0.MINOR.PATCH` for the experimental public series, Git tags `relay-v0.MINOR.PATCH`; once a stable API/production release is achieved the major version may move to 1 by explicit maintainer decision. All platform-specific files in the same release share the same global number.
- Initial public release is `relay-v0.1.0`. Never reset, reuse, rewrite, delete, or move an existing release tag to make a number smaller or the same.
- From the last Relay release tag, count changed **first-party source code** (excluding generated files, external clones and documentation). Any source changes qualify for a patch increment; at least **500 changed source lines or 15 changed source files** qualifies for a minor increment and resets patch to zero. Documentation-only changes do **not** create a new binary release. An explicitly incompatible public API change must be reviewed for a major-version bump.
- Choose versions from the latest existing immutable `relay-v*` tag, not from a local clock, current app flavor or an upstream Chiaki tag. A new release requires eligible platform changes, real build verification and a confirmed version higher than every earlier Relay tag. Weekly runs with no eligible new source must exit successfully without publishing a duplicate.

## Build cadence and options

- GitHub Actions checks for eligible builds **every Monday at 00:00 America/New_York**, using a timezone-aware schedule. A manual dispatch can select a target or all supported targets. Scheduled jobs do not force a release when no new eligible code exists.
- A common version is assigned before per-target builds. Publish only the platforms whose source, capabilities, signatures and UI evidence pass the release gate. Missing, blocked or failed platforms are **omitted**, not replaced by empty assets or prior stale builds.
- Supported selector names are `3ds`, `android-mobile`, `android-tv`, `android-bravia`, `android-dualscreen`, `android-other`, `vita`, `switch`, `windows`, `xbox`, `apple`, `tizen`, `roku`, `steamos`, `all`; selectors without a reproducible public release toolchain must fail/skip clearly.
- GitHub-hosted build jobs must build **release configuration** (optimized / signed as required); never upload APK debug builds, test executables, Gradle debug APKs, unsigned Android packages advertised as installable, empty placeholder binaries or inherited upstream release files.
- Real menu launch/animation must be verified in an emulator or on target hardware for each target **and for the exact relevant source revision**. A passed compiler, screenshot of an unrelated version or source parity hash alone does not qualify.
- Every published artifact has a simple platform identifier: `Relay-Android-Mobile-vX.Y.Z.apk`, `Relay-Android-TV-vX.Y.Z.apk`, `Relay-Android-BRAVIA8II-vX.Y.Z.apk`, `Relay-Android-DualScreen-vX.Y.Z.apk`, `Relay-3DS-vX.Y.Z.3dsx`, etc., according to the actual target package format. Publish a checksum file for all released binaries.

## Screenshots and animated previews

- Each release version with an eligible build gets **one real main-menu screenshot and one animated GIF for each released platform**, both labeled with the exact global version. The image and GIF should depict the actual app's UI on that platform, with animation frames obtained from a running emulator or physical device. Do not fabricate gameplay, streaming, pairing or performance claims.
- Give every image/GIF a versioned filename such as `Relay-3DS-v0.1.0-menu.png` and `Relay-3DS-v0.1.0-menu.gif`, with `v0.1.0` visibly overlaid on the media. Update/capture fresh media for every significant UI update; a different version label alone is not a replacement for verification of changed visuals.
- Visual samples for unreleased or unfinished platforms may be shown in README as **development previews**, clearly separated from release downloads. Do not use an unfinished platform to advertise a completed release.
- Relay Social images may show an actual development UI, optional profile/console pairing flow and a friend-code field **only with the code completely obscured**. Verify no username, email, PSN token, console address, QR payload or other private identifier remains readable. Do not include Social URLs until separately authorized.

## Public README and releases

- README must be meticulously formatted with a top Relay logo/banner, compact navigation, status/compatibility tables, visual build galleries, usage, setup prerequisites, keyboard/dual-display capabilities, pairing explanation, privacy/security, contribution instructions, licensing/credits, roadmap, and Relay Social teaser. No emojis, irrelevant marketing slogans or unrelated research names.
- Distinguish **verified** features from experimental/unverified and unsupported capabilities. Do not call native shells fully functional Remote Play clients when live streaming is not implemented.
- Release descriptions use clear headings, version, build date/source commit, what changed, platform compatibility, exact available build filenames, installation instructions, tested features, limitations, screenshots/GIFs and hashes. No emojis. Use GitHub Release assets only; never check binaries into the source tree.
- The repository logo is stored in `docs/branding/relay-icon-original.png`, referenced in README and included in versioned release media. A GitHub Social Preview image may require an explicit Settings upload if not supported by the API.

## Automation safety

- The schedule is a **build/verification opportunity**, not permission to publish an unverified platform. A branch/ref mismatch, unknown license, missing platform proof, failed source-hygiene scan, missing signing credentials, or absent runnable target must block that platform's release.
- Preserve exact release/source hashes and an evidence manifest under `docs/release-validation/`. Do not force-push or delete published release history.
- Keep inherited upstream release/weekly/Discord workflows disabled so only intentional Relay workflows can publish. Source changes are versioned together, but each platform's release readiness stays independent.
- Future contributions use normal signed/identified Git commits from real maintainers. GitHub commit history and legally required attribution must not be falsified or scrubbed.

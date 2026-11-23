# Relay keyboard rollout and Azahar visual validation — 2026-11-23

## Visual reference basis

- Sony PS Portal passcode UI from the official 2025-11-05 PlayStation Blog (dark-violet surface, clear focus/entry). `docs/references/portal-keyboard/psportal-passcode-reference.png`.
- PS5 system Store-search keyboard reference (numeric row, QWERTY, outlined selected key, bottom actions). `docs/references/portal-keyboard/ps5-search-keyboard-reference.jpg`.
- These are different UI contexts. **A verified standalone PS Portal native QWERTY screenshot was not located**. Relay is an original PlayStation-inspired rendering, not a firmware image clone. Design source contract: `docs/RELAY_KEYBOARD_STANDARD.md` and `assets/relay-keyboard-layout.json`.

## Android builds

`build-android.ps1 -All` completed successfully (10 device flavors; 413 Gradle tasks, 59 executed and 354 up-to-date). All use the new shared `PortalOnScreenKeyboard.kt` on the Android search field, with a number row, alpha keys, symbol mode, Shift, Space, Backspace, Done and controller-focusable keys. The dedicated dual-display `PortalThorSecondScreen.kt` presents a separate keyboard on the second display when selecting Search, while the primary UI remains on the first display. This is **compiled, not verified on actual dual-screen hardware**.

| Flavor | SHA-256 of named local debug APK |
| --- | --- |
| mobile | `E528CA7C7DD4160258BEFA435E5A3148056A69A9806F29967B996BDA3668B1F0` |
| tv | `413AD1E4DFECB11B54EF7A64C7508EED6793DC8B559202B3BFC751BCF2ECF30B` |
| shield | `FD4011B9E9934FB858FD9579B73CABF5B438D69F0550B1DF91B465992EB5802E` |
| googleTv | `DEFB7B1155A380C6177DE18EA349E105F2403F184A5B58330893BA0A708A7A2D` |
| bravia8ii | `CE2D636BE4774D86E6A799F8B41BFA86A984BECC01DD922F3512CE9AAFF1AB73` |
| retroid | `2D73A8819CB9AA80F2D3C7A0FE3BC335F2DF6323B2F53BF7DFFCC688B20B213A` |
| razerEdge | `8E9D6A6C9F003F70BB3D4D65E104CC9500AD27B2880838B4A650AD579F101D28` |
| aynThor | `9877B47923A23035203C40801497482DFA5BFDE5DE071746ACFDCE73C65A973A` |
| dualScreen | `495B2F3B5008843C7BEDFA145604F1EEA587C8BAC6FFB00E30ADE9ED2547E958` |
| metaQuest | `ADBDAC4D0EA76C437B3AD0E90FC3AAD67A6798F94CEA472D4C555227CDF2F022` |

Local named builds: `releases/local-development/`. Build log: `docs/KEYBOARD_ALL_ANDROID_20261123.log`.

## Nintendo 3DS XL / New 3DS XL — actual emulator screen evidence

- Final native build: `platforms/3ds/dist/Relay-3DSFamily-Horizon-ARM11-dev.3dsx` (same ARM11 source for both hardware profiles).
- Source implementation uses Citro2D: full top Relay visual/orb and status screen (400×240), independent bottom navigation/QWERTY keyboard (320×240), number row, letters, symbols, Shift, Space, Delete, Done, D-pad and touch handling.
- Azahar 2126.2 **New 3DS profile**: emulator screenshot directly captured through Azahar's `Tools > Capture Screenshot` command. Top orb and lower keyboard both visibly render: `docs/screenshots/relay-3ds-azahar-keyboard-X-test-20261123.png`.
- Controller input: using Azahar's mapped X key opened the keyboard; its mapped A key entered `1`. The second real emulator screenshot `docs/screenshots/relay-3ds-azahar-keyboard-A-typed-20261123.png` shows `1` in both the lower entry field and the top Search view. **Controller-to-local keyboard test passed**; this does NOT imply remote console input is implemented.
- Azahar **original 3DS profile**: `System_IsNew3ds: false` confirmed in emulator log. Native screenshot `docs/screenshots/relay-3ds-azahar-old3ds-native-20261123.png` shows full top orb/status and lower menu. 3DS touch, symbol mode, character case and keyboard input on Old 3DS remain to be tested.
- Performance shown by Azahar UI: approximately 59–60 FPS in these UI tests; this is **emulator UI rendering only**, not physical handheld performance or streamed video FPS.
- Original roaming Azahar settings were backed up and restored exactly after profile testing (verified identical SHA-256). Experimental CIA/CCI archived; 3DSX is the only active development package.
- Source/GUI reference not pixel-identical to Portal; 3DS display resolution requires smaller labels and simplified effects.
- **Unimplemented:** real PS3/PS4/PS5 video decode, streamed audio, Remote Play session pairing and remote controller/keyboard delivery. They remain separate engineering tasks.

## Remaining ports

The architecture/design requirement is propagated to all maintained Relay platforms in the master tracker. Only Android's 10 Gradle flavors and the 3DS native code were implemented and compiled in this pass. Vita/PSTV, Switch, SteamOS, Windows/ROG Ally, Xbox, Roku, Tizen, iOS and tvOS still require their respective native keyboard integrations and runtime verification. No GitHub release was published.

**Final source audits:** `tools/check_relay_ui_parity.py` reported zero source asset mismatches. The separate Switch source hash-lock reported two changed files, `PortalMainActivity.kt` (the intentional Android keyboard/UI change) and `PortalUpdateActivity.kt` (an additional existing change), so the Switch parity gate remains **failed/pending review**. The organized artifact index was refreshed with 26 source package entries.

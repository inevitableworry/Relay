# Relay — feature backlog and product direction

**Updated:** 2026-11-23 · **Scope:** Relay Social, Android/console clients, PS5-host infrastructure and community features.

This is a **planning backlog**, not a list of shipped capabilities. `[x]` means an implementation exists (but may still need hardware or security validation), `[ ]` means not shipped. Priorities are **P0** security/launch blocker, **P1** next release, **P2** expansion, **P3** exploratory research. Source of truth for actual platform readiness remains [RELAY_BUILD_PARITY_MASTER.md](RELAY_BUILD_PARITY_MASTER.md).

## Design rules that apply to every item

- Android Relay remains the canonical PS Portal-inspired visual reference. Social must use the same authentic Relay identity without pretending to be Sony's official PSN product.
- Preserve owner consent, private pairing IDs, friend-code-only introductions, strict permissions and no MAC-address-based recovery.
- Test every interactive state: loading, empty, failed, fulfilled, permission denied, expired and cancelled. Screens and labels must describe real backend behavior.
- Apply the locally cloned design guidance in [research/design-skills](research/design-skills) but **do not automatically execute external skill scripts**. Product-specific design decisions override generic preferences.
- After any feature change, record affected platforms, tests, build artifacts and blocked implementations in the parity master. Never call an unfinished simulation a finished feature.

## 1 — Accounts, identity, security (20 items)

- [x] **P0** Email/password login with hashed passwords and protected sessions.
- [x] **P0** Authenticator TOTP enrolment with local QR generation and manual fallback.
- [x] **P0** One-time recovery codes for TOTP.
- [x] **P0** Hidden internal PS5 pairing ID separated from shareable friend code.
- [ ] **P0** Verified email before unrestricted publishing or friend requests.
- [ ] **P0** Password-reset email with single-use signed/hashed tokens and short TTL.
- [ ] **P0** Passkeys / WebAuthn enrollment, sign-in and authenticator verification.
- [ ] **P0** Account/session dashboard with device, timestamp, last seen and revoke controls.
- [ ] **P0** Optional require-2FA policy for administrators and moderators.
- [ ] **P0** Sensitive-action re-authentication and confirmation with recovery codes.
- [ ] **P1** Log in with a trusted device using explicit user approval.
- [ ] **P1** Add/change verified email with security notifications.
- [ ] **P1** Multiple PS5 profiles linked to one website account, individually revocable.
- [ ] **P1** User-requested data export and full account deletion workflow.
- [ ] **P1** Account inactivity timeout configuration and active-session inventory.
- [ ] **P1** Suspicious-login alerts and new-device approval.
- [ ] **P2** Security activity timeline filterable by event, device and IP region.
- [ ] **P2** Household/family access without sharing account credentials.
- [ ] **P2** Trusted-contact recovery with cooldown and out-of-band approval.
- [ ] **P3** Privacy-preserving authentication risk scoring.

## 2 — Profiles, theming, public identity (20 items)

- [x] **P1** Dedicated signed-in member profile route `/u/:slug`.
- [x] **P1** Owner-facing “View as others” modes for non-friends and accepted friends.
- [x] **P1** Public profile identity filtered to PS5 name/avatar or social fallback.
- [x] **P1** Public trophy shelf showing only earned achievements.
- [x] **P1** Profile link copy and navigation from member identity controls.
- [ ] **P1** Customizable profile cover image with safe dimension limits.
- [ ] **P1** Avatar cropping tool with offline canvas preview and EXIF stripping.
- [ ] **P1** Site-avatar to PS5-avatar synchronization gated by console capability/consent.
- [ ] **P1** PS5-name source indicator and signed console-sync timestamp.
- [ ] **P1** Public profile activity filtering: posts, guides, Relay groups, trophies.
- [ ] **P1** Pinned post, pinned trophy and featured community contribution.
- [ ] **P1** Privacy controls for friend count, joined groups and online presence.
- [ ] **P2** Profile themes with per-property safe CSS variable controls.
- [ ] **P2** User-designed profile layouts in contained, sanitized blocks.
- [ ] **P2** Milestone/anniversary display with user-controlled visibility.
- [ ] **P2** Rich bio fields with plain text, no executable markup.
- [ ] **P2** Custom accent/motion accessibility preferences.
- [ ] **P2** Community endorsements with spam/farming safeguards.
- [ ] **P3** Verified contribution portfolio / public developer pages.
- [ ] **P3** Reversible profile history snapshots and time capsule.

## 3 — Friends, discovery, presence (18 items)

- [x] **P1** Friend requests only via friend code.
- [x] **P1** Incoming/outgoing friend approval and removal.
- [ ] **P1** Optional friend code rotation with safe invalidation.
- [ ] **P1** Block and mute member actions across all social surfaces.
- [ ] **P1** Presence states: online, in game, idle, invisible.
- [ ] **P1** Friend-list sorting by last active, recent chat or favorites.
- [ ] **P1** Grouped friend lists for PS5, Switch, handhelds and cross-platform friends.
- [ ] **P1** Private friend notes visible only to the note author.
- [ ] **P1** Consent-based “now playing” status with user privacy presets.
- [ ] **P1** Pending friend request expiry and request rate limits.
- [ ] **P2** Mutual friends count without exposing hidden accounts.
- [ ] **P2** Optional shareable invite links that rotate and expire.
- [ ] **P2** Connection suggestions based on shared joined Sub-Relays, opt-in only.
- [ ] **P2** Friends' birthdays/anniversaries with display opt-in.
- [ ] **P2** Favorite friends presence widget.
- [ ] **P2** Friends currently available for co-op or spectator sessions.
- [ ] **P3** Cross-device presence state reconciliation.
- [ ] **P3** Optional friend-of-friend introductions with double consent.

## 4 — Feeds, posts, images, comments (20 items)

- [x] **P1** Private/friends-only text and image posts.
- [x] **P1** Home feed using actual friendship privacy filters.
- [ ] **P1** Comments with nested replies, collapse and @mentions.
- [ ] **P1** Reactions that distinguish appreciation from popularity farming.
- [ ] **P1** Edit history with timestamps and reason field.
- [ ] **P1** Post bookmarking and saved collections.
- [ ] **P1** Draft autosave in browser and optional synced drafts.
- [ ] **P1** User reports on posts, images and comments.
- [ ] **P1** Image carousel with keyboard/touch navigation and captions.
- [ ] **P1** Sort feeds by latest, friends, watched groups, saved.
- [ ] **P1** Pinned community announcements and moderator notes.
- [ ] **P1** Content warnings and click-to-reveal image previews.
- [ ] **P2** Link previews generated server-side with SSRF protection.
- [ ] **P2** Hashtags/topic tags with clear provenance.
- [ ] **P2** Thread subscriptions and replies notifications.
- [ ] **P2** Poll posts with anti-multiple-vote rules.
- [ ] **P2** Scheduled publishing for group announcements.
- [ ] **P2** Lightweight Markdown posts with strict sanitation.
- [ ] **P3** Thread digest feeds and monthly highlight collections.
- [ ] **P3** Repost with attribution and consent controls.

## 5 — Sub-Relays: topic communities (20 items)

- [ ] **P1** Create a Sub-Relay with unique slug, short name and description.
- [ ] **P1** Sub-Relay home: header, pinned rules, recent posts and member count.
- [ ] **P1** Join/leave and owner membership list.
- [ ] **P1** Sub-Relay discussion threads with titles, body and privacy.
- [ ] **P1** Moderator role scoped to an individual Sub-Relay.
- [ ] **P1** Moderator removal of posts with reason and audit event.
- [ ] **P1** Temporary and permanent Sub-Relay member bans.
- [ ] **P1** Member appeal route for a Sub-Relay ban.
- [ ] **P1** Report queue and trusted moderator triage per Sub-Relay.
- [ ] **P1** Community rules, sidebar guide and FAQs.
- [ ] **P1** Join settings: open, request to join, invite-only.
- [ ] **P1** Sub-Relay discovery by categories and accessible keyword search.
- [ ] **P2** Thread flair labels with moderator-controlled vocabulary.
- [ ] **P2** Sub-Relay events calendar and co-op matchmaking.
- [ ] **P2** Moderator task assignment and shared notes.
- [ ] **P2** Sub-Relay knowledge base / sticky curated guides.
- [ ] **P2** Cross-community posts with original-source attribution.
- [ ] **P2** Sub-Relay themed covers within site-safe design constraints.
- [ ] **P3** Federated community bridges with abuse containment.
- [ ] **P3** Community import/export with moderation history.

## 6 — Moderation, admin, abuse handling (22 items)

- [ ] **P0** Hidden, account-bound administrator role with server-side authorization.
- [ ] **P0** Non-public admin dashboard accessible only to authorized staff sessions.
- [ ] **P0** Admin user table excluding password hashes, tokens and secrets.
- [ ] **P0** Create/remove moderator by exact friend code, with audit log.
- [ ] **P0** Audit timestamps for joins, role edits, bans, warnings and awards.
- [ ] **P0** Rate limit and duplicate/repeated-content spam detection.
- [ ] **P0** Profanity and targeted-harassment reporting with context.
- [ ] **P0** Staff-only alert inbox for spam, harassment and banned-content hits.
- [ ] **P0** Appeals and reversible moderation decisions.
- [ ] **P0** Prevent moderators from editing or banning administrator accounts.
- [ ] **P1** Mod warning templates and written reasons.
- [ ] **P1** Moderator ability to delete posts and restrict groups, scoped per permissions.
- [ ] **P1** Temporary suspension and permanent ban with expiry if applicable.
- [ ] **P1** Human review before serious automated enforcement.
- [ ] **P1** Moderator escalation button and internal discussion thread.
- [ ] **P1** Staff handoff notes and triage queue ordering.
- [ ] **P1** New-member admin toast plus persistent notification history.
- [ ] **P1** Filterable admin analytics by date and event category.
- [ ] **P1** Trust, safety and content-report SLA metrics.
- [ ] **P2** Personalized word-monitor policy by Sub-Relay.
- [ ] **P2** Crisis and imminent-threat escalation playbooks.
- [ ] **P3** Transparent, privacy-preserving moderation transparency reports.

## 7 — Trophies, badges, awards, reputation (22 items)

- [x] **P1** 300 authored trophy definitions in a structured catalog.
- [x] **P1** 300 separate staff-grantable badge definitions.
- [x] **P1** Deterministic 20×15 trophy sprite sheet in PNG and SVG.
- [x] **P1** Deterministic 20×15 badge sprite sheet in PNG and SVG.
- [x] **P1** Profile trophy display API with only already-earned records.
- [ ] **P1** Seed award catalog into the production database through versioned migration.
- [ ] **P1** Event-driven award engine with transaction-safe idempotent unlocks.
- [ ] **P1** Unlock toasts with award art and accessible motion preferences.
- [ ] **P1** Bronze, silver, gold, platinum and mythic tiers with consistency checks.
- [ ] **P1** Hidden achievements revealed only after legitimate unlock.
- [ ] **P1** Staff award grants requiring a reason and audit record.
- [ ] **P1** Staff award revocation and appeal history.
- [ ] **P1** Featured badge displayed above profile avatar when awarded.
- [ ] **P1** Trophy cabinet with category and rarity filtering.
- [ ] **P1** Unique contribution recognition that cannot be bought or mass farmed.
- [ ] **P1** Anti-award-farming limits and staff conflict-of-interest policy.
- [ ] **P2** Trophy progress bars based on real measured events.
- [ ] **P2** Community-specific trophies and Sub-Relay milestones.
- [ ] **P2** Seasonal collection themes with archival history.
- [ ] **P2** Rare contributor showcase with user privacy controls.
- [ ] **P3** Offline trophy synchronization for native Relay clients.
- [ ] **P3** Trophy export cards that do not reveal hidden IDs or private activity.

## 8 — Messaging, notifications, alerts (18 items)

- [x] **P1** Friend-only DM conversation support.
- [x] **P1** Non-friend DM request inbox and acceptance path.
- [ ] **P1** Server-side new-user events for admin/mod toast alerts.
- [ ] **P1** Notification history with read/unread and persistent timestamps.
- [ ] **P1** Browser notifications after opt-in and permissions.
- [ ] **P1** Push notifications for mobile with per-event controls.
- [ ] **P1** @mention notifications with mute/block enforcement.
- [ ] **P1** Separate high-priority security alert channel.
- [ ] **P1** Group moderator report notifications.
- [ ] **P1** DM typing indicators and expiring ephemeral presence.
- [ ] **P1** Message delivered/read state with privacy options.
- [ ] **P1** Delete-for-self and delete-for-all within a short window.
- [ ] **P2** Short voice messages with audio safety scans.
- [ ] **P2** Group DMs with participant approval.
- [ ] **P2** Conversation pin/mute/archive.
- [ ] **P2** Notification digest email with verified opt-in.
- [ ] **P2** Friends' trophy-earned notifications.
- [ ] **P3** Optional encrypted end-to-end DM protocol after external review.

## 9 — Social stats, analytics, anniversaries (16 items)

- [ ] **P0** Server-derived new account registration event with timestamp.
- [ ] **P0** Active user counts with bot-filtered intervals.
- [ ] **P0** Staff analytics for total users, new joins, DAU/WAU/MAU.
- [ ] **P1** Profile anniversary timer based on account creation timestamp.
- [ ] **P1** New-member anniversary badges at 30, 90, 180, 365 days.
- [ ] **P1** Stream request and consent event timeline.
- [ ] **P1** User reports and moderation response-time statistics.
- [ ] **P1** Trophy unlock and contributor award totals.
- [ ] **P1** Sub-Relay membership and engagement analytics.
- [ ] **P1** Server health, queue depth and storage stats in admin view.
- [ ] **P2** Cohort retention trends with minimum anonymity thresholds.
- [ ] **P2** Region aggregates with privacy-protecting bins.
- [ ] **P2** Post and comment activity time series.
- [ ] **P2** Moderation effectiveness dashboard.
- [ ] **P2** Annual user “Relay Year in Review” card, opt-in.
- [ ] **P3** Capacity planning and per-platform media traffic forecast.

## 10 — PS5 profile pairing, console host, spectate (22 items)

- [x] **P1** Single-use, expiring account-side PS5 pairing code API.
- [x] **P1** Local PS5 setup QR simulator with streaming disabled.
- [x] **P1** Explicit friend-controlled stream permission request and revocation records.
- [ ] **P0** Actual PS5-compatible host payload with selected firmware validation.
- [ ] **P0** Consent-first, on-screen live capture indicator and immediate stop.
- [ ] **P0** Host keypair provisioning and mutual device authentication.
- [ ] **P0** Owner-confirmed per-profile link/re-link and orphaned-device recovery.
- [ ] **P0** Browser-safe WebRTC signaling with short-lived viewer tickets.
- [ ] **P0** Private TURN/SFU media transport and gateway authentication.
- [ ] **P0** Hard stop on logout, sleep, account switch or viewer revocation.
- [ ] **P1** Read authorized local PS5 name/avatar metadata.
- [ ] **P1** PS5→Social profile metadata change notifications.
- [ ] **P1** Website avatar→PS5 queued changes only after verified safe API.
- [ ] **P1** Native video encoder and audio-mixer capture feasibility benchmark.
- [ ] **P1** Cloudflare outbound control connection with reconnect/backoff.
- [ ] **P1** Spectate request page and viewer approval queue.
- [ ] **P1** Stream quality presets, bitrate and network diagnostics.
- [ ] **P1** Friends-only and explicit temporary-public access policies.
- [ ] **P2** Multi-viewer adaptive broadcasting with server bandwidth ceilings.
- [ ] **P2** Scheduled co-op sessions / invitations through Relay friends.
- [ ] **P2** Real-time stream chat with viewer consent.
- [ ] **P3** User-operated self-hosted tunnel advanced mode.

## 11 — Relay Remote Play and client capabilities (20 items)

- [x] **P1** Eight Android build flavors using one shared codebase.
- [x] **P1** Switch application-mode NSP and Vita VPK native preview builds.
- [x] **P1** Signed-manifest Android update-check and user-approved APK installer.
- [ ] **P0** Verified PS5 streaming video, audio and controller return on Vita.
- [ ] **P0** Verified PS5 streaming video, audio and controller return on Switch.
- [ ] **P0** Fix Switch Eden performance regression and native Atmosphère hardware test.
- [ ] **P0** Fix Vita3K graphics surface behavior with post-build frame captures.
- [ ] **P1** Switch and Vita quick-menu parity with canonical Android.
- [ ] **P1** Original Relay menu cue playback verified on native Vita/Switch.
- [ ] **P1** AYN Thor independent lower-screen input/focus on hardware.
- [ ] **P1** Quest 3S real-device 2D panel/controller/audio testing.
- [ ] **P1** Native OpenXR immersive Quest theater mode.
- [ ] **P1** Native SteamOS Remote Play decoder, not external Chiaki launcher only.
- [ ] **P1** Signed VPK/NSP/Steam Deck update installer per platform.
- [ ] **P1** End-to-end stream latency and dropped-frame telemetry.
- [ ] **P2** HDR display negotiation and local tone mapping.
- [ ] **P2** Spatial FSR 1 upscaling with independently licensed shader implementation.
- [ ] **P2** Motion interpolation to 120 Hz with frame pacing diagnostics.
- [ ] **P2** Gyro/DualSense haptics/adaptive-trigger feedback on supported hardware.
- [ ] **P3** Experimental safe dual-screen/extended UI and spectator view.

## 12 — Translation, accessibility and localization (18 items)

- [x] **P1** Separate Japanese image, video, speech and English TTS Android test controls.
- [x] **P1** Experimental Natural Subtitle Replace using inpainting/temporal pixels.
- [ ] **P0** Japanese offline ASR model installation and verified English dubbing on Samsung.
- [ ] **P1** Text mask refinement to remove residual Japanese glyphs without smearing.
- [ ] **P1** 60-second reference video with locked provenance and acceptance suite.
- [ ] **P1** Configurable Japanese audio mute with independent decode pipeline.
- [ ] **P1** Translation source confidence, segment timestamps and latency.
- [ ] **P1** On-device English voice selection with sample preview.
- [ ] **P1** Caption safe-area layout across 16:9, ultrawide and foldables.
- [ ] **P1** Screen reader labels for every social UI action and achievement.
- [ ] **P1** Keyboard-only navigation across mobile, browser and native menu.
- [ ] **P1** WCAG AA text contrast and error feedback audit.
- [ ] **P1** Screen motion reduction and zero-animation preference.
- [ ] **P2** Multilingual UI with translated strings and plural handling.
- [ ] **P2** Text-size scaling without truncating trophies or usernames.
- [ ] **P2** Hearing accessibility: visual indicators of menu cues.
- [ ] **P2** Profile content alt-text prompts and automatic image metadata stripping.
- [ ] **P3** Translation settings synchronized across linked Relay clients.

## 13 — Search, discovery, events and content quality (16 items)

- [ ] **P1** Search users by friend code without revealing private email or IDs.
- [ ] **P1** Search Sub-Relays by title, category and rules.
- [ ] **P1** Search posts limited to viewer-authorized content.
- [ ] **P1** Filter trophies by earned, locked, secret and contribution type.
- [ ] **P1** Tag verified setup guides and technical how-to posts.
- [ ] **P1** New-user onboarding checklist with meaningful milestones.
- [ ] **P1** Discover Sub-Relay welcome threads and required rules acknowledgement.
- [ ] **P1** Spam-resistant quality filters for community discovery.
- [ ] **P2** Game library index with PS5 titles, artwork license compliance.
- [ ] **P2** Hardware compatibility articles with tested firmware/version dates.
- [ ] **P2** Community poll calendar and event RSVP.
- [ ] **P2** Structured reviews and reproducible bug reports.
- [ ] **P2** User-maintained FAQ with moderator-approved revisions.
- [ ] **P2** Saved cross-platform testing recipes.
- [ ] **P3** Personalized topic ranking with explainable controls.
- [ ] **P3** Opt-in digest of content from followed friends and groups.

## 14 — Operations, deployment and reliability (20 items)

- [x] **P0** Cloudflare HTTPS tunnel routes for social/update primary domains.
- [x] **P0** Local BEN-PC website and update origin health endpoints.
- [ ] **P0** Production email sender/domain reputation and verified email rollout.
- [ ] **P0** Secure audited secret manager and encrypted backup/recovery drill.
- [ ] **P0** Reboot/reconnect testing of BEN-PC Windows scheduled tasks.
- [ ] **P0** Staging environment with separate database and Cloudflare ingress.
- [ ] **P0** Independent external security review before broader public beta.
- [ ] **P0** No-leak tests for MAC addresses, passwords, tokens and pairing IDs.
- [ ] **P0** CSP and CSRF regression tests for staff, profile and moderation routes.
- [ ] **P1** Uptime and failure alerts to admin (without secrets in logs).
- [ ] **P1** Database migration tooling with reversible backups.
- [ ] **P1** User content/image quota and garbage collection.
- [ ] **P1** Immutable security audit logs with limited retention.
- [ ] **P1** Synthetic login/feed/trophy/admin smoke tests after each deploy.
- [ ] **P1** Signed update publish/rollback workflow across all platforms.
- [ ] **P1** Device-specific UI screenshot comparisons after any feature change.
- [ ] **P2** CDN asset revisioning and cache-busting.
- [ ] **P2** Web app offline shell with no stale private-data caching.
- [ ] **P2** API versioning/compatibility policy for old native clients.
- [ ] **P3** Regional failover and managed infrastructure migration plan.

## 15 — Design craft and user experience (16 items)

- [x] **P1** Actual Relay logo in social site navigation and sign-in screen.
- [x] **P1** PlayStation-inspired dark navy and blue three-column social shell.
- [x] **P1** User profile hero, activity and trophy regions with distinct hierarchy.
- [x] **P1** Public-identity preview with real friend/visitor visibility rules.
- [ ] **P1** Apply a documented product-specific typography/spacing/radius scale.
- [ ] **P1** Reduce generic repeated card geometry and decorative gradients.
- [ ] **P1** Design real post, comment, group, moderation and notification states.
- [ ] **P1** Remove decorative controls without real actions.
- [ ] **P1** Mobile QA at 375, 430, 768 and 1024 CSS pixels.
- [ ] **P1** Keyboard focus flow and persistent mobile navigation.
- [ ] **P1** Loading skeletons used only where actual async data exists.
- [ ] **P1** Error copy tied to specific failed actions and recovery options.
- [ ] **P2** Trophy component motion design respecting reduced-motion setting.
- [ ] **P2** Reusable card, feed, avatar, profile and moderation component tokens.
- [ ] **P2** Visual QA snapshots for all pages, logged-in/out and empty/full states.
- [ ] **P3** User-led theme voting with accessibility gates.

## 16 — Community governance, trust, sustainability (12 items)

- [ ] **P0** Public community rules, privacy policy and terms of service.
- [ ] **P0** Abuse reporting contacts and legal complaint procedure.
- [ ] **P0** Moderator conflict-of-interest and code-of-conduct policy.
- [ ] **P1** Transparent community decision log without member private data.
- [ ] **P1** Community feedback board with tracked decisions.
- [ ] **P1** Accessibility feedback and issue tracking.
- [ ] **P1** Volunteer moderator training and safe onboarding.
- [ ] **P1** Data retention and deletion schedule for logs, uploads and DMs.
- [ ] **P2** User-experience research sessions with consensual participant feedback.
- [ ] **P2** Release notes with what changed, what broke and known limitations.
- [ ] **P2** Community-maintained translations and documentation review.
- [ ] **P3** Self-sustaining funding plan that never sells private user data.

---

## Implementation sequencing

**Release gate A — identity and profile:** make the `/u/:slug` profile UI pass visitor/friend privacy tests; finish account verification and recovery; protect staff role actions before public admin UI.

**Release gate B — communities and moderation:** land Sub-Relays, reports, warnings, scoped moderator bans and staff notification history with auditability. Implement spam/profanity checks as **triage alerts**, not automatic punitive decisions.

**Release gate C — achievements:** seed the 600-author catalog, implement event-driven unlocks and staff award grants/revocation, then display earned badges above avatars and in trophy cabinets. Sprite sheets exist, but most award events are not yet wired.

**Release gate D — PS5 host:** separately prove ownership consent, real video/audio capture, WebRTC viewer authorization and revocation on supported jailbroken PS5 firmware before advertising live spectating.

**Release gate E — cross-platform quality:** Switch/Vita rendering and menu fixes, end-to-end stream audio/input, AYN Thor, Quest and Steam Deck device tests. Every Android-origin feature propagates or receives an explicit documented blocker.

## Audit references

- [Design guidance](research/design-skills/) — locally cloned GitHub repositories. Read relevant docs only; never execute unaudited install scripts.
- [Social roadmap](docs/RELAY_SOCIAL_ROADMAP.md), [PS5 host specification](docs/PS5_HOST_SPECTATE_SPEC.md), [network](network.md), [platform parity](docs/RELAY_SWITCH_VITA_ANDROID_PARITY.md).
- [Trophy sprite generator](services/relay-social/tools/build_awards.py) and [existing catalog](services/relay-social/public/awards/catalog.json).

**Review cadence:** after each completed feature, update this file, the parity master, tests, and a real browser/device screenshot. Do not run a never-ending development loop or claim background work without a scheduled, monitored task.

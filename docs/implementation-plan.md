# Android MCP - approved design and implementation plan

Goal: control the owner's Android phone remotely without root or ADB, including files via APIs without UI navigation. User approved implementation September 8, 2026.

Architecture: native Android companion (Android 11+) with explicitly enabled AccessibilityService, screenshots and gestures, plus Storage Access Framework persistent user-selected directory grants. Node.js MCP stdio bridge on agent PC talks to authenticated Android endpoint over private Tailscale network. No public relay or Funnel. Setup requires manual installation and permission grants only. No bypass of lock screen, secure screenshots, private app storage, or Android permissions.

## Contract and tasks
- [ ] Android native UI: explain permissions, enable Accessibility, select/revoke SAF roots, start/stop remote service, show connection address, generate and rotate high-entropy secret. Never log secret or file contents. Clear connection state and stop server on revocation. No automatic hidden startup.
- [ ] Remote API: authenticated bounded JSON requests; health/status, UI hierarchy, screenshot, tap/long press/swipe/scroll/text, global actions and launch application. Strict operation allowlist, timeouts, bounds, bounded outputs. Screenshot returns image suitable for MCP image content. Password nodes must not expose their contents.
- [ ] File API independent of Accessibility: roots/list/stat/read (bounded range), plus explicitly requested optional file writes only if implemented safely; user scope is navigation/read. Use granted root plus relative path, reject traversal, arbitrary URI access and ungranted roots. Directory grants survive restart; revocation takes effect immediately. Do not request all-files access by default.
- [ ] Node MCP SDK bridge: schemas, helpful tool descriptions, no stdout diagnostics, config from environment, private remote endpoint restriction, HTTP timeouts, response limits, error propagation. Distinguish UI-service disabled from file capability. Provide config template without real credentials.
- [ ] Deliver Windows build/run scripts, lockfiles and Gradle wrapper, APK if local SDK permits. Italian README: setup via APK download without adb; Tailscale on both peers; permissions; background limits; locked-screen limitations; usage examples.
- [ ] Runnable tests: auth missing/wrong, unknown operation, invalid gesture, root escape, unknown root, bounded reads, remote disconnect, MCP discovery and request routing against local fixture. Fixture results must not be described as physical phone evidence.
- [ ] Run Node checks and Android build/lint where available; record exact results and blockers. Parent inspects all code and reruns tests, then independent read-only review.

Ownership: worker owns implementation under this folder only. Parent owns docs/acceptance.md and independent verification. Do not change CRM or other projects. No commits, push, public deployment, host security changes, credential printing or ADB required. Keep smallest complete implementation, not scaffolding or TODOs.

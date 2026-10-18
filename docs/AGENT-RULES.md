# Rules for agents working on MFBooks (read fully before starting)

Project: /home/mrfrederic/Projects/MFBooks — Android local-files audiobook player. Kotlin 2.4, Compose BOM 2026.09 + material3 1.5.0-beta01 (Material 3 Expressive), navigation-compose, Coil 3, material-kolor, Media3 1.11, Room, DataStore, WorkManager. Spec: docs/MFBooks-Design.md. Each agent works in its OWN git worktree (copy `local.properties` from /home/mrfrederic/Projects/MFBooks into it first; it is gitignored). Stay inside your brief's scope; commit your work on your branch when done (small commits OK). The coordinator merges.

## Contracts (read, don't break)
- data/Entities.kt, data/AppDatabase.kt (additive DAO queries OK — report them), data/Prefs.kt (Settings; `prefs.update { it.copy(...) }`). New prefs since last round: AutoRewind.AUTO (default), EndOfBook.SHOW_LIBRARY, Settings.lockControls.
- playback/PlayerConnection.kt: NowPlaying state + control methods; new `bookEnded: SharedFlow<String>`.
- library/LibraryScanner.kt public API; library/Parsing.kt contract.

## Build (sandbox: no network, read-only /tmp)
`cd <your worktree> && JAVA_TOOL_OPTIONS="-Dorg.sqlite.tmpdir=$TMPDIR -Djava.io.tmpdir=$TMPDIR" ./gradlew --offline -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebug --console=plain -q`
Unit tests: same with `:app:testDebugUnitTest` (must stay green). New dependencies need one unsandboxed (online) build to resolve. Never run `./gradlew --stop`.

## Emulator
- Each agent gets at most ONE emulator, named by serial in its brief, and touches no other device. `emulator-5554` = AVD mfbooks_api34; `emulator-5556` = AVD material34 (boot it yourself: `~/Android/Sdk/emulator/emulator -avd material34 -port 5556 -no-snapshot-save -no-boot-anim &` unsandboxed, then `timeout 180 adb -s emulator-5556 wait-for-device`). A physical phone may be attached: never install to or touch it. Never uninstall the app or clear its data on emulator-5554.
- No emulator named in your brief = code-only (compile + unit tests). Never use another agent's emulator, not even for a screenshot.
- adb/emulator commands MUST run outside the Bash sandbox (dangerouslyDisableSandbox: true) — inside it adb starts an isolated server that can't see the emulator and hangs. In zsh: `A=(~/Android/Sdk/platform-tools/adb -s emulator-5554)`.
- Wrap every wait in `timeout` (e.g. `timeout 120 ...`); no unbounded loops, no bare `wait-for-device`. Don't leave background commands running when you finish.
- To install: `$A install -r app/build/outputs/apk/debug/app-debug.apk`. Screenshots: `$A exec-out screencap -p > $TMPDIR/x.png`, view with the Read tool (downscale with ffmpeg if large).
- Library on the emulator: /sdcard/Audiobooks (already a root) — the owner's real books (m4b with chapters, .nfo/.cue, EmbeddedCover.jpg, Smart AudioBook Player files position.sabp.dat, "!Smart AudioBook Player Statistics/statistics.xml"), plus small generated test books. Host copy (read-only reference, NEVER modify): /home/mrfrederic/Projects/MFBooks/.tmp/Audiobooks.

## Style
Concise idiomatic Kotlin, no DI, no needless layers, `ponytail:` comments for deliberate shortcuts. Real M3 Expressive components (check signatures in the material3 jar under ~/.gradle/caches/modules-2/files-2.1/androidx.compose.material3/). Strings may be hardcoded English. Clean-room: no code from the Smart AudioBook Player APK; NomaTune (GPL) is reference-only, never copy its code.

## Final report
Short: what changed, files, DAO/API additions, screenshots taken (paths), what's not done.

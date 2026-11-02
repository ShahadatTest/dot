## What and why

<!-- What changed, and the issue it fixes (Fixes #n). -->

## Impact and blast radius

<!-- Who notices, and what breaks if this is wrong: playback, saved data (playlists, stats), sound, UI only, build/CI. Can it be reverted cleanly? -->

## How it was tested

<!-- Phone and Android version, what you tried. Note anything you couldn't test. -->

## Checklist

- [ ] `./gradlew lintRelease testDebugUnitTest assembleRelease` passes
- [ ] Tested on a real phone (Android 10 too if you touched newer APIs)
- [ ] No new permissions, network access, exported components or dependencies, or they're explained above
- [ ] Saved data stays compatible, or the migration is described above
- [ ] Made with an AI agent? Followed [AGENTS.md](../AGENTS.md) and said so above

# Fork Maintenance

This fork keeps the Cockpit-specific auth, updater, and release workflow changes on top of the upstream NuvioTV codebase.

## Release artifacts

- `app-full-universal-release.apk` stays available for broad compatibility, but it will always be much larger because it bundles every ABI.
- `app-full-arm64-v8a-release.apk` is the recommended install for most Android TV devices.
- `app-full-armeabi-v7a-release.apk` is the fallback for older 32-bit devices.

## Upstream sync workflow

The fork should keep all Cockpit-specific work on `dev` and treat `tapframe/NuvioTV` as the upstream source.

### GitHub-first sync flow

1. `upstream-sync.yml` runs on a schedule and can also be started manually from Actions.
2. The workflow fetches `tapframe/NuvioTV` and merges `upstream/main` directly into `dev`.
3. The merge uses `git merge -X ours`, which means any conflicting hunk automatically keeps the Cockpit fork's side of the file.
4. Every successful sync push into `dev` triggers `android-release-build.yml`, which rebuilds the app automatically.
5. When you want a distributable release, push a version tag from `dev`. The tag-triggered release workflow will build the APKs and publish a GitHub Release.

This is the fully automatic path: upstream intake and app builds happen through GitHub without requiring a review PR. The tradeoff is that overlapping upstream edits are discarded on conflicting hunks so the Cockpit fork always wins those merges.

### Local fallback flow

1. Add the original repository once:

```bash
git remote add upstream https://github.com/tapframe/NuvioTV.git
```

2. Enable recorded conflict resolutions so repeat merges get easier:

```bash
git config rerere.enabled true
```

3. Pull upstream into an integration branch first, not directly into `dev`:

```bash
git fetch upstream
git checkout dev
git pull origin dev
git checkout -b upstream-sync upstream/main
git merge origin/dev
git merge upstream/main
```

4. Resolve conflicts there, run the release build, then merge the integration branch back into `dev`.

## Expected conflict areas

- `app/build.gradle.kts`: updater owner/repo, panel API config, release signing behavior.
- `app/src/main/java/com/nuvio/tv/core/auth/**`: Cockpit panel auth replaces the original cloud auth path.
- `app/src/main/java/com/nuvio/tv/ui/screens/account/**`: the sign-in UX and account settings are fork-specific.
- `.github/workflows/**`: this fork builds and uploads `fullRelease` APKs for Cockpit releases.

## CI notes

- `upstream-sync.yml` merges upstream directly into `dev` and auto-resolves conflicting hunks in favor of the Cockpit branch.
- `android-release-build.yml` verifies `:app:assembleFullRelease` on pushes, pull requests, manual dispatches, and release tags.
- `beta-release.yml` is the publish flow and now reads the actual `full/release` APK outputs.
- Pushing a tag such as `0.6.17-beta-cockpit` creates a GitHub prerelease automatically.

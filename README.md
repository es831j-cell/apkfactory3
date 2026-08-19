# APK Factory v2.3 Artifact Finder

Fixes the case where GitHub Actions reports a successful Android build but APK Factory says the APK artifact was not found.

Changes:
- Keeps secure on-device GitHub token storage.
- Keeps single-commit project upload.
- Waits up to about two minutes for artifact publication.
- Checks the exact workflow run first.
- Falls back to repository-wide Actions artifacts when multiple workflows run for the same commit.
- Prefers artifact names containing APK, Android, or Lumi.
- Downloads the artifact ZIP and extracts the first `.apk` as before.

Build package fix: includes gradlew/gradlew.bat required by APK Factory validation.

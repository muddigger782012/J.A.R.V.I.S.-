# Jarvis Keyboard updates

Signed Jarvis Keyboard APKs and the public latest.json update feed. This branch contains keyboard distribution files only.

Latest: version 1.9 (version code 10).

The app checks this feed when its Updates screen opens, downloads only newer versions, verifies APK SHA-256 and package signing identity, then requests automatic self-installation through Android PackageInstaller. Android's installation permission and confirmation fallback remain available.

No signing keys, clipboard content, or user data are stored in this branch. Future releases must retain versioned APK filenames and update latest.json only after signature and hash verification.

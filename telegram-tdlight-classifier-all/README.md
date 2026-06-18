# Telegram TDLight Classifier All

This module centralizes TDLight Java dependencies and mainstream native classifiers.
Application modules should depend on this module instead of declaring TDLight
dependencies directly.

Included dependencies:

- `it.tdlight:tdlight-java`
- `it.tdlight:tdlight-natives:linux_amd64_gnu_ssl3`
- `it.tdlight:tdlight-natives:linux_arm64_gnu_ssl3`
- `it.tdlight:tdlight-natives:linux_amd64_gnu_ssl1`
- `it.tdlight:tdlight-natives:linux_arm64_gnu_ssl1`
- `it.tdlight:tdlight-natives:windows_amd64`
- `it.tdlight:tdlight-natives:macos_arm64`

Runtime requirements still apply:

- Linux: glibc, zlib, and OpenSSL matching the selected native classifier.
- macOS: OpenSSL 3 installed and linked as required by TDLight.
- Windows: Microsoft Visual C++ Redistributable.

For Docker images, prefer Debian/Ubuntu based Java 21 images and install
`libssl3` plus `zlib1g`; then the Linux OpenSSL 3 classifiers are used.

If a smaller platform-specific artifact is needed later, replace this module
with another module using the same artifact id and a narrower classifier set.

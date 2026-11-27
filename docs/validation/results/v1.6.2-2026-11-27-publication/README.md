# MAVLab v1.6.2 publication verification

Published on 2026-11-27 at 16:54:44 UTC. Release source is
e412a9849d663651e1c5b5db544be05eb1acb6e4, version 1.6.2/code 25.
This receipt adds documentation after that tagged source.

[Public release](https://github.com/Labs-Ascend/MAVLab/releases/tag/v1.6.2) ·
[Release workflow](https://github.com/Labs-Ascend/MAVLab/actions/runs/46036652546).

## Verified publication

Android CI and the release workflow completed successfully for the release source.
The annotated tag object is 66358e6647a8ab31c3e91ffa5700b4db7072404b.
Canonical and personal remotes returned that same object and peeled source commit.

The unauthenticated latest-release API returned v1.6.2 as a public, non-draft,
non-prerelease release. Exactly two uploaded attachments are present,
MAVLab-v1.6.2.apk and MAVLab-v1.6.2.aab. Both workflow-built files were downloaded,
their SHA-256 values matched GitHub's asset digests, and their signatures matched
the permanent release certificate
46ff910b66597a7340d7b7737e598619368b8594bcdc2f05a91efb54d811eb70.
The APK is non-debuggable and reports version 1.6.2/code 25.

[Public metadata and asset digests](public-release.json) · [Downloaded checksums](SHA256SUMS) ·
[APK certificate](apk-certificate.txt) · [AAB certificate](aab-certificate.txt) ·
[AAB verification](aab-verification.txt) · [Workflow result](release-workflow.json).

## Installed phone identity

The connected Samsung Galaxy S10e, Android 12, already had the exact public APK.
Its installed hash matched the public APK digest. The only remaining MAVLab
package is com.ascend.mavlab. The QA/test packages were removed during the
previous requested cleanup. Its UID and original first-install time match
the earlier in-place update evidence. [Phone identity](phone-identity.json).

No further runtime, flight or QGC tests were run during publication, following
the maintainer's direction. Original simulation, renderer and service evidence
retains its recorded scope. Physical Android 8 GPU drivers remain untested.
Signing and publication do not establish Play Console acceptance.

Tester was Codex via GitHub CLI, SDK signing tools and ADB. Phone checks here
covered installed-package identity and APK bytes.

[Candidate preparation](../v1.6.2-2026-11-27-preparation/README.md) ·
[Service startup verification](../2026-11-27-foreground-service-startup/README.md).

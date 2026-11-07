# v1.6.1 publication and signed-update verification

Date: 2026-11-07 UTC. Tester: Codex on Ambrose's Linux workstation.
Release: [MAVLab v1.6.1](https://github.com/Labs-Ascend/MAVLab/releases/tag/v1.6.1),
Android code 24. Tagged source: `e9a29c82c425f089caa6a68d1232bee395dc615e`;
annotated tag object: `17fd5ca717f0c2b66a31d7e4aa0811b756676c31`.
This postpublication receipt does not move the tag or alter its artifacts.

## Public distribution

The canonical release was independently downloaded. It is public, neither draft
nor prerelease, with exactly the named APK and AAB. The body matches
`docs/v1_6_1_release_notes.md` at the tag. Both downloaded sizes and SHA-256 hashes
match GitHub's asset metadata; both signatures match the approved permanent
certificate `46ff910b66597a7340d7b7737e598619368b8594bcdc2f05a91efb54d811eb70`.
The APK identifies version 1.6.1/code 24 and is not debuggable.

- [Published artifact hashes and sizes](published-artifacts.json).
- [APK signature verification](apk-signature.txt).
- [AAB signature verification](aab-signature.txt).
- [Successful main CI, release, tag mirror and notes workflows](workflows.json).
- [Local host/signing gate](../v1.6.1-2026-11-07-prepublication/README.md).

Local and CI artifact hashes differ and are recorded separately. The CI APK/AAB
are the public distribution. No video, image, checksum or validation sidecar is
attached to the GitHub release. Both user-uploaded README video URLs were verified
against the compressed copies, as recorded in the
[compression report](../../../../assets/media/issue-upload/compression-report.json).

At publication, canonical and personal mirror `main` and peeled `v1.6.1` all
resolved to the source above; both annotated tag object IDs also matched. Only
this receipt and its index are committed after that release source. Documentation
checks are run for this receipt; the unchanged Android code retains the successful
tag/main CI evidence rather than requiring another Android build for these logs.

## Isolated signed-app update

Device: dedicated `MAVLab_v161_Release_Check` Android emulator, Android 15/API 35,
x86_64 Google APIs image `AE3A.240806.043/12960925`, emulator 36.6.11, KVM.
The image is root-capable for inspecting this test installation; the APK itself
remains non-debuggable. No physical phone was accessed, uninstalled or cleared.

1. Installed the locally signed release APK after Android completed boot. Granted
   notification permission for the fixture. Enabled airplane mode; connectivity
   reported no active default network and IPv4 routing was empty.
2. Opened onboarding, skipped it, and observed Cockpit, Control and the SIM HUD.
3. Rebooted with host graphics after the graphics failure below. Installed the
   independently downloaded public APK with `adb install --no-streaming -r`.
4. Verified the installed base APK hash equals the public download. App UID 10209,
   both existing preference files and `onboarding_complete=true` were preserved
   byte-for-byte through installation. No app-data directory was removed.
5. Launched the published app offline: `am start -W` returned `Status: ok`;
   completed onboarding remained skipped, Cockpit opened, and Ops showed a
   foreground runtime, disarmed/Idle state, recording stopped and GCS disconnected.
   The final AndroidRuntime error query returned no app-fatal entries.

[Exact update/data checks](update-data-verification.json) ·
[Published launch](published-launch.txt) · [Cockpit text](published-cockpit.txt) ·
[Ops text](published-ops.txt).

This proves a same-version replacement between two builds using the new identity
and retention of these two fixture files. It is not an upgrade from an older debug
certificate, a future version migration or comprehensive recording/photo retention.

## Infrastructure failures and qualification limits

The first shared-AVD cold boot restarted Android's system server. It was stopped
without installing MAVLab. The new dedicated AVD completed its initial boot in
553 seconds. One install attempted before Android's services were ready failed
inside PackageInstaller/StorageManager; installing after boot succeeded. Cold-boot
System UI ANR dialogs were dismissed with Wait. UIAutomator sometimes returned a
null root during transitions; stale dumps were discarded for final observations.

The SwiftShader emulator host process exited 139 after SIM/transition to Ops;
the host kernel recorded a RenderThread segmentation fault at 21:11:17 EAT.
The app's SIM screenshot showed a HUD/grid, not a qualified 3D-model rendering.
The dedicated AVD was restarted with host graphics and booted in 74 seconds;
publication install/data, Cockpit and Ops checks then completed. Full 3D behavior,
emulator graphics stability, low-end performance and battery use remain unqualified.

No additional mission or QGC run was performed, following the maintainer's request.
Earlier Samsung/QGC checks remain tied to their development artifacts. APK/AAB
signing and publication do not establish Play acceptance, full MAVLink equivalence
or real-aircraft qualification. Impact rows and prior implementation evidence are
reviewed in the linked prepublication record; no runtime code changed after it.

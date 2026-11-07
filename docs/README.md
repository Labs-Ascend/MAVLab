# MAVLab Documentation

MAVLab documentation is organized by reader intent rather than by the order in which features were built.

Status: Active index
Owner: Ascend Labs
Last reviewed: 2026-11-07 (v1.6.1)

## I want to use MAVLab

User documents describe observable behavior without requiring source-code knowledge. The four-surface guides describe the current layout; v1.6.1 adds automatic simulated captures and removes the Ops camera setup card. Earlier v1.5.3 installations have the previous layout. Signing and qualification limitations remain explicit in versioned release notes.

- [User documentation home](user/README.md)
- [Setup and first run](setup_guide.md)
- [Known first-run issues](known_first_run_issues.md)
- [Installing or upgrading to v1.6.1](user/upgrading.md)
- [Teacher guide](teacher_guide.md)
- [v1.6.0 demonstration script](v1_6_demo_script.md)

## I need exact technical behavior

- [Internals home](internals/README.md)
- [Architecture overview](internals/overview.md)
- [Runtime lifecycle](internals/runtime-lifecycle.md)
- [Simulation model](internals/simulation-model.md)
- [Control-authority contract](internals/control-authority.md)
- [MAVLink/QGroundControl contract](internals/mavlink-contract.md)
- [Glossary](internals/glossary.md)
- [Architecture decisions](internals/decisions/README.md)
- [Documentation governance](internals/documentation-governance.md)

Existing files such as [architecture.md](architecture.md), [protocol_guardrails.md](protocol_guardrails.md), and [ardupilot_compatibility.md](ardupilot_compatibility.md) remain migration source material. Where prose disagrees with code and focused tests, record the contradiction and correct the documentation; do not silently invent behavior.

## I am contributing

- [Contributor and agent guide](../AGENTS.md)
- [Contributing policy](../CONTRIBUTING.md)
- [Android implementation README](../mavlab-android/README.md)
- [Validation model](validation/README.md)

## I am releasing or validating MAVLab

- [Operations home](operations/README.md)
- [Release runbook](operations/release.md)
- [v1.6.0 release disposition and readiness](operations/v1.6.0-readiness.md)
- [v1.6.1 release notes](v1_6_1_release_notes.md)
- [v1.6.0 release notes](v1_6_0_release_notes.md)
- [v1.6.0 acceptance specification](validation/v1.6.0-acceptance.md)
- [New media, captions and checksums](../assets/media/2026-10/README.md)
- [Validation and evidence](validation/README.md)
- [QGC acceptance specification](v1_5_qgc_acceptance.md)
- [v1.5.3 release notes](v1_5_3_release_notes.md)
- [Earlier release notes](v1_5_2_release_notes.md)

A test specification is not a test result. A result record must identify the commit/tag, app build, device, Android version, QGC build, tester, date, and evidence.

## I am reviewing research or history

- [Technical paper source](paper/paper.md)
- [Paper build notes](paper/README.md)
- [Archive policy](archive/README.md)

The published paper describes an evaluated release snapshot. Later product changes should use a versioned revision or addendum rather than silently rewriting historical evidence.

## Canonical-source rule

- Public product behavior and technical architecture: this repository.
- Company strategy, ownership, private operating context, and active decision tracking: Ascend’s Obsidian vault.
- Implementation history: Git commits, pull requests, issues, and releases.
- Published research claims: the versioned paper and DOI release.

The vault may link to repository documentation, but it should not privately redefine technical behavior that contributors cannot access.

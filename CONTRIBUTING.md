# Contributing to MAVLab

Thanks for your interest in MAVLab — a phone-first drone digital-twin simulator built by Ascend Labs (Ascend Drone Technologies Ltd., Nairobi).

## Orientation

MAVLab runs the quadcopter simulation on Android and exposes supported MAVLink workflows to QGroundControl. The technical paper documents the original design and evaluated release.

- **Contributor and agent guide:** `AGENTS.md`
- **Documentation home:** `docs/README.md`
- **Architecture overview:** `docs/internals/overview.md`
- **MAVLink contract:** `docs/internals/mavlink-contract.md`
- **Paper (DOI):** https://doi.org/10.5281/zenodo.21319694
- **In-repo paper source:** `docs/paper/paper.md`

Read `AGENTS.md` first for MAVLab’s non-negotiables, project vocabulary, hazards, impact matrix, and verification defaults. Read the paper’s §4 and §6 for the historical phone-first and protocol-first rationale.

## Building

```bash
cd mavlab-android
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew lintDebug testDebugUnitTest assembleDebug
```

See `docs/setup_guide.md` for the full environment setup. Prebuilt APKs live on the [releases page](https://github.com/Labs-Ascend/MAVLab/releases).

## Reporting issues

- **Bugs** → use the "Bug report" template.
- **Can't get connected on first try?** → use the "First-run problem" template, and check `docs/known_first_run_issues.md` first.

## Code of conduct

Be kind. People come to MAVLab with different levels of simulation experience. Assume good faith and explain the reason behind a decision.

## License

Apache License 2.0. By contributing you agree your contributions are licensed under the same.

## Commit attribution

Use the actual human contributor's Git identity and preserve genuine human co-author credits. Do not append Claude `Co-authored-by` or `Claude-Session` trailers. The maintainer-approved [2026-10-12 attribution cleanup](docs/validation/results/2026-10-12-attribution-rewrite/README.md) records the source-preserving history rewrite and old/new commit mapping.

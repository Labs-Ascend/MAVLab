# MAVLab User Documentation

This section is for learners, instructors, operators, and evaluators using the shipped application.

User documents should:

- explain observable behavior in product language;
- avoid private filesystem paths and contributor-only commands;
- identify the MAVLab version when behavior is version-specific;
- distinguish phone-only use from QGroundControl-connected use;
- explain how to enter, observe, and leave operational states.

## Start here

- [Install and first run](../setup_guide.md)
- [Known first-run issues](../known_first_run_issues.md)
- [Upgrade and retained data](upgrading.md)
- [Automatic simulated captures and earlier map photos](virtual-camera.md)
- [v1.6.1 release notes](../v1_6_1_release_notes.md)
- [v1.6.0 release notes](../v1_6_0_release_notes.md)
- [Teacher guide](../teacher_guide.md)
- [v1.6.0 demonstration](../v1_6_demo_script.md)
- [v1.5.3 release notes](../v1_5_3_release_notes.md)
- [Earlier release notes](../v1_5_2_release_notes.md)

## Product surfaces

These surface guides describe the current layout. v1.6.1 adds automatic simulated captures and removes the Ops camera setup card. Earlier v1.5.3 installations retain the previous navigation. See the upgrade guide for the new permanent certificate's migration requirements.

1. **[Cockpit](cockpit.md)** — live flight state, authority and contextual recovery.
2. **[Control](controller.md)** — phone/manual input and conditional received-mission actions.
3. **[SIM](sim.md)** — engine-driven 3D vehicle and received-route spatial view.
4. **[Ops](ops.md)** — connectivity, upload evidence, failures, logs and setup.

[Mission operation](mission.md) spans these surfaces; it is not a separate destination. Lesson and Labs code remains internal and is not active primary navigation.

See [Navigation and display](navigation-and-display.md) for compact tabs, guarded swiping, and Ops → Display → Full screen.

See [Onboarding](onboarding.md) for the refreshed nine-page introduction, swipe/button navigation and replay from Ops.

See [Flight boundaries and rally points](flight-boundaries.md) for the v1.6.0 QGC safety-plan service, simulated breach recovery and RTL landing behavior.

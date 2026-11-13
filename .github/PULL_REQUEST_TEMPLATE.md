## Summary

<!-- Describe what this PR changes and why. -->

## Type of Change

- [ ] New printer support
- [ ] New feature
- [ ] Bug fix
- [ ] Documentation / tooling

## Printer Details *(for new printer support PRs)*

- **Model:**
- **USB VID:PID:**
- **Document format(s) / protocol:**
- **Requires cold-boot firmware or Smart Install switch?**

## Verification

- [ ] `./gradlew testDebugUnitTest assembleDebug` passes locally
- [ ] Verified on Android TV / Android USB Host hardware (describe device & printer tested)
- [ ] Added or updated unit tests for parser / dithering / telemetry logic (if applicable)

## Pre-Commit Checks

- [ ] No personal paths (`local.properties`), keystores, `.gradle`, or build outputs included
- [ ] Concurrency & resource limits preserved (e.g., `@Synchronized` around native C encoder, bounded network/PDF sizes)
- [ ] No proprietary printer firmware blobs added without proper redistribution rights
- [ ] UI changes remain D-pad navigable for Android TV (`layout-w600dp/activity_main.xml`)

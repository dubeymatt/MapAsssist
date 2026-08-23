# Fix KSP IllegalStateException: unexpected jvm signature V

The error `unexpected jvm signature V` is a known issue when using KSP with Room, specifically when processing `suspend` functions that return `Unit` (represented as `V` in JVM signatures). This typically happens when Room generates Java code that KSP then struggles to reconcile with the Kotlin source.

## Proposed Changes

### Build Configuration

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/dubey/AndroidStudioProjects/MapAsssist/app/build.gradle.kts)
- Enable Room's Kotlin code generation via KSP arguments. This often resolves JVM signature mismatches by generating Kotlin code instead of Java.
- Explicitly set `jvmTarget` for Kotlin to ensure consistency with `compileOptions`.

#### [MODIFY] [libs.versions.toml](file:///C:/Users/dubey/AndroidStudioProjects/MapAsssist/gradle/libs.versions.toml)
- Upgrade Kotlin and KSP to more modern versions that are better suited for AGP 9.3.1.
- Upgrade Room to a more recent stable version (2.6.1 is slightly old for 2026).

## Verification Plan

### Automated Tests
- Run `./gradlew :app:kspDebugKotlin` to verify the KSP processing succeeds.
- Run `./gradlew assembleDebug` to ensure the project builds correctly.

### Manual Verification
- None required as this is a build-time issue.

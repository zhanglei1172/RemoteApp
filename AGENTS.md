# Repository Guidelines

## Project Structure & Module Organization

- `app/` contains the Android application. Kotlin sources live under `app/src/main/java/com/remoteapp/host/`, grouped by responsibility (`auth`, `accessibility`, `capture`, `server`, and `domain`).
- Android resources and the packaged control page are under `app/src/main/res/` and `app/src/main/assets/web/`.
- `app/src/test/` contains JVM unit tests. Device or UI tests, if added, should use the corresponding Android test source set.
- `web/` is the Vite/TypeScript control-console source. `docs/architecture.md` describes the API, lifecycle, and security model.

## Build, Test, and Development Commands

Use JDK 17, Android SDK 35, and Node.js 20 or newer.

```bash
./gradlew :app:assembleDebug       # Build the debug APK
./gradlew :app:testDebugUnitTest   # Run JVM unit tests
cd web && npm install && npm run build  # Build the web console
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. After changing `web/`, copy the generated Vite assets into `app/src/main/assets/web/` before packaging the APK.

## Coding Style & Naming Conventions

Follow Kotlin official style (`kotlin.code.style=official`): four-space indentation, trailing commas where useful, `PascalCase` types, `camelCase` functions/properties, and descriptive package names. Keep Android lifecycle work off the main thread and preserve the existing responsibility-based package layout. TypeScript uses two-space indentation and `camelCase`; keep web formatting consistent with the existing files. No repository-wide formatter or linter is configured, so run the relevant build and tests before submitting changes.

## Testing Guidelines

Tests use JUnit 4 and should be named after the behavior they verify, for example `normalizedCoordinatesMustBeFiniteAndInRange`. Add focused tests for validation, authentication, API behavior, and lifecycle edge cases. Run `./gradlew :app:testDebugUnitTest`; manually verify accessibility, screen capture, rotation, and service start/stop behavior on a suitable Android device when those areas change.

## Commit & Pull Request Guidelines

The Git history currently contains only an initial placeholder commit, so no established convention is available. Use short, imperative subjects (for example, `Fix pairing token expiry`) and keep unrelated changes separate. Pull requests should explain the behavior change, link an issue when applicable, list validation commands, and include screenshots or a short recording for Compose or web UI changes. Call out permission, networking, authentication, or privacy implications explicitly.

## Security & Configuration Tips

Remote control requires explicit user authorization and an enabled accessibility service. Keep local-only access as the default, do not add shell or filesystem control, and avoid logging tokens or screen content. Treat changes to authentication, network exposure, foreground services, or capture permissions as security-sensitive and update `docs/architecture.md` when the design changes.

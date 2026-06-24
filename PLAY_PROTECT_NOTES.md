# Play Protect cleanup notes

This app was cleaned to reduce Play Protect risk when installing APKs outside the Play Store.

## What changed

- Removed unused Android permissions from the manifest:
  - `READ_MEDIA_IMAGES`
  - `READ_MEDIA_VIDEO`
  - `READ_MEDIA_VISUAL_USER_SELECTED`
  - `READ_EXTERNAL_STORAGE`
  - `RECORD_AUDIO`
  - `POST_NOTIFICATIONS`
- Kept only:
  - `INTERNET` for AI/API calls
  - `CAMERA` for the AI Studio camera button
- Removed unused notification/reminder background code:
  - `AppNotify.kt`
  - `ReminderScheduler.kt`
  - `AlarmEvent.kt`
  - `AppVisibility.kt`
- Removed startup notification permission request.
- Bumped APK version to `1.0.1` / `versionCode 2`.
- Debug builds now use package suffix `.debug` so they do not look like the real release app.
- GitHub Actions now prefers a signed release APK when release signing secrets are available.

## Important

For normal phone installation, use a release APK, not the debug fallback APK.

The debug artifact is only for testing and is uploaded as `app-debug-test-only`.

To build a release APK in GitHub Actions, add these repository secrets:

- `RELEASE_KEYSTORE_BASE64`
- `STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Then run the Android CI workflow. The release artifact will be named `app-release`.

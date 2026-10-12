# Tertis for Android

Native Android Studio version of the iOS Tertis project. The game uses a custom high-refresh Canvas view to preserve the original portrait UI, colors, board proportions, controls, and menu structure.

## Open and run

1. Open this `TertisAndroid` folder in Android Studio.
2. Allow Gradle sync to finish.
3. Select an Android device or emulator running Android 8.0 (API 26) or later.
4. Run the `app` configuration.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

Player data, high scores, options, and custom control layouts are saved locally with Android SharedPreferences.

# ZenLink Android

Fork of https://github.com/KDE/kdeconnect-android with its upstream history and
licenses retained. The app keeps the original source namespace for compatibility
and uses the distinct application ID `org.zenos.zenlink` (`.debug` in debug builds).

The `plugins/media` package implements paired audio/video streaming, phone-owned
stream configuration, a foreground service with Stop notification, live front/back
camera switching, and phone/headset microphone routing. Sensors start only after
permission checks and an explicit Start from the phone's media activity.

Defaults: phone microphone and camera to PC; PC system sound to phone. The PC
creates dedicated microphone/playback devices and a virtual webcam through the
companion extension. Upstream KDE Connect features are retained.

Build from the workspace's Nix development shell with `./gradlew assembleDebug`.
Run `./gradlew testDebugUnitTest` for the unit suite. Media protocol tests are in
`src/test/java/org/kde/kdeconnect/plugins/media`.

Camera and headset routes still need complete hardware validation.

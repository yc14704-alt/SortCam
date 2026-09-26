# Development signing only

This public test keystore signs the SortCam+ debug app (`com.sortcam.app.next`) so later debug builds can update it. Alias: `androiddebugkey`; store/key passwords: `android`. It is deliberately public and MUST NOT be used for production, Play Store distribution, authentication, or secrets. Production needs a separately protected release key.

The original SortCam 1.2 APK used an ephemeral runner debug key that was not retained. SortCam+ uses a separate package to preserve the user's original installation and its database. Photos from the earlier app can be selected via gallery import.

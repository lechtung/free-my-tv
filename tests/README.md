# Android recommendation checks

No Gradle or test libraries. Build the app first, then build and install the separate instrumentation APK with the same signing key:

```sh
./build.sh
bash tests/build.sh
adb install --no-incremental -r FreeMyTV.apk
adb install --no-incremental -r out/tests/checks.apk
adb shell am instrument -w io.freemytv.tests/io.freemytv.RecommendationsTest
```

The checks cover the key-independent default, source persistence, Off, shelf removal and filtering, Movies, Shows and Watch Next (clips/music stay in Explore), intents staying inside the publishing app, and package resolution staying off the UI thread for both custom and built-in shelves. Repeated UI filtering and screensaver tab reads use cached eligibility. Without a TMDB key, they also verify that Discover has no picks. They run in the home process, temporarily change the app's shelf and recommendation preferences, and restore both before finishing. No provider data or TV settings are changed.

Run with TV-listing permission granted to exercise the filters, and with it denied to check that cached app recommendations are hidden. Restore the original permission afterward. Reopen the home screen when finished. The test APK can be removed with `adb uninstall io.freemytv.tests`.

Also check on the TV: switching sources and restarting, all three tabs, scrolling to both ends, selected-card expansion, empty feeds, artwork and app-logo placeholders, content links, and the screensaver. LG/webOS and Samsung/Tizen stay unchanged.

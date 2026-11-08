dpt-shell integration (open-source packer)

What you get after `gradlew.bat assembleRelease`:
  1. normal app-release.apk (signed, minified)
  2. protected copy under app/build/outputs/apk/protected/
     - DEX method bodies hollowed (reconstructed at runtime)
     - anti-debug
     - anti-frida
     - libc .text CRC check
     - only arm / arm64 ABIs kept

This is NOT commercial IJM (爱加密). IJM is a paid web service with no public
offline CLI you can legally embed. dpt-shell is the practical offline equivalent
you can run fully locally and in CI.

Layout:
  tools/dpt/executable/dpt.jar
  tools/dpt/executable/shell-files/...

Override / extra flags:
  Edit the protectRelease task in app/build.gradle.kts
  Useful flags:
    --disable-anti-debug
    --disable-frida-detect
    --disable-crc-detect
    -x / --no-sign          (if you want to re-sign yourself)
    -vs / --verify-sign     (pin signature at runtime)
    -K / --keep-classes     (keep some classes unhollowed for speed)
    -r rules.file           (exclude class list)

First time:
  tools/ already contains the v2.21.0 executable from this pack.
  Just run:  gradlew.bat assembleRelease

NativeGuard note:
  After protection the APK is re-signed by dpt (or left unsigned with -x).
  Update PINNED_SIGNATURE_HASH in NativeGuard.kt to match the final signing
  cert, or the process will kill itself on release. Easiest path for testing:
  temporarily force isAllowed() = true while you verify the protected build.

# bridge-app

The production app. See the project root for docs:

- [RC4Solution.md](../docs/RC4Solution.md) — architecture, build/install instructions, current
  status.
- [MitsubishiEclipseCrossReverseEngineerWitifProtocol.md](../docs/MitsubishiEclipseCrossReverseEngineerWitifProtocol.md)
  — the car's protocol this app speaks.
- [DEVELOPER-NOTES.md](../docs/DEVELOPER-NOTES.md) — test phone setup and debug tooling.

An earlier version of this app used a vendored `phevcore` C library via JNI/NDK, targeting
what turned out to be the wrong car/cipher (Outlander PHEV, not this Eclipse Cross). That
code has been removed; the app is now pure Kotlin/JVM, no native build step.

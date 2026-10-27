# Native piano display patch (AGPL-3.0)

Upstream: [GeyserDisplayEntity](https://github.com/GeyserExtensionists/GeyserDisplayEntity/tree/847d7a9a08b49b503c9d4e828de8e3dc7eb384e9), version 1.0.14.
`ItemDisplayEntity.java` is the complete modified class. All other classes remain identical to the upstream release. The upstream complete source and Gradle project are available at the pinned link. License: [AGPL-3.0](LICENSE).

The patch publishes the nine piano model identifiers as Bedrock entity `VARIANT` values 1–9. For those entities it sends empty armour slots, allowing the resource pack to draw a native entity mesh instead of relying on an equipment attachable and its bound skeleton. Other models retain the upstream equipment path.

The model order must match `../native-models.json` and `scripts/build_bedrock.py`.

Rebuild with `python scripts/build_native_display.py` using JDK 21+. Place the official extension JAR in `.local/bedrock-output/GeyserDisplayEntity-1.0.14.jar`, your Geyser API JAR in `.local/Geyser-live.jar` and Velocity in `.local/bedrock-proxy/velocity.jar`. These are compile-time dependencies; the script replaces just one class in the original shaded extension JAR. Its expected base JAR and annotation dependency hashes are checked.

Install the patched extension **instead of** the original extension, together with the native 2.5.0 piano pack. Restart the proxy process to replace the extension class. No Minecraft backend restart is needed.

# More Cobblemon Contents (MCC)

Successor of More Battle Content (MBC). MCC is being restructured into a core mod and content addons.

| Item | Value |
|---|---|
| Mod ID | `more_cobblemon_contents` |
| Package | `jbro.cobblemon.mcc` |
| JAR | `more-cobblemon-contents-<version>.jar` |
| Version property | `more_cobblemon_contents_version` (root `gradle.properties`) |
| Command | `/mcc` |

## Build and test

```bash
./gradlew :more-cobblemon-contents:unitTest
./gradlew :more-cobblemon-contents:remapJar
```

`tasks.test` is disabled; run tests with `unitTest`.

Work history, decisions and open issues are recorded in [`MEMORY.md`](MEMORY.md).

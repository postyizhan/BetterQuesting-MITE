# BetterQuesting-MITE

BetterQuesting-MITE is an in-progress port of Better Questing to Minecraft 1.6.4-MITE. The repository currently contains platform probes and the FML project skeleton; it does not yet provide a playable quest system.

## Target platform

- Minecraft 1.6.4-MITE
- Fish Mod Loader (FML) 3.4.2
- RustedIronCore (RIC) 1.5.0
- ManyLib 2.3.1

ManyLib is a required, independently installed runtime dependency licensed under LGPL-3.0. Its source repository is [De6ris/ManyLib](https://github.com/De6ris/ManyLib), as declared by the dependency's own `fml.mod.json`. This project does not shade or relocate ManyLib; a compatible version can be replaced as a separate JAR.

The current `assets/betterquesting/icon.png` is a temporary copy of the Fish Example Mod template icon, not an upstream Better Questing asset.

## Development

Use the included Gradle wrapper:

```console
./gradlew.bat clean build
```

See [`docs/platform-probes.md`](docs/platform-probes.md) for the verified platform surface and [`docs/source-migration-ledger.md`](docs/source-migration-ledger.md) for the source migration inventory.

## Licenses

The port and upstream Better Questing-derived material are distributed under the MIT license in [`LICENSE`](LICENSE), preserving the upstream copyright notice. Additional upstream bundled components retain their license texts in [`LICENSE.cb-for-bq`](LICENSE.cb-for-bq) and [`LICENSE.command-blocks`](LICENSE.command-blocks).

ManyLib remains a separate dependency. Its LGPL-3.0 text is available in the checked reference source at [`ManyLib-main/LICENSE`](ManyLib-main/LICENSE) and in its [source repository](https://github.com/De6ris/ManyLib).

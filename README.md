# SneakyResource

`SneakyResource` is a Paper plugin plus the source resource pack and datapack it manages.

The plugin reads the repo checkout directly from disk, then:

- zips `sasquatchresourcepack/` into a server-ready pack zip
- writes a SHA-1 file for that zip
- mirrors `datapack/` into your Paper world datapacks folder
- runs `minecraft:reload` after syncing

## Commands

- `/sneakyresource sync`
- `/sneakyresource reload`
- `/sneakyresource status`

## Default layout

The default `config.yml` assumes this repo is checked out next to your Paper server folder:

```text
parent/
  sneakyresource/
  paper-server/
```

With that layout:

- `../sneakyresource/sasquatchresourcepack` is the resource pack source
- `../sneakyresource/datapack` is the datapack source
- `world/datapacks/sneakyresource` is the live server datapack destination

## Build

```bash
./gradlew build
```

The plugin jar will be in `build/libs/`.

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

The default `config.yml` assumes this repo is checked out inside your Paper server folder:

```text
paper-server/
  sneakyresource/
  plugins/
  world/
```

With that layout:

- `sneakyresource/sasquatchresourcepack` is the resource pack source
- `sneakyresource/datapack` is the datapack source
- `world/datapacks/sneakyresource` is the live server datapack destination

The plugin also falls back to the older sibling layout automatically:

```text
parent/
  paper-server/
  sneakyresource/
```

## Build

```bash
./gradlew build
```

The plugin jar will be in `build/libs/`.

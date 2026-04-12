# SneakyResource

`SneakyResource` is a Paper plugin plus the source resource pack and datapack it manages.

The plugin reads the repo checkout directly from disk, then:

- zips `sasquatchresourcepack/` into a server-ready pack zip
- writes a SHA-1 file for that zip
- mirrors `datapack/` into your Paper world datapacks folder
- sends the resource pack to joining players through Paper
- runs `minecraft:reload` after syncing

## Commands

- `/sneakyresource sync`
- `/sneakyresource reload`
- `/sneakyresource update`
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

## Resource Pack Delivery

Set `resource-pack.public-url` in `plugins/SneakyResource/config.yml` to the public download URL for the generated zip.

When that URL is configured, the plugin will:

- send the pack to players on join via the Paper API
- optionally mark the pack as required

## Self Update

`/sneakyresource update` will:

- run `git pull --ff-only` in the configured repo
- run the repo build
- copy the built jar into Paper's `update/` folder for the next restart
- optionally re-sync the resource pack and datapack from the updated checkout

By default this uses:

- `self-update.repository-directory: "sneakyresource"`
- `self-update.branch: "main"`
- `gradlew.bat build` on Windows
- `./gradlew build` on Linux/macOS

Paper recommends staging updated plugin jars in the configured update folder and applying them on restart:

- [Updating | PaperMC Docs](https://docs.papermc.io/paper/updating/)
- [bukkit.yml Reference | PaperMC Docs](https://docs.papermc.io/paper/reference/bukkit-configuration/)

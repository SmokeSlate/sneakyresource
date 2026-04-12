# SneakyResource

`SneakyResource` is a Paper plugin plus the source resource pack and datapack it manages.

The plugin reads the repo checkout directly from disk, then:

- zips `sasquatchresourcepack/` into a server-ready pack zip
- writes a SHA-1 file for that zip
- mirrors `datapack/` into your Paper world datapacks folder
- sends the resource pack to joining players through Paper
- runs `minecraft:reload` after syncing

If no external `sneakyresource/` checkout exists, the plugin falls back to the bundled pack and datapack that are shipped inside the jar.

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

- `SneakySasquatch/sasquatchresourcepack` is the resource pack source
- `SneakySasquatch/datapack` is the datapack source
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

By default the plugin uses GitHub-hosted URLs:

- `https://raw.githubusercontent.com/SmokeSlate/sneakyresource/pack-dist/sasquatchresourcepack.zip`
- `https://raw.githubusercontent.com/SmokeSlate/sneakyresource/pack-dist/sasquatchresourcepack.zip.sha1`

If you prefer, you can still set your own `resource-pack.public-url` and `resource-pack.sha1-url` in `plugins/SneakyResource/config.yml`.

When resource-pack delivery is configured, the plugin will:

- send the pack to players on join via the Paper API
- optionally mark the pack as required

For truly zero-config setup, upload the jar and restart the server. Pushes to `main` publish the generated pack files to the `pack-dist` branch, which keeps the default URLs on `raw.githubusercontent.com` and works with hosts such as MineKeep that whitelist GitHub domains for resource packs.

## Self Update

`/sneakyresource update` will:

- run `git pull --ff-only` in the configured repo
- run the repo build
- copy the built jar into Paper's `update/` folder for the next restart
- optionally re-sync the resource pack and datapack from the updated checkout

By default this uses:

- `self-update.repository-directory: "SneakySasquatch"`
- `self-update.branch: "main"`
- `gradlew.bat build` on Windows
- `./gradlew build` on Linux/macOS
- `self-update.run-on-startup: true`
- `self-update.sync-when-unchanged: true`
- `self-update.restart-after-update: true`

Paper recommends staging updated plugin jars in the configured update folder and applying them on restart:

- [Updating | PaperMC Docs](https://docs.papermc.io/paper/updating/)
- [bukkit.yml Reference | PaperMC Docs](https://docs.papermc.io/paper/reference/bukkit-configuration/)

If your host does not automatically restart the server after shutdown, set `self-update.restart-command` to the console command your panel expects and SneakyResource will run that instead.

# SneakyResource

`SneakyResource` is a Paper plugin plus the source resource pack and datapack it manages.

The plugin reads the repo checkout directly from disk, then:

- zips `sasquatchresourcepack/` into a server-ready pack zip
- writes a SHA-1 file for that zip
- mirrors `datapack/` into your Paper world datapacks folder
- syncs the Nexo item/recipe configs for custom blocks when Nexo is installed
- mirrors `sasquatchresourcepack/` into Nexo's `pack/external_packs/` when Nexo is installed
- places custom `Cashe` and rock blocks through Nexo when available, otherwise falls back to reserved vanilla block states
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
- `SneakySasquatch/nexo` is the Nexo config source
- `world/datapacks/sneakyresource` is the live server datapack destination
- `plugins/Nexo` is the Nexo root used for synced items, recipes, and external pack assets

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

By default the plugin self-hosts the generated pack through the public `https://...:2053` endpoint in front of its embedded HTTP server:

- `resource-pack.self-hosted.enabled: true`
- `resource-pack.self-hosted.public-base-url: "https://sneakysasquatch.minekeep.dev:2053"`

If you terminate TLS in a reverse proxy, set `resource-pack.self-hosted.public-base-url` to the external `https://...` URL and leave the embedded server on its internal bind port. The embedded server itself still only speaks HTTP.

If you prefer, you can still disable `resource-pack.self-hosted.enabled` and set your own `resource-pack.public-url` and `resource-pack.sha1-url` in `plugins/SneakyResource/config.yml`.

When built-in resource-pack delivery is configured, the plugin will:

- send the pack to players on join via the Paper API
- optionally mark the pack as required

For fully local hosting, upload the jar and restart the server. The plugin will generate the zip and sha1 locally, then serve both from the embedded HTTP server. Pushes to `main` still publish `pack-dist`, which remains available as a fallback external host.

## Custom Blocks

When Nexo is installed and `nexo.enabled: true`, SneakyResource treats Nexo as the source of truth for the custom block set:

- `nexo/items/sasquatch_blocks.yml` defines `cashe`, `rock`, `rock_small_1`, `rock_small_2`, and `rock_small_3`
- `nexo/recipes/` defines the crafting recipes for those Nexo items
- `sasquatchresourcepack/` is mirrored into `plugins/Nexo/pack/external_packs/<external-pack-name>` so Nexo can merge the existing models and textures into its generated pack
- the plugin switches its runtime block integration to Nexo and keeps only the Cashe inventory persistence logic locally

If Nexo is not installed, the plugin falls back to the old reserved-state integration:

- `Cashe` uses a reserved `jigsaw` orientation
- rocks use reserved `pink_petals` states

Existing reserved-state blocks already placed in a world are not auto-converted to Nexo blocks by this change.

This build targets Paper `1.21.11`.

## Self Update

`/sneakyresource update` will:

- download the latest built plugin jar published by GitHub Actions
- verify it against the published SHA-1
- copy that jar into Paper's `update/` folder for the next restart
- optionally re-sync the resource pack and datapack after the update check

By default this uses:

- `self-update.branch: "pack-dist"`
- `self-update.jar-url: "https://github.com/SmokeSlate/sneakyresource/raw/refs/heads/{branch}/sneakyresource.jar"`
- `self-update.jar-sha1-url: "https://github.com/SmokeSlate/sneakyresource/raw/refs/heads/{branch}/sneakyresource.jar.sha1"`
- `self-update.build-info-url: "https://github.com/SmokeSlate/sneakyresource/raw/refs/heads/{branch}/build-info.properties"`
- `self-update.run-on-startup: true`
- `self-update.sync-when-unchanged: true`
- `self-update.restart-after-update: true`

Set `self-update.branch` to any published branch you want this server to follow. The `{branch}` placeholder is expanded in all three self-update URLs, so branch switches do not require editing each URL separately.

GitHub Actions publishes different dist branches automatically:

- `main` publishes to `pack-dist`
- a pushed branch like `feature/nexo-fix` publishes to `pack-dist-branch-feature-nexo-fix`
- a same-repo pull request like `#42` publishes to `pack-dist-pr-42`

Those dist branches contain:

- `sneakyresource.jar`
- `sneakyresource.jar.sha1`
- `build-info.properties`
- `sasquatchresourcepack.zip`
- `sasquatchresourcepack.zip.sha1`

Paper recommends staging updated plugin jars in the configured update folder and applying them on restart:

- [Updating | PaperMC Docs](https://docs.papermc.io/paper/updating/)
- [bukkit.yml Reference | PaperMC Docs](https://docs.papermc.io/paper/reference/bukkit-configuration/)

If your host does not automatically restart the server after shutdown, set `self-update.restart-command` to the console command your panel expects and SneakyResource will run that instead.

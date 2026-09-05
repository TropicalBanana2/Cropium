# Cropium

A client-side Fabric mod for crop harvesting, mine harvesting, egg hatching, and merchant workflows. Built around the Sales Minehut layout and menus—not a general-purpose bot for arbitrary servers.

Use only in your own test world though it is compatible with the server Sales.minehut.gg

## Use the release
If you don't find yourself enticed to build it by yourself, download the release. You can check it yourself for anything sus.

## What it does

- **Farm:** follows mapped routes and makes direct passes through reachable shiny crops.
- **Mine:** flies low over the mapped mine, prioritizes fossils over ice, and uses long oval/Zamboni-style routes.
- **Custom routes:** draw closed loops on a 2D map, save multiple phases, and switch between them during a session.
- **Egg Hatcher:** selects an egg tier, verifies auto-hatching, and forges pets up to a selected tier when storage fills.
- **Merchant:** optionally pauses a macro to claim eligible NPC offers, salvage eligible purchases, and place retained NPCs on a configured plot.
- **Dashboard:** shared controls, route previews, exclusions, block/shiny rates, diagnostics, and bounded recovery with safety stops.

The mod reads the loaded client world, entity highlights, menu items, and chat to coordinate normal movement, aiming, block breaking, and inventory actions. Each workflow has its own controller, with shared navigation, input recovery, configuration, and statistics. It does not require a server-side mod.

## Build and install

Requirements: **JDK 25**, **Minecraft 26.2**, **Fabric Loader 0.19.3+**, and **Fabric API 0.158.0+26.2**. Gradle is included through the wrapper; no separate Gradle installation is needed.

```powershell
.\gradlew.bat build
```

In IntelliJ IDEA, open this folder as a Gradle project, set the Gradle JVM to JDK 25, and run the `build` task. A Unix launcher is also included: `bash ./gradlew build`.

Copy `build/libs/cropium-1.0.0.jar` and Fabric API into your Minecraft instance's `mods` folder. Do not install the `-sources.jar`. Restart Minecraft after replacing the mod. Builds require internet access on the first run.

## Quick start

1. Open the dashboard with **O** (or middle click by default).
2. Choose **Harvester**, **Mine**, or **Egg Hatcher** and review its settings. Farming/mining need a usable tool in hotbar slot 1 and server-permitted flight.
3. Use the module's start control. **P** pauses/resumes active harvesting; **Shift** or **Stop all** stops it.

The bundled farm/mine layouts are location-specific. Plot bounds must be configured before NPC placement. Saved settings stay in the instance's `config` folder; existing `crop-pilot` filenames are retained for compatibility.

See the [user guide](docs/user-guide.md) for controls, setup, safety behavior, and module details. See [development and publishing](docs/development.md) for the source layout, checks, and GitHub commands.

## License

The source is licensed under [MIT](LICENSE), matching the existing mod metadata. Minecraft, Fabric, and other third-party content retain their own licenses. Cropium is not affiliated with Mojang, Microsoft, or Minehut.

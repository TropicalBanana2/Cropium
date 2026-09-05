# Development and publishing

## Source layout

| Path | Purpose |
| --- | --- |
| `src/main/java/com/salesfarm/croppilot/` | Shared geometry, navigation, parsers, workflow logic, and assertion-based regression tests |
| `src/client/java/com/salesfarm/croppilot/` | Fabric entry point, dashboard, controllers, config, and client mixins |
| `src/main/resources/` | Fabric mod metadata |
| `src/client/resources/` | Mixins, translations, and embedded map data |
| `tools/checks/` | Portable client source-contract checks |
| `tools/maps/` | Optional, read-only map inspection and route-generation utilities |
| `docs/` | Detailed usage and developer notes |
| `.github/workflows/` | Automatic checks and build artifacts |

Keep the `crop-pilot` mod ID, package names, resource paths, and configuration filenames compatible with existing saves. The embedded maps are runtime inputs; downloaded worlds and personal configuration are not part of the repository.

## Checks

Use JDK 25 and the included Gradle wrapper:

```powershell
.\gradlew.bat check build
```

`check` runs `motionSelfTest` (assertions enabled) and `clientSourceContracts`. The behavior tests cover shared navigation, target selection, flight, route validation, and inventory/workflow logic. Source-contract checks guard client attack handling, boundary protection, and quiet rerouting. They are intentionally lightweight and do not emulate Minecraft or server acknowledgements.

The tests are currently colocated with the shared Java helpers and dispatched from `MotionMath.main`. Client behavior lives in the separate Fabric client source set. Keep changes focused rather than reorganizing controller state machines without regression coverage.

For runtime changes, also smoke-test start/pause/stop, edge and obstacle handling, Merchant handoff/resume with attack held, and inventory transfers with an empty cursor in a permitted test environment. Never disable safety checks just to make a test pass.

If sync software locks Gradle output, build from a local non-synced checkout. Jars belong in `build/libs/`; generated output and local game data are ignored by Git.

### Optional map utilities

These are not required for the mod build. From the repository root, use Python 3.10+ and a virtual environment:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r tools/maps/requirements.txt
.\.venv\Scripts\python.exe tools/maps/generate_safe_routes.py
.\.venv\Scripts\python.exe tools/maps/inspect_mine.py "C:\path\to\world-download" --verify
```

The first script checks generated routes against the embedded asset; `--payload` prints verified replacement JSON without writing it. The second reads the supplied world download and verifies the saved mine geometry. Its expected region directory is `dimensions/minecraft/overworld/region`. Keep world downloads outside the repository. Both scripts offer `--help`.

## Put it on GitHub

Before the first commit, review the included maps and any other content for permission to redistribute. `.gitignore` excludes builds, game instances, logs, configurations, world saves, Python caches, and common credential files; still inspect what will be committed.

This checkout already has Git initialized. From the project folder:

```powershell
git add .
git diff --cached --stat
git diff --cached
```

After reviewing the staged files:

```powershell
git branch -M main
git update-index --chmod=+x gradlew
git commit -m "Initial Cropium source"
gh auth login
gh repo create Cropium --private --source=. --remote=origin --push
```

This creates a **private** repository by default. Replace `--private` with `--public` only if you want public source and have reviewed the contents. Skip `gh auth login` if already signed in. A commit may first require your preferred Git author name/email to be configured.

If you create an empty repository on GitHub's website instead, omit GitHub-generated starter files and replace the final `gh` commands with:

```powershell
git remote add origin https://github.com/YOUR_USERNAME/Cropium.git
git push -u origin main
```

The included workflow builds the source on Windows, checks the wrapper, and uploads jars as build artifacts. GitHub does not build a Java repository automatically without a workflow. No secrets, publishing token, or release setup is required for this workflow; GitHub Actions must be enabled for the repository.

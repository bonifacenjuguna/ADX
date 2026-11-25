# Project scripts

Small, standard-library Python and PowerShell utilities used to maintain the SavahBot / SavahLive release process.

| Script | Purpose |
|---|---|
| `bump_version.py` | Update or verify the separate SavahBot and SavahLive version values |
| `check_releases.py` | Validate the release directory layout and checksums |
| `pack_android.py` | Rebuild the Android source archive |
| `gen_savahlive_icons.py` | Generate matching SVG and Android vector icons from one source list |
| `release-savahlive.ps1` | Run the SavahLive release workflow on Windows |

Run commands from the repository root. See [`../docs/RELEASING.md`](../docs/RELEASING.md) for the release procedure. The scripts are retained as part of the final project record; no further feature releases are planned.

# SavahLive icons

One 24x24 SVG per icon: 2 px stroke, round caps and joins, drawn with `currentColor`, so it takes whatever colour you give it.
Four icons are deliberately solid because a solid shape is the clearer symbol: `dot` (a status light), `play`, `stop` and `heart`.

The Android app uses the same shapes as vector drawables (`android/app/src/main/res/drawable/ic_<name>.xml`, white) and tints them
with the panel theme at runtime, so changing the theme recolours every icon.

**Do not edit these files by hand.** They are generated from one list, so the SVGs and the Android drawables can never disagree:

    python scripts/gen_savahlive_icons.py

To add or change an icon, edit its entry in `ICONS` in that script and run it again. (`ic_stat` and `ic_launcher_foreground`, the
notification and launcher marks, are separate and are left alone.)

| Where | Icons |
|---|---|
| Bottom bar | `home`, `palette`, `chat`, `settings` |
| Panel header and tab | `dot` (status light), `eye` (viewers), `heart` (likes), `expand`, `collapse`, `dock` (tuck into a tab) |
| Panel body | `pin`, `send` |
| Notification | `eye_off` (hide), `lock`, `unlock`, `move` (reset), `phone` (open), `stop`, `palette` (next look) |
| App screens | `play`, `stop`, `layers`, `link`, `paste`, `check`, `bell`, `battery`, `download`, `trash`, `bookmark`, `opacity`, `text`, `star`, `clock`, `chart`, `wifi`, `live`, `eye_off`, `close` |


---

**Project record:** These generated icon assets are part of the SavahLive 3.0.0 companion release. The source list and generator remain the canonical way to make changes; generated SVGs and Android drawables should stay in sync.

<!--
  EDIT ME: every place you may want to change is marked with "EDIT".
  1. Replace bonifacenjuguna/SavahBot (search for it) with your GitHub user and repository name.
  2. Add a row to the download table after each release (see "Downloads").
-->
<div align="center">

<img src="docs/images/banner.svg" alt="SavahBot: calm moderation for YouTube live chat" width="100%">

<br>

<p align="center">
<a href="#downloads"><img height="28" alt="SavahBot version" src="https://img.shields.io/badge/SavahBot-2.0.0-7d95ff?style=for-the-badge"></a> <a href="#downloads"><img height="28" alt="SavahLive version" src="https://img.shields.io/badge/SavahLive-3.0.0-e5242b?style=for-the-badge&logo=android&logoColor=white"></a> <img height="28" alt="Owner only" src="https://img.shields.io/badge/access-owner%20only-3ccf93?style=for-the-badge"> <img height="28" alt="No AI" src="https://img.shields.io/badge/moderation-explainable%20%C2%B7%20no%20AI-f0b34a?style=for-the-badge"> <img height="28" alt="Python" src="https://img.shields.io/badge/Python-3776AB?logo=python&logoColor=white&style=for-the-badge"> <img height="28" alt="Starlette" src="https://img.shields.io/badge/Starlette-111827?style=for-the-badge"> <img height="28" alt="TypeScript" src="https://img.shields.io/badge/TypeScript-3178C6?logo=typescript&logoColor=white&style=for-the-badge"> <img height="28" alt="Vite" src="https://img.shields.io/badge/Vite-646CFF?logo=vite&logoColor=white&style=for-the-badge"> <img height="28" alt="Kotlin" src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white&style=for-the-badge"> <img height="28" alt="Android" src="https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=black&style=for-the-badge"> <img height="28" alt="Gradle" src="https://img.shields.io/badge/Gradle-02303A?logo=gradle&logoColor=white&style=for-the-badge"> <img height="28" alt="PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-4169E1?logo=postgresql&logoColor=white&style=for-the-badge"> <img height="28" alt="SQLite" src="https://img.shields.io/badge/SQLite-003B57?logo=sqlite&logoColor=white&style=for-the-badge"> <img height="28" alt="Redis" src="https://img.shields.io/badge/Redis-DC382D?logo=redis&logoColor=white&style=for-the-badge"> <img height="28" alt="Railway" src="https://img.shields.io/badge/Railway-0B0D0E?logo=railway&logoColor=white&style=for-the-badge"> <img height="28" alt="Vercel" src="https://img.shields.io/badge/Vercel-000000?logo=vercel&logoColor=white&style=for-the-badge"> <img height="28" alt="Firebase" src="https://img.shields.io/badge/Firebase-FFCA28?logo=firebase&logoColor=black&style=for-the-badge"> <img height="28" alt="Telegram" src="https://img.shields.io/badge/Telegram-26A5E4?logo=telegram&logoColor=white&style=for-the-badge"> <img height="28" alt="YouTube" src="https://img.shields.io/badge/YouTube-FF0000?logo=youtube&logoColor=white&style=for-the-badge"> <img height="28" alt="GitHub Actions" src="https://img.shields.io/badge/GitHub%20Actions-2088FF?logo=githubactions&logoColor=white&style=for-the-badge"> <img height="28" alt="PWA" src="https://img.shields.io/badge/PWA-5A0FC8?logo=pwa&logoColor=white&style=for-the-badge">
</p>

**[Downloads](#downloads)** &nbsp;·&nbsp; **[Two products](#two-products-one-repo)** &nbsp;·&nbsp; **[Screenshots](#a-look-around)** &nbsp;·&nbsp; **[How it fits](#how-it-fits-together)** &nbsp;·&nbsp; **[Quick start](#quick-start)** &nbsp;·&nbsp; **[Docs](#documentation)**

</div>

<br>

SavahBot is a personal, owner-only moderation and utility bot for YouTube live chat. You run it yourself and control it from a web dashboard and from Telegram. **SavahLive** is its Android companion: it floats your stream's chat, viewers and likes over any game on your phone.

There is no AI in the loop. Moderation is a set of explainable algorithms, and every decision shows up as a named signal with a weight, so you can always see why a message scored what it did.

---

## Downloads

> **SavahLive (Android)** is the only part you download. The dashboard and the backend are deployed, not installed ([Quick start](#quick-start)).

<!-- Add the newest SavahLive release at the top. Link to the published GitHub release asset and record its published date and SHA-256 digest. -->

| | Version | Released | Download | SHA-256 | Notes |
|:-:|:--|:--|:--|:--|:--|
| <img src="branding/savahlive-app-icon.svg" width="34" alt=""> | **SavahLive 3.0.0** | **25 Nov 2026** | [**Download release asset**](https://github.com/bonifacenjuguna/SavahBot/releases/download/3.0.0/unsigned.apk) | <code>99dc3ada235c7110d19ebad48d3cf9d3caa47bc9ae1d36aeb80c8fc76eff07b7</code> | [Release notes](https://github.com/bonifacenjuguna/SavahBot/releases/tag/3.0.0) |

<sub>**Release asset note:** GitHub currently lists the uploaded file as `unsigned.apk`. The SHA-256 above is GitHub's asset digest for that exact file. Verify its signing status before distributing it as an installable release. The signed, versioned APK folder described in [the release guide](docs/RELEASING.md) is not present in the repository yet.</sub>

<details>
<summary><b>Verify the downloaded release asset</b></summary>

Expected SHA-256:

```text
99dc3ada235c7110d19ebad48d3cf9d3caa47bc9ae1d36aeb80c8fc76eff07b7
```

Windows PowerShell:

```powershell
Get-FileHash unsigned.apk -Algorithm SHA256
```

macOS / Linux:

```bash
sha256sum unsigned.apk
```

The result must match the hash above exactly.

</details>

### Source and developer downloads

| What | Download | Size | For |
|:--|:--|:--|:--|
| **Android source only** (`android/`, excludes build output and signing keys) | [**SavahLive-android-source.zip**](https://github.com/bonifacenjuguna/SavahBot/raw/refs/heads/main/downloads/SavahLive-android-source.zip) | **141 KB** (144,353 bytes) | Building SavahLive in Android Studio or with Gradle |
| **Whole repository** (dashboard + backend + Android app) | [**Download ZIP**](https://github.com/bonifacenjuguna/SavahBot/archive/refs/heads/main.zip) | Varies | Full source, deployment and development |
| **Frontend only** | [Download frontend folder ZIP](https://download-directory.github.io/?url=https://github.com/bonifacenjuguna/SavahBot/tree/main/frontend) | Varies | Dashboard development |
| **Backend only** | [Download backend folder ZIP](https://download-directory.github.io/?url=https://github.com/bonifacenjuguna/SavahBot/tree/main/backend) | Varies | API and service development |

<sub>The Android source ZIP is a separate, clean source package; it is not an APK. Folder-only downloads use an external directory-download service. For a trusted, complete copy, use GitHub's whole-repository ZIP or clone the repository. Refresh the Android source ZIP after changing the app: `python scripts/pack_android.py`.</sub>

<sub>**Installation status:** the current release asset is named `unsigned.apk`. Do not treat it as an end-user-ready APK until its signing status is confirmed and a properly signed release build is published. Once the signed build is available, install it from the release page and pair it through **Manage › SavahLive** in the dashboard.</sub>

---

## Two products, one repo

The dashboard and the Android app are different projects with different release rhythms, so they have **different version numbers**.

| | <img src="branding/logo-app-icon.svg" width="20" alt=""> **SavahBot** | <img src="branding/savahlive-mark.svg" width="18" alt=""> **SavahLive** |
|:--|:--|:--|
| **What it is** | The brain: moderation, commands, timers, notifications, weather, history, backup | A floating window for your phone: chat, viewers, likes |
| **Where it runs** | Railway (backend) and Vercel (dashboard) | On your Android phone, over any other app |
| **Made with** | Python 3.13, Starlette, TypeScript | Kotlin, plain Android Views, no third-party libraries |
| **Version** | **2.0.0** | **3.0.0** |
| **Folders** | `backend/`, `frontend/` | `android/` |
| **You get it by** | Deploying it ([DEPLOYMENT](docs/DEPLOYMENT.md)) | Downloading the APK ([above](#downloads)) |
| **Talks to** | YouTube, Telegram, Firebase, Open-Meteo | Only your SavahBot, never YouTube |
| **Can moderate?** | Yes: delete, hold, time out, ban | Optional: hide a message or time out for 5 minutes, per phone |
| **Release** | `python scripts/bump_version.py web X.Y.Z`, then redeploy | `python scripts/bump_version.py app X.Y.Z`, then `release-savahlive.ps1` |

---

## A look around

<p align="center">
  <img src="docs/images/dashboard.svg" alt="The SavahBot dashboard on a computer and a phone" width="100%">
</p>

<p align="center">
  <img src="docs/images/overlay.svg" alt="The SavahLive overlay floating over a game, with its parts labelled" width="78%">
</p>

<p align="center">
  <img src="docs/images/themes.svg" alt="SavahLive's five app themes: Auto, Dark, AMOLED, Light and Soft" width="100%">
</p>

<sub>Illustrations drawn for this README. They show the layout and colours, not live data.</sub>

---

## What you get

| | |
|:--|:--|
| 🛡️ **Explainable moderation** | Every message is scored by named signals with weights. Open any decision and see exactly why. Held, removed, approved: all logged. |
| 🧠 **Knowledge packs and detectors** | Packs of patterns for spam, scams and abuse, plus detectors that read them. Set each one *Off*, *Shadow* (watch only) or *Live*. It learns from your Approve and Reject, by counting, with no AI service. |
| 🧪 **Demo mode** | Simulated chat and nine scenarios (raid, link spammer, caps flood, bot timing, disguised words and more). Nothing touches YouTube or Telegram and no quota is used. |
| 💬 **Commands and timers** | Auto-replies, timers that post while you are live, your own `$(variables)`. |
| ✈️ **Telegram** | Alerts and owner commands, by webhook or polling, switchable any time. |
| 🔔 **Notification Centre** | A bell in the app, push on phone and desktop, quiet hours. |
| 🌦️ **Weather** | Open-Meteo forecasts and alerts for the places you choose. |
| 📈 **History and insights** | Long-term history with interactive charts, per stream. |
| 🔐 **Careful sign-in** | Password, passkeys, authenticator app, recovery codes, and a lockdown switch that signs everything else out. |
| 💾 **Encrypted backup** | One `.svbak` file you can export, import and restore piece by piece, with an undo. |
| 📱 **Installable** | A progressive web app for phone, tablet and desktop, with offline pages. |
| 🎙️ **Chat reader** | Reads chat aloud with a neural voice, with per-person voices. |
| 🧩 **SavahLive** | The Android overlay: pair once, then it just works. Five themes, a panel that remembers where you left it, and pop-outs for each message. |

---

## How it fits together

<p align="center">
  <img src="docs/images/architecture.svg" alt="YouTube and Telegram feed the SavahBot backend, which serves the dashboard and the SavahLive app" width="100%">
</p>

```mermaid
sequenceDiagram
    autonumber
    participant D as Dashboard
    participant B as SavahBot
    participant P as SavahLive (phone)
    D->>B: Manage > SavahLive > Pair a phone
    B-->>D: QR code + one-time code (5 minutes)
    P->>B: Redeem the code
    B-->>P: Its own device key (stored encrypted on the phone)
    loop while you stream
        P->>B: Feed: chat, viewers, likes
        B-->>P: Only what the phone may see
    end
```

The phone never talks to YouTube and holds no YouTube credentials. A device key opens exactly one door, the overlay feed. Details: [docs/SAVAHLIVE.md](docs/SAVAHLIVE.md).

---

## Technology and infrastructure

- **Backend** (`backend/`): Python 3.13, Starlette and uvicorn. PostgreSQL in production, SQLite for local use. Runs on Railway.
- **Dashboard** (`frontend/`): Vite and TypeScript with no framework. Responsive for phone, tablet and desktop, installable. Runs on Vercel.
- **SavahLive** (`android/`): Kotlin, programmatic Views, no third-party libraries. Android 8.0 or newer.
- **Telegram**: webhook or polling, switchable from the dashboard or with `/tgmode`.

---

## Quick start

**Run it locally**

```bash
cd backend && python -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
uvicorn app.main:create_app --factory --reload --port 8000
cd ../frontend && npm install && VITE_API_URL=http://localhost:8000 npm run dev
```

On first start a generated owner password is printed in the log (or set `OWNER_PASSWORD`). Details: [docs/TESTING.md](docs/TESTING.md).

**Deploy it:** Railway for the backend, Vercel for the dashboard. Step by step in [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

**Build the Android app** (or [download the folder](#source-and-developer-downloads) first):

```powershell
cd android
gradle clean assembleDebug
# the APK: app\build\outputs\apk\debug\app-debug.apk
```

After installing, the footer in the app's Settings shows the version. If it is not the one you built, the build failed and the old APK was installed: read the Gradle output.

---

## Versions and releases

| I want to | Run |
|:--|:--|
| See both versions | `python scripts/bump_version.py --show` |
| Release a new SavahBot (dashboard + backend) | `python scripts/bump_version.py web 2.0.1` · add `## SavahBot 2.0.1` to [CHANGELOG](CHANGELOG.md) · commit · redeploy |
| Release a new SavahLive (Android) | `python scripts/bump_version.py app 3.0.1` · add `## SavahLive 3.0.1` to the changelog · `scripts\release-savahlive.ps1` · commit `releases/` |
| Check every place agrees | `python scripts/bump_version.py --check` |
| Refresh the Android source zip | `python scripts/pack_android.py` |

Versions only go up, because Android refuses to install an older build over a newer one. Full guide: [docs/RELEASING.md](docs/RELEASING.md). Everything that ever changed: [CHANGELOG.md](CHANGELOG.md).

---

## Documentation

| I want to | Read |
|:--|:--|
| Deploy it | [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) |
| Know every setting | [docs/ENVIRONMENT.md](docs/ENVIRONMENT.md) |
| Protect sign-in | [docs/SECURITY.md](docs/SECURITY.md) |
| Get push notifications | [docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md), [docs/PWA_AND_PUSH_PLATFORMS.md](docs/PWA_AND_PUSH_PLATFORMS.md) |
| Add weather | [docs/WEATHER.md](docs/WEATHER.md) |
| Float my stream over other apps (Android) | [docs/SAVAHLIVE.md](docs/SAVAHLIVE.md), [android/README.md](android/README.md) |
| Release a new version of the Android app | [docs/RELEASING.md](docs/RELEASING.md) |
| Understand how messages are scored | [docs/MODERATION.md](docs/MODERATION.md), [docs/KNOWLEDGE.md](docs/KNOWLEDGE.md) |
| Back up and restore | [docs/BACKUP_AND_RECOVERY.md](docs/BACKUP_AND_RECOVERY.md) |
| Fix a problem | [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) |
| Everything else | [docs/README.md](docs/README.md) |

---

## Good to know

<details>
<summary><b>The app says "You have 3.0.0" but I built a newer one</b></summary>

The footer shows the version that was actually installed. A build that fails to compile leaves the previous APK in place, so the phone keeps running the old version. Run `gradle clean assembleDebug`, confirm `BUILD SUCCESSFUL`, and install `app-debug.apk` from that build.
</details>

<details>
<summary><b>I changed the web app but the site looks the same</b></summary>

Web changes only show after a redeploy on Vercel. Backend changes need a redeploy on Railway.
</details>

<details>
<summary><b>Why do SavahBot and SavahLive have different version numbers?</b></summary>

They are released separately. A dashboard fix should not force a new APK, and an app fix should not force a redeploy. The server tells phones the newest SavahLive version on its own, so the update card in the app never depends on the dashboard version.
</details>

<details>
<summary><b>Does the phone see my YouTube account?</b></summary>

No. The phone only talks to your SavahBot, with a device key that opens one door: the overlay feed. Pairing alerts you on Telegram, and any phone can be removed in one tap.
</details>

---

## What is and is not verified

Automated tests use fakes, never the real YouTube, Firebase or Open-Meteo, and cannot exercise real browsers, phones, hardware passkeys or PostgreSQL. The list and a manual checklist are in docs/TESTING.md. Expect to fix small things on the first real run; Settings > Developer and the logs are designed to make them easy to find.

---

<div align="center">
<sub>Made for one streamer's chat, kept simple on purpose. · <a href="#downloads">Back to the top</a></sub>
</div>

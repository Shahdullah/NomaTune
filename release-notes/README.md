# Release Notes

Each NomaTune release publishes the contents of `release-notes/v<version>.md`
as the GitHub Release body (ArchiveTune-style format).

## How it works

`.github/workflows/release.yml` runs a **Build release notes** step before
creating the GitHub Release:

1. If `release-notes/v<version>.md` exists, it is used verbatim.
2. Otherwise notes are generated automatically from the commits since the
   previous tag, plus a standard signing note — so a release can never ship
   with a stale or empty body.

## Adding notes for a new release

1. Bump `versionName` / `versionCode` in `app/build.gradle.kts`.
2. Create `release-notes/v<version>.md`.
3. Commit and push — the Release workflow builds all variants, signs them with
   the permanent key and publishes the release with these notes.

## Writing style

Adapted from ArchiveTune's release format, titled for NomaTune:

- One-paragraph summary at the top (what this release is about)
- `## ✨ Highlights` with `###` subsections per area
- Bullet points with bold lead-ins and emoji section headers
  (e.g. `### 🎧 Signed-Out Playback`, `### 🔐 Seamless Updates`)
- `## 🐛 Stability & Bug Fixes`, grouped by area
- Optional `## 🧾 Technical Summary` table for engineering-facing changes
- Close with a **Full Changelog** link between tags

Keep the voice user-facing: describe what a listener noticed and what is fixed
now, not the mechanics alone.

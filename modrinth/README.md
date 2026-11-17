# Modrinth publishing payload

Everything needed to publish **8BitDo Controller Support** (`bta-8bitdo`, version
`1.0.1+8.0.1`) on Modrinth. Nothing has been published yet: the API needs a personal access
token that does not exist on this machine.

| File | Purpose |
|---|---|
| `project.json` | project metadata; `body_file` is replaced by the contents of `description.md` when publishing |
| `description.md` | project body (Modrinth Markdown; the screenshot is referenced from `raw.githubusercontent.com`) |
| `version.json` | version metadata; `changelog_file` is replaced by `changelog.md`, `file` points at the built jar |
| `changelog.md` | version changelog |
| `../tools/publish-modrinth.fish` | creates the project, uploads the icon and the version |

## Metadata in the payload

- **Project:** title `8BitDo Controller Support`, slug `bta-8bitdo`, type `mod`,
  categories `["utility"]`, client side `required`, server side `unsupported`,
  license `MIT`, source and issues pointing at `github.com/psampir/bta-8bitdo`,
  `is_draft: true`.
- **Version:** name and number `1.0.1+8.0.1`, game version `b1.7.3`, loader
  `bta-babric`, type `release`, environment `client_only`, no dependencies, jar
  `build/libs/eightbitdo-1.0.1+8.0.1.jar`.

Two fields look redundant but are not: `project.json` keeps `initial_versions: []` because
the API rejects the request with `missing field initial_versions` without it even though the
field is deprecated, and `version.json` sets `environment: client_only` because the legacy
`client_side`/`server_side` fields are ignored on creation (the project shows `unknown`
until a version declares the environment).

`bta-babric` is a Modrinth **loader**, not a category: categories on Modrinth are limited to
the `utility`, `library`, … list, so it lives in `version.json` → `loaders`. The publish
script also checks the categories against the live tag list and drops unknown ones with a
warning instead of letting the request fail with HTTP 400.

`is_draft` is deprecated in the API but still accepted, and Modrinth always creates projects
as drafts anyway; the project has to be submitted for review on the website after upload.

## One-time: personal access token

1. Open <https://modrinth.com/settings/pats> and create a token with these scopes:
   - `USER_READ`
   - `PROJECT_CREATE`
   - `PROJECT_WRITE`
   - `VERSION_CREATE`
   - `VERSION_WRITE`

   (read scopes for projects/versions are optional.)
2. Store it outside the repository, readable only by you:

   ```fish
   mkdir -p ~/.config/modrinth
   printf '%s\n' '<paste-token-here>' > ~/.config/modrinth/token
   chmod 600 ~/.config/modrinth/token
   ```

3. Revoke it at the same page when you are done, or keep it for later updates.

## Publish

```fish
./gradlew build                          # the jar must exist
tools/publish-modrinth.fish --dry-run    # optional: prints both payloads, no network, no token
tools/publish-modrinth.fish
```

The script:

- reads the token from `~/.config/modrinth/token` and hands it to curl through a mode-600
  header file (`-H @file`), so it never shows up in the process list or in any output;
- creates the project from `project.json` + `description.md` unless `MODRINTH_PROJECT_ID`
  is set, in which case that project id (or slug) is used and nothing is created;
- validates the categories against `/v2/tag/category` and drops unknown ones with a warning;
- uploads `docs/logo/icon.png` with `PATCH /project/{id}/icon?ext=png` (Modrinth's limit is
  256 KiB, the icon is ~239 KiB);
- uploads the jar from `version.json` as version `1.0.1+8.0.1` (multipart `data` + file).

Keep the project id from the output for later releases:

```fish
set -gx MODRINTH_PROJECT_ID <id>
```

## Current state

Published as a draft on 2026-11-16:

- project `hi6SzlXH`, slug `bta-8bitdo` — <https://modrinth.com/mod/bta-8bitdo>
- version `RE4vO4qq` (`1.0.0+8.0.1`, `b1.7.3`, `bta-babric`, environment `client_only`)
- icon and the in-game screenshot (gallery, featured, ordering 0) are uploaded

For later versions, keep `MODRINTH_PROJECT_ID=hi6SzlXH` set and run the script again; it
skips project creation and uploads only the new version.

## Remaining manual step

Click **Submit for review** on the project page: projects are created as drafts and stay
invisible to everyone else until Modrinth approves them.

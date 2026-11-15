# PaperFeed

An Android app that follows nuclear-physics journals and arXiv, sends a notification for every new paper, and lets you sort papers into lists and give them star ratings.

## Features

- **Sources**: PRC, NPA, PLB, Chinese Phys. C, PPNP, Annu. Rev. Nucl. Part. Sci., RMP, NSE, Ann. Nucl. Energy, J. Nucl. Sci. Technol., J. Phys. G, and arXiv nucl-th/nucl-ex. You can add more on the Sources screen:
  - any journal by **ISSN** (through Crossref)
  - any **RSS/Atom** feed
  - an **arXiv** query (`cat:hep-ph AND ti:neutrino`, `au:Witten`, …)
  - an **OpenAlex** keyword search
  - a **Google Scholar** search. This is best effort: Scholar has no API and often answers automated requests with a captcha.
- **Notifications** for each new paper. The collapsed view shows the title; expanding it shows the authors and abstract. Each notification has three buttons:
  - **Read later**
  - your chosen quick list (Lists → ⋮ → *Show as notification button*)
  - **Lists…**, which opens a small dialog where you can tick any lists, create a new one, and set a star rating
- **Home feed**, newest first, with pull-to-refresh, a filter for each source, and search. Each row shows a bold title, the first two authors and the year (small, bottom left), and the journal (bottom right). Tapping an author's name opens all of that author's papers in date order: papers from your feeds plus their OpenAlex profile.
- **Article page**: bold title, authors (each one clickable), journal, volume, date, a DOI link and the abstract. All of this text can be selected and copied. The bottom bar has a 3-star importance rating, a Read-later toggle and *Save to lists*.
- **Lists**: create, rename, reorder and delete them. You can sort a list by date or by rating, and the rows show your stars. You can reach lists from the drawer, the bottom tab, and launcher shortcuts (long-press the app icon).
- **Missing abstracts**: Elsevier and T&F feeds don't include abstracts, so the app looks them up through OpenAlex, Crossref and Semantic Scholar. For new papers it keeps retrying with backoff until the indexes catch up. Inline TeX like `$^{208}\mathrm{Pb}$` is shown as ²⁰⁸Pb.
- **Themes**: Follow system, Material You, Paper, Night, AMOLED, Sepia, Solarized Light/Dark, Nord, Dracula, Gruvbox, Deep Ocean, Forest, Rosé, and a **custom theme editor** (accent, background, surface and text colours). Text size is adjustable.
- **Backup** of your lists, ratings, saved papers, sources and theme:
  - **Google Drive** (Drive API): back up, restore, and an optional automatic daily backup
  - **File backup**: export/import, plus a daily auto-backup to a file you choose. Pick *Drive* in the system file picker to keep that file in Google Drive without any setup.

## New in 1.1

- **Notes**: write notes in a split screen, with the paper and abstract on top and the editor below; drag the divider to resize. Find all notes under Lists → Notes or in the drawer. Each note shows its paper's title, authors, year and DOI.
- **Abstracts**: tap *Fetch abstract*. The app checks OpenAlex, Crossref, Semantic Scholar, arXiv and, if you've added a free key, Elsevier. If none of them has it, the publisher page opens in your browser; copy the abstract, come back, and tap *Use it*.
- **Aa** buttons on Home and on the article page change the text size. On Home you can also choose a layout (List / Compact / Cards) and an abstract preview.
- **Share** a link, a citation, the citation with abstract, or BibTeX. The **Z** button saves straight to the Zotero app.
- **Support** screen (money icon) for Google Play tips. See PUBLISHING.md.
- **Endless feed**: keep scrolling and older articles load automatically, back to each journal's first issue. The feed stays strictly in date order across journals.
- **Search** (magnifier on Home) covers every year of every journal in your sources, plus arXiv. Search by title/keywords or by author, sorted by best match or newest first, with endless scrolling.
- **Author pages** load all of an author's papers page by page.

## Build

### Requirements

- JDK 17 or newer (JDK 21 recommended). Point `JAVA_HOME` at it, or set `org.gradle.java.installations.paths` in your **user** `~/.gradle/gradle.properties`.
- Android SDK with platform 37. Android Studio creates `local.properties` (`sdk.dir=…`) for you; otherwise create it by hand. It is git-ignored.

```bash
./gradlew assembleRelease                    # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest                  # parser unit tests
LIVE_TESTS=1 ./gradlew testDebugUnitTest     # also hits every default feed live
```

Without `keystore.properties` the release build is signed with the debug key, so you can install it directly (`adb install app-release.apk`). For Play Store signing see [PUBLISHING.md](PUBLISHING.md).

Optional settings in the app (stored only on the device, never in the code): a contact email for the Crossref/OpenAlex "polite pool" and a free Elsevier API key.

## Google Drive setup (one time)

Google only lets an app use the Drive API if it is registered with an OAuth client:

1. Go to https://console.cloud.google.com/ → create a project → **APIs & Services → Library** → enable **Google Drive API**.
2. **OAuth consent screen**: choose External, fill in the app name, and add your Google account as a **test user**.
3. **Credentials → Create credentials → OAuth client ID → Android**:
   - Package name: `com.paperfeed`
   - SHA-1: the SHA-1 of the key that signs your build. Get it with `./gradlew signingReport` (or `keytool -list -v -keystore <your.jks>`). Debug and release keys have different SHA-1s, so add one Android client per key you use.
4. That's all; no client ID goes into the code. In the app, open **Settings → Google Drive backup → Back up now** and grant access.

The app asks only for the `drive.file` scope, so it can see nothing in your Drive except its own `PaperFeed-backup.json`.

## Layout

```
data/     Room entities + DAOs, Repository, settings (DataStore)
net/      Crossref / RSS+Atom / arXiv / OpenAlex / Scholar fetchers, abstract lookup, text cleanup
work/     WorkManager refresh, notifications, notification actions, list-picker dialog
backup/   JSON backup format, Google Drive + file backup
ui/       Compose screens and themes
```

## License

MIT. See [LICENSE](LICENSE).

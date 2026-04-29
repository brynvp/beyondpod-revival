# Marketing & Release Plan

> Living document — add ideas as they emerge, flesh out before launch.

---

## App Identity / Rebrand

**Working name:** WttrPlayer (or WitterPlayer / W Player — decide before launch)

**Concept:** The name is a deliberate in-joke for the Wittertainment community (Kermode & Mayo listeners). People who get it feel seen. People who don't aren't the target audience yet. No explanation needed — the right people will just *know*.

**Pre-loaded feed on first install:** Mark Kermode & Simon Mayo's podcast (confirm canonical feed URL — it has moved across BBC → Scala Radio transitions over the years).

**Rebrand TODO (do at the very end before release — touches everything):**
- App ID: `mobi.beyondpod.revival` → `mobi.wttrplayer.app` (or similar)
- Package name throughout all Kotlin source
- `app_name` string resource
- Git repo name + remote URL
- CLAUDE.md, all spec docs
- Play Store listing
- Any hardcoded strings referencing "BeyondPod"
- `AndroidManifest.xml` authorities (WorkManager, FileProvider etc.)
- Notification channel IDs, widget action strings

> Do this as a single session at the very end — it touches hundreds of files and is easy to break if done piecemeal.

---

## Launch Strategy

### Phase 1 — Wittertainment community (warm launch)
1. **Email Kermode & Mayo directly** — not a pitch, a love letter. "I built a podcast app and the first thing it opens with is your show." They love this stuff. If they mention it on air or socials the community floods in already primed.
2. **r/Wittertainment** — post as a fan project, mention the name is an in-joke, let the community stress-test it. These are engaged, technically literate podcast fans who will give real feedback and spread it if it's good.
3. **iwttr connection** — the iwttr app already self-identifies this listener community. Explore whether there's a natural crossover or shoutout opportunity.

### Phase 2 — Broader podcast community
- r/androidapps — "I rebuilt BeyondPod 4.x from scratch, open source"
- r/podcasts — focus on the feature set (SmartPlay, queue, offline-first)
- r/Android

### Phase 3 — General release
- Play Store listing
- F-Droid submission (open source — fits their ethos perfectly)

---

## License

**Current:** MIT

**Problem with MIT:** Anyone can take the code, build a commercial product, and sell it. MIT permits this. The principle feels wrong even if enforcement is impractical.

**Options to consider:**

| License | Use freely | Copy/learn | Commercial product | Notes |
|---|---|---|---|---|
| MIT (current) | ✓ | ✓ | ✓ | Too permissive |
| GPL v3 | ✓ | ✓ | Must open-source derivative | Strong copyleft — commercial use allowed but forces open source |
| AGPL v3 | ✓ | ✓ | Must open-source even SaaS | Strongest copyleft — closes the "SaaS loophole" |
| MIT + Commons Clause | ✓ | ✓ | ✗ (selling prohibited) | Simple add-on to MIT. Not OSI-approved but widely understood |
| PolyForm NonCommercial | ✓ | ✓ | ✗ | Clean, purpose-built for this use case |
| BSL (Business Source) | ✓ | ✓ | ✗ until X years → then Apache | Used by MariaDB, HashiCorp etc. Converts to open eventually |

**Likely best fit:** MIT + Commons Clause, or PolyForm NonCommercial.
- Simple to understand
- Clearly states: free to use, free to learn from, not free to commercialise
- Doesn't stop anyone technically — but it's the principle, and it's on record

**TODO:** Decide license before first public release. Update LICENSE file + all source file headers if using Commons Clause.

---

## Notes / Ideas Parking Lot

- Consider a "hello to Jason Isaacs" easter egg somewhere in the UI
- The Code of Conduct could inspire an onboarding screen tone
- Wittertainees are global — localisation matters eventually

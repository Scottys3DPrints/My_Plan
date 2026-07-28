# Aegis

An intelligent content and app blocker for Android that classifies what it is looking at,
rather than matching a list of names.

Traditional blockers match names — you tell them "block this site," and they block it.
Aegis judges content. An on-device classifier reads what a page actually contains and
decides *what kind of thing it is*, so a site nobody has ever listed is still caught. The
rule you set is "block anything that is adult," not "block this URL."

**Everything runs locally.** There is no account, no server, and no analytics. What you
browse never leaves the phone — see [Privacy](#privacy) for what that means concretely.

**It installs directly.** No Play Store, no developer account, no cable.
→ **[docs/INSTALL.md](docs/INSTALL.md)**

---

## What it does

| | |
|---|---|
| **Category blocking** | Adult, graphic violence, gambling, self-harm, extremist, drugs, social. Each set to Wall, Timed, Warn, or Off. |
| **On-device classifier** | Weighted evidence over page text, title, metadata, hostname and images. Sub-millisecond, deterministic, and it can always say which words caused a block. |
| **Hardened browser** | Where filtering is at full strength, because Aegis controls the renderer and sees the page rather than just the hostname. |
| **App blocking & budgets** | Block an app, cap it at 30 minutes a day, or share one "Social" hour across several. |
| **Grace tap** | Out of time? You can have five more minutes — after sixty seconds of sitting and waiting for them. |
| **No side doors** | "Never reach facebook.com" is enforced across the Aegis browser, other browsers' address bars, in-app webviews, link previews and DNS — not just the app you deleted. |
| **Cooling-off** | Strengthening a rule applies instantly. Weakening one waits 24 hours. Including the setting that controls the wait. |
| **Accountability partner** | Optionally tell someone when you try to weaken your own rules. |
| **Transparency log** | Every block, with its evidence, and a one-tap "this was wrong" that actually retrains the local model. |

---

## Repository layout

```
core/     Pure Kotlin/JVM. No Android dependencies.
          Classifier, rules engine, cooling-off, budgets, DNS/IP packet handling.
          88 unit tests. Runs on any JDK — no Android SDK required.

app/      The Android app.
          Local VPN (DNS filtering), accessibility guard, hardened browser, Compose UI.
```

The split is not ceremony. Every decision Aegis makes — is this page adult, is this budget
spent, does this edit weaken the rules, is this DNS name blocked — lives in `core` and is
tested there. The Android module is wiring: services, screens, and permissions.

### Running the core tests

Needs nothing but a JDK:

```bash
./gradlew -c settings-core-only.gradle.kts :core:test
```

### Building the app

Needs the Android SDK:

```bash
./gradlew :app:assembleRelease
```

Or push a tag and let CI do it — see [docs/INSTALL.md](docs/INSTALL.md).

---

## How the classifier works

Not a neural network, and that is a deliberate choice rather than a shortcut.

Each category carries a lexicon of weighted terms, matched against page text, title,
metadata, URL path and hostname. A match in the title counts for more than the same word
in body text; a hostname match counts for more again. Repeated hits saturate
logarithmically, so a word repeated forty times in a footer cannot manufacture a verdict.
Dampener terms subtract — which is what stops a breast-cancer charity or a
gambling-addiction helpline being blocked for using the vocabulary it has to use. The
result is squashed into a 0–1 confidence that is exactly zero when nothing matched.

Four properties this buys, all of which matter more than a couple of points of accuracy:

- **No model download.** The app is small and works the moment it is installed.
- **Fast.** Well under a millisecond, so it can run on every page load without being felt.
- **Deterministic.** The same page always gets the same verdict.
- **Explainable.** Every block names the words that caused it, which is what makes the
  "this was wrong" button meaningful rather than decorative.

A learned model can be dropped in behind the `ContentClassifier` interface later without
touching a single caller. Starting here means the product is honest about its mistakes
from day one.

### Images

Images contribute a coarse, capped signal — enough to tip a page that text already made
suspicious, never enough on its own to produce a confident block. A skin-tone heuristic
cannot tell a beach photo from pornography and this one does not pretend to; the cap and
the one-tap-to-unblur are the honest response to a weak signal. See the notes in
`ImageSampler.kt` for what it does and does not do well.

---

## Privacy

- No network calls except the browser fetching pages you asked for, and image bytes for
  local classification.
- No account, no server, no analytics, no crash reporting.
- The transparency log stores hostnames, verdicts and the matched terms — capped at 500
  entries, never uploaded.
- Cloud backup and device transfer are **disabled**, deliberately: restoring an old backup
  must not become a way around a cooling-off period.
- The VPN is local. It exists so DNS can be inspected on-device, not so traffic can be
  collected.

---

## What Aegis cannot do

Stated up front, because a blocker that overstates its coverage is worse than one that
does less and says so.

- **DNS-over-HTTPS bypasses the network filter.** An app with its own hard-coded encrypted
  resolver never asks a question the filter can see. The browser and the app guard cover
  much of this; not all of it.
- **It cannot read inside other apps' feeds.** Blurring explicit images inside TikTok's
  feed is not possible on Android without screen-scraping every frame, which would be both
  a battery disaster and a far greater intrusion than this app is willing to be. Aegis
  blocks or budgets those apps instead.
- **iOS is not supported and this design does not port.** Apple permits app limits and a
  network content filter, but not the accessibility APIs the app guard depends on. That
  would be a different product, not a build target.
- **It cannot stop you uninstalling it.** Nothing that could would be distinguishable from
  malware. The friction here is designed to outlast an impulse, not to trap you.

---

## Design notes

Two decisions worth knowing about, both documented at length in the code:

**Cooling-off decomposes an edit.** A settings change is diffed into independent deltas,
each classified as tightening or loosening. The tightening parts apply immediately; the
loosening parts queue. This is why bundling "turn off the adult filter" together with
"block gambling" does not get the first one through faster. `CoolingOffTest` is written as
a series of attacks on this rather than demonstrations of it — including winding the system
clock forward, rebooting, and setting the cooling-off period to zero.

**The accountability partner has no backend.** The concept called for a server; a server
that receives a message every time someone tries to weaken their porn filter would be a
database of the most sensitive thing this app touches. Instead Aegis composes the message
and hands it to your own mail or messaging app. Same friction, no third party. The
trade-off — it prompts rather than guarantees delivery — is stated in the code and in the
settings screen rather than glossed over.

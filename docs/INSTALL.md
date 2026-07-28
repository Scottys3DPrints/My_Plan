# Installing Aegis on your phone

No Play Store, no developer account, no cable, no ADB. You download a file and tap it.

---

## 1. Get the APK

### The easy way — a GitHub Release

Push a tag and CI builds and publishes an installable APK:

```bash
git tag v0.1.0
git push origin v0.1.0
```

When the **Build APK** workflow finishes, the release page has an `aegis-…apk` attached.
Open that page **in the phone's browser** and tap the file.

### Or grab it from any build

Every push to `main` or a `claude/**` branch also builds an APK. Open the repository on
GitHub → **Actions** → the most recent **Build APK** run → **Artifacts** →
`aegis-apk`.

That artifact is a `.zip` (GitHub always zips artifacts). On the phone you will need to
unzip it before the APK is tappable — any file manager will do. This is why a tagged
release is the nicer route: releases attach the `.apk` directly, with nothing to unzip.

### Or build it yourself

Requires JDK 17 and the Android SDK:

```bash
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Transfer it to the phone however you like.

---

## 2. Install it

1. Tap the `.apk`.
2. Android asks whether to allow your browser (or file manager) to install unknown apps.
   Say yes. This is a per-app permission, and you can turn it back off afterwards under
   **Settings → Apps → Special app access → Install unknown apps**.
3. Play Protect may warn that the app is unrecognised. It says that about every app not
   distributed through the Play Store. Choose to install anyway.

---

## 3. Two permissions Aegis needs, and why

Open Aegis. The Home screen shows both, and neither is on until you grant it.

### Network filter (VPN)

Tap the switch and accept Android's VPN prompt.

It is a **local** VPN: the tunnel exists so DNS lookups can be inspected on the device,
and nothing is sent anywhere for analysis. The persistent notification and the key icon in
the status bar are Android's, and it shows them for every VPN — there is no way to
suppress them, and you should be suspicious of any app that tries.

One real limitation: Android allows only one VPN at a time. If you already use a VPN for
privacy or work, you cannot run both.

### App & route guard (accessibility service)

This one needs an extra step on Android 13 and newer, **specifically because you
sideloaded the app**:

1. **Settings → Apps → Aegis → ⋮ (top right) → Allow restricted settings.**
2. Then **Settings → Accessibility → Aegis app & route guard → On.**

Without step 1, the toggle in step 2 is greyed out with no useful explanation. Android
added the restriction to stop malware sideloaded by a scam caller from switching on an
accessibility service, which is a good rule that also catches legitimate sideloaded apps
like this one.

The service reads which app is in the foreground (for app blocks and time budgets), and in
browsers it reads the address bar and the page text — that last part is what lets it block
explicit content in Chrome the same way it does in its own browser.

Page text is classified in memory and thrown away on the next scan. It is never written to
storage and never leaves the phone; the only thing kept is what already appears in the
Record: a hostname, a verdict, and the words that matched. Aegis only reads text from a
window that is already showing a web address, so it does not walk the screen of your
messages or your notes.

### Usage access, optional

**Settings → Apps → Special app access → Usage access → Aegis.** Improves the accuracy of
time budgets. Aegis works without it.

---

## 4. Set your rules, then lock them

Aegis starts in **setup mode**: every change applies immediately. Configure categories,
apps, budgets and destinations until they look right.

When you're happy, tap **Lock in my rules** on the Home screen. From then on:

- Making anything **stricter** still happens instantly.
- Making anything **looser** — including unlocking again — waits out the cooling-off
  period, 24 hours by default. The queued change appears under **Waiting** on the Home
  screen with a countdown, and you can cancel it at any time.

If you tap a control after locking and it springs back to its old value, that is the
cooling-off period doing its job, not a bug. A message tells you what is queued and when
it lands, and the control itself shows the same thing with a Cancel button.

Editing the same control again replaces the queued change rather than adding a second one
— and it does **not** restart the countdown, so changing your mind is free but waiting is
still waiting.

---

## 4a. Interrupting endless scrolling

**Rules → Scrolling.** Off by default; the switch turns it on and pre-selects the feed
apps you actually have installed.

This is the one rule that isn't about *what* you reach or *how long*. A daily budget
charges the same minute whether it went on replying to someone or on falling down a feed,
and the classifier has nothing to say because no individual post is the problem. This
watches the shape of the use instead: an unbroken run of scrolling in an app you already
decided was fine.

- **Say something after** — how long one unbroken run may go. Default 10 minutes.
- **Then again every** — how long before it says so again. Default 5 minutes.
- **Which apps** — it only watches what you name. Scrolling is also how you read a long
  article or go back through a chat, and a version of this that interrupted those would
  be off within a day.

A pause longer than 90 seconds ends the run and the count restarts, so glancing at a
message doesn't cost you and putting the phone down genuinely resets it. It also needs
more than 25 swipes before duration counts for anything — otherwise an app left open on a
table would trip it having done nothing.

The screen it shows is not a block. Nothing is against the rules, so there is no bargain
to negotiate: it names the number and the only button puts the phone down. Interruptions
appear in the Record, which is where the feature earns its keep — seeing *23 minutes, 40
minutes, 31 minutes* three evenings running is worth more than any single interruption.

Loosening it — longer runs, fewer reminders, unwatching an app, switching it off —
waits out the cooling-off period like every other loosening. "Just twenty more minutes",
said mid-scroll, is the exact sentence this exists to sit in front of.

---

## 5. Updating without redownloading every time

Once signing is set up **and you have published a release**, Aegis updates itself: it
checks GitHub Releases once a day and, when there's a newer build, offers **Download and
install** in Settings. One tap.

Both halves of that sentence matter. The updater watches **Releases**, not Actions runs —
Actions artifacts are zipped and need a logged-in GitHub session to download, so they
cannot be fetched by an app. Until you push a version tag there is genuinely nothing for it
to find, and it will say so:

> No releases have been published yet, so there is nothing to update to.

Publish one with:

```bash
git tag v0.1.1
git push origin v0.1.1
```

Android will not let any app install silently unless it is a system app or an enterprise
device owner, so the final confirmation dialog is unavoidable — and for a tool like this
that is the right outcome, not a limitation to route around. What you avoid is the
downloading, unzipping and file-hunting.

The first time, Android asks you to allow Aegis to install apps
(**Settings → Apps → Aegis → Install unknown apps**). After that it's tap-tap.

You can turn the daily check off in **Settings → Updates**. It is one unauthenticated GET
to `api.github.com` and sends nothing about you — but it is the only request Aegis makes
that you did not ask for, so it gets a switch.

### Signing is a hard prerequisite, not a nicety

Android installs an update over an existing app **only if both are signed with the same
key**. Without the signing secrets configured, each build falls back to Android's debug
key — and because every CI run starts on a fresh machine, that key is **randomly generated
per build**. Two such builds cannot update each other at all; you would get
*"App not installed"* every time and have to uninstall first, losing your rules.

You can verify this yourself: every build prints its certificate fingerprint in the
**Report signing identity** step of the workflow. Without secrets it differs run to run.
With secrets it is identical every time.

### Alternative: Obtainium

If you'd rather not have the app update itself, [Obtainium](https://github.com/ImranR98/Obtainium)
is an open-source app that watches GitHub Releases and handles updates for sideloaded
apps. Point it at this repository and it will notify you and install. Same signing
requirement applies.

### Setting up the key

```bash
./tools/make-keystore.sh
```

It creates two files and tells you what to paste into
**Settings → Secrets and variables → Actions**:

| Secret | What it is |
|---|---|
| `AEGIS_KEYSTORE_BASE64` | the whole contents of `aegis-release.jks.base64` |
| `AEGIS_KEYSTORE_PASSWORD` | the password you chose |
| `AEGIS_KEY_ALIAS` | `aegis` |
| `AEGIS_KEY_PASSWORD` | the password you chose |

A keystore is binary and a GitHub secret can only hold text, which is what the `.base64`
file is for: the same key written as one line of roughly 5,000 characters. It wraps across
the whole terminal and looks like many lines, so copy it from the file rather than by
selecting it on screen — one dropped character fails the build with an error that does not
say so. `xclip -selection clipboard < aegis-release.jks.base64` (or `pbcopy` on macOS,
`termux-clipboard-set` on Termux) avoids the problem entirely.

Delete the `.base64` file once the secret is saved; it is the signing key in another form.
Keep the `.jks` file. Losing it means no more in-place updates, ever.

---

## Uninstalling

**Settings → Apps → Aegis → Uninstall**, as usual — with two things to know first.

Aegis will not stop you. A blocker that could refuse to be uninstalled would be
indistinguishable from malware, and Android is right to make that impossible. The friction
in this app is deliberately the kind you can walk out of; it is there to outlast an
impulse, not to trap you.

If the app & route guard is on, turn it off in Accessibility settings first — Android
sometimes leaves the entry behind otherwise.

---

## Troubleshooting

**"App not installed."** Usually a signature clash with an existing install. Uninstall the
old one first.

**The accessibility toggle is greyed out.** Do step 1 above — *Allow restricted settings*.

**The filter switch turns itself off.** Another VPN is active. Only one can run at a time.

**I changed a setting and now I can't change it back.** You've locked your rules in.
Loosening waits out the cooling-off period — check **Waiting** on the Home screen, where
you can cancel the queued change or see when it lands. If you want to edit freely again,
**Unlock** on the Home screen, which itself waits the same period.

**Some sites still load.** Expected, and worth understanding rather than treating as a
bug. The network filter reads DNS. An app using its own encrypted resolver
(DNS-over-HTTPS), or connecting straight to an IP address, never asks a question the
filter can see. The Aegis browser filters those pages properly because it renders them
itself, and the app guard catches other browsers by reading their address bar. Between
them the coverage is good; it is not total, and the app says so rather than implying
otherwise.

**"App not installed" when updating.** The two builds are signed with different keys. See
*Signing is a hard prerequisite* above. Settings → Updates shows the key your build was
signed with, and warns you outright if it is the debug key.

**"Check now" says there is nothing new, but there is.** Check what it actually said. "You
are on the latest release" means a release exists and is not newer. "No releases have been
published yet" means the repository has never been tagged — Actions builds are not releases
and the app cannot download them. Push a version tag to publish one.

---

## Does it block sites in Chrome?

Yes, and it now judges pages the same way its own browser does.

**Three layers, in the order they act:**

1. **The DNS filter** covers every app. A blocked hostname gets an unroutable address and
   the page never loads.
2. **The address check.** The guard reads Chrome's address bar and applies your
   destination rules and hostname classification.
3. **The page check.** If a page survives both, the guard reads the rendered text out of
   Chrome's accessibility tree and runs the full classifier on it — so explicit content on
   a site with a perfectly innocent-sounding name is caught on what it contains, not on
   what it is called.

Layer 3 is the one that matters most in practice. The name of a site is chosen by the
people who run it, and choosing a bland one is free.

### Turn off Chrome's Secure DNS

**Chrome → ⋮ → Settings → Privacy and security → Use secure DNS → off.**

Chrome can send lookups over its own encrypted connection, which routes past layer 1.
Layers 2 and 3 still work without this, but DNS stops the page before it loads rather
than after.

### What Chrome still doesn't get

**Images.** In its own browser Aegis can blur one explicit picture on an otherwise fine
page, because it controls the renderer. In Chrome it can only judge the page as a whole.

**Instant verdicts.** The page check runs when the address changes, so there is a moment
where content is on screen before the block appears. The built-in browser decides before
anything renders.

**Warnings.** In Chrome, a page the classifier is *unsure* about is logged but not acted
on — throwing an interstitial over somebody else's browser on a maybe is the wrong trade.
Check **Record** to see what it flagged without blocking, and tighten the category's
sensitivity to Cautious if things are getting through.

### If something still gets through

**Settings → Diagnostics** shows exactly what the guard last saw. Open the page in the
other browser, come back, and read it:

| What it says | What it means | What to do |
|---|---|---|
| *has not seen anything* | The guard is off. | Turn it on from the Home screen. |
| *no web address on screen* | It cannot find the address bar. | Tell me which browser — its address bar needs adding. |
| *page exposed no readable text* | Zero characters harvested. | Tell me the browser and Android version. |
| *Read N characters. Nothing matched.* | It read the page; the words are not in the lexicon. | Tell me the site and I will extend it. |
| *Closest: Adult at 45%* | It read the page and scored it below your threshold. | Set that category to **Cautious** in Rules. |
| *Last page was blocked* + *could not show a block screen* | It judged correctly but could not put anything on screen. | Tell me your Android version. |
| *Last block attempt: skipped — … is protected* | It credited the page to the wrong app and stood down. | Should no longer happen; tell me which package it names. |

**Page credited to** in that panel is the other half of the same story. The guard is woken
by *events*, and the app that fires an event is often not the app on screen — the status
bar repainting wakes it while Chrome is in front. It now reads which window it actually
looked at and blames that, and the counter tells you how often it had to correct itself.

If nothing else works, add the site under **Never reach**, which needs no classifier at
all and is enforced by DNS as well as the guard.

**Note:** the guard must be re-enabled in Android's Accessibility settings after an
update that changes what it asks for. Toggle it off and on if diagnostics say it is not
seeing anything.

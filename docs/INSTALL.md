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

The service reads which app is in the foreground (for app blocks and time budgets) and the
address bar of other browsers (to catch a destination you asked never to reach, opened
through someone else's in-app browser). It runs entirely on the device.

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

## 5. Updating without redownloading every time

Once signing is set up, Aegis updates itself: it checks GitHub Releases once a day and,
when there's a newer build, offers **Download and install** in Settings. One tap.

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

It creates the keystore and prints the four values to paste into
**Settings → Secrets and variables → Actions**:

| Secret | What it is |
|---|---|
| `AEGIS_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `AEGIS_KEYSTORE_PASSWORD` | the password you chose |
| `AEGIS_KEY_ALIAS` | `aegis` |
| `AEGIS_KEY_PASSWORD` | the password you chose |

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
*Signing is a hard prerequisite* above.

---

## Does it block sites in Chrome?

Yes — by two independent routes, with one setting you should change.

**The DNS filter** covers every app on the phone, Chrome included. When Chrome looks up a
blocked hostname, Aegis answers with an unroutable address and the page fails to load.

**The app & route guard** reads Chrome's address bar and holds it to the same destination
rules and hostname classification as the Aegis browser. If a blocked site loads, Aegis
takes you back and shows why. This path works even when DNS filtering does not.

### Turn off Chrome's Secure DNS

Chrome can send its lookups over its own encrypted connection (DNS-over-HTTPS), which
routes straight past the DNS filter. Turn it off so both routes work:

**Chrome → ⋮ → Settings → Privacy and security → Use secure DNS → off.**

The guard still catches things without this, but the DNS filter is the faster and quieter
of the two — it stops the page before it loads rather than after.

### What Chrome does *not* get

Full content classification. In the Aegis browser, Aegis renders the page and can read its
text, metadata and images — so a brand-new site with no recognisable name is still judged
on what it contains, and individual images can be blurred on a page that is otherwise
fine. In Chrome, Aegis only ever sees the hostname. That is the honest difference, and it
is why the built-in browser exists.

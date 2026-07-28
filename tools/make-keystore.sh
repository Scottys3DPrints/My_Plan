#!/usr/bin/env bash
# Create the signing key for Aegis, once.
#
# Why this matters for a sideloaded app: Android will only install an update over an
# existing app if both are signed by the same key. Lose this keystore and the only way to
# ship a new version is to uninstall the old one — taking every rule, budget and pending
# cooling-off change with it. Keep the file and the passwords somewhere you will still
# have them in a year.
#
#   ./tools/make-keystore.sh
#
# Then add the four values it prints to the repository's Actions secrets, and CI will
# sign every release build with this key.

set -euo pipefail

KEYSTORE="${1:-aegis-release.jks}"
ALIAS="${AEGIS_KEY_ALIAS:-aegis}"

if [ -f "$KEYSTORE" ]; then
  echo "error: $KEYSTORE already exists. Refusing to overwrite a signing key." >&2
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "error: keytool not found. Install a JDK (17 or later) and try again." >&2
  exit 1
fi

read -r -s -p "Choose a keystore password: " STORE_PASSWORD
echo
read -r -s -p "Confirm: " STORE_PASSWORD_CONFIRM
echo

if [ "$STORE_PASSWORD" != "$STORE_PASSWORD_CONFIRM" ]; then
  echo "error: passwords do not match." >&2
  exit 1
fi

if [ ${#STORE_PASSWORD} -lt 8 ]; then
  echo "error: use at least 8 characters." >&2
  exit 1
fi

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass "$STORE_PASSWORD" \
  -keypass "$STORE_PASSWORD" \
  -dname "CN=Aegis, OU=Personal, O=Aegis, L=Unknown, ST=Unknown, C=GB"

# Written to a file rather than only printed. It is one line of about five thousand
# characters, which wraps across a whole screen and looks like many — selecting that by
# hand is how this step usually goes wrong, and a single dropped character fails the build
# with an error that does not say so.
BASE64_FILE="$KEYSTORE.base64"
{ base64 -w 0 "$KEYSTORE" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n'; } > "$BASE64_FILE"

cat <<INSTRUCTIONS

Created $KEYSTORE
Created $BASE64_FILE ($(wc -c < "$BASE64_FILE" | tr -d ' ') characters, all on one line)

Add these to the repository under Settings → Secrets and variables → Actions:

  AEGIS_KEYSTORE_BASE64    the entire contents of $BASE64_FILE
  AEGIS_KEYSTORE_PASSWORD  the password you just chose
  AEGIS_KEY_ALIAS          $ALIAS
  AEGIS_KEY_PASSWORD       the password you just chose

To copy it without touching the terminal selection:

  Linux    xclip -selection clipboard < $BASE64_FILE
  macOS    pbcopy < $BASE64_FILE
  Termux   termux-clipboard-set < $BASE64_FILE

Or just open $BASE64_FILE in a text editor and select all.

Delete $BASE64_FILE once the secret is saved. It is the signing key in another form,
so it deserves the same care as the keystore itself.

INSTRUCTIONS

cat <<'WARNING'
Do not commit the .jks file. It is already covered by .gitignore.

To build locally with this key instead of using CI, create keystore.properties in the
repository root (also git-ignored):

    storeFile=aegis-release.jks
    storePassword=...
    keyAlias=aegis
    keyPassword=...
WARNING

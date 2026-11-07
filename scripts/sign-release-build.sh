#!/usr/bin/env bash
# Sign a staged release APK or AAB; never modify the input or replace an output.
set -euo pipefail
export LC_ALL=C
if (( $# != 2 )); then
  echo 'Usage: bash scripts/sign-release-build.sh INPUT_APK_OR_AAB NEW_OUTPUT' >&2
  exit 2
fi
for setting in MAVLAB_APK_KEYSTORE_B64 MAVLAB_APK_STORE_PASSWORD MAVLAB_APK_KEY_ALIAS MAVLAB_APK_KEY_PASSWORD MAVLAB_APK_CERT_SHA256; do
  if [[ -z ${!setting:-} ]]; then
    echo "Publication signing input missing: $setting" >&2
    exit 2
  fi
done
[[ $MAVLAB_APK_CERT_SHA256 =~ ^[a-fA-F0-9]{64}$ ]] || { echo 'Expected certificate fingerprint must be SHA-256 hex' >&2; exit 2; }
[[ -r $1 && ! -e $2 ]] || { echo 'Unreadable input or output already exists' >&2; exit 2; }
kind=${1##*.}
[[ $kind == apk || $kind == aab ]] || { echo 'Only APK and AAB builds can be signed' >&2; exit 2; }
umask 077
stage=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/mavlab-release-signing-XXXXXX")
trap 'rm -rf "$stage"' EXIT
printf '%s' "$MAVLAB_APK_KEYSTORE_B64" | base64 --decode > "$stage/key.p12"
if [[ $kind == apk ]]; then
  signer=${MAVLAB_APKSIGNER:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/build-tools/35.0.0/apksigner}
  [[ -x $signer ]] || { echo 'apksigner unavailable' >&2; exit 2; }
  "$signer" sign --ks "$stage/key.p12" --ks-pass env:MAVLAB_APK_STORE_PASSWORD \
    --ks-key-alias "$MAVLAB_APK_KEY_ALIAS" --key-pass env:MAVLAB_APK_KEY_PASSWORD \
    --out "$stage/signed.apk" "$1"
  actual=$("$signer" verify --print-certs "$stage/signed.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
else
  jarsigner -keystore "$stage/key.p12" -storepass:env MAVLAB_APK_STORE_PASSWORD \
    -keypass:env MAVLAB_APK_KEY_PASSWORD -sigalg SHA256withRSA -digestalg SHA-256 \
    -signedjar "$stage/signed.aab" "$1" "$MAVLAB_APK_KEY_ALIAS" >/dev/null
  jarsigner -verify "$stage/signed.aab" >/dev/null
  actual=$(keytool -printcert -jarfile "$stage/signed.aab" | sed -n 's/.*SHA256: //p' | tr -d ':')
fi
[[ ${actual,,} == ${MAVLAB_APK_CERT_SHA256,,} ]] || { echo 'Build certificate does not match the approved release identity' >&2; exit 1; }
cp "$stage/signed.$kind" "$2"
echo "$kind signature verified against the approved certificate."

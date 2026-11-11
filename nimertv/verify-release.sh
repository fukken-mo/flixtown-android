#!/usr/bin/env bash
# Verifies a Nimer TV release APK before it is published:
#   package name, signature validity and that it is signed with the one permanent
#   production certificate (public fingerprint pinned in release-signer.sha256).
# Usage: ./verify-release.sh path/to/NimerTV27.apk
set -euo pipefail
apk="${1:?usage: verify-release.sh <apk>}"
here="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$sdk" ] || { echo "ANDROID_HOME is not set"; exit 2; }
tools="$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)"

expected="$(tr -d ' :\n' < "$here/release-signer.sha256" | tr 'A-F' 'a-f')"
badging="$("$tools/aapt2" dump badging "$apk")"
package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")"
code="$(sed -n "s/^package:.* versionCode='\([^']*\)'.*/\1/p" <<<"$badging")"
name="$(sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
certs="$("$tools/apksigner" verify --print-certs "$apk")"
signer="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs")"
signers="$(grep -c 'certificate SHA-256 digest' <<<"$certs")"

fail() { echo "RELEASE CHECK FAILED: $1"; exit 1; }
[ "$package" = "com.nimertv.app" ] || fail "package is '$package', expected com.nimertv.app"
[ "$signers" = "1" ] || fail "expected exactly one signer, found $signers"
[ "$signer" = "$expected" ] || fail "signed with $signer, expected the production certificate $expected"
grep -q "application-debuggable" <<<"$badging" && fail "this is a debuggable build"

echo "package      : $package"
echo "versionCode  : $code"
echo "versionName  : $name"
echo "signer SHA256: $signer (production certificate)"
echo "file SHA256  : $(sha256sum "$apk" | cut -d' ' -f1)"
echo "file size    : $(stat -c %s "$apk") bytes"
echo "OK"

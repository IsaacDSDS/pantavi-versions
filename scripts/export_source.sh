#!/bin/bash
# Publishes the GPL source of a released version to the public repo (pantavi-versions).
#
#   scripts/export_source.sh <tag> <public-repo-clone>
#
#   tag                 the release tag in this repo, e.g. v1.0.0
#   public-repo-clone   a local clone of https://github.com/IsaacDSDS/pantavi-versions
#
# Replaces the clone's files with the tag's tree minus the private parts, adds the OpenSSL source
# the prebuilt libcrypto.a comes from, then commits and tags. Pushing is left to you.
set -euo pipefail

TAG="${1:?usage: $0 <tag> <public-repo-clone>}"
DEST="${2:?usage: $0 <tag> <public-repo-clone>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Not part of Pantavi: the Pro key app, the working notes and the instructions for agents.
PRIVATE=(prokey HANDOFF.md AGENTS.md CLAUDE.md)
OPENSSL_DIR=app/src/main/cpp/third_party/openssl

die() { echo "error: $*" >&2; exit 1; }

git -C "$ROOT" rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "no tag $TAG in $ROOT"
[ -d "$DEST/.git" ] || die "$DEST is not a git clone"
[ -z "$(git -C "$DEST" status --porcelain)" ] || die "$DEST has uncommitted changes"
! git -C "$DEST" rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "$DEST already has $TAG"

find "$DEST" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
git -C "$ROOT" archive --format=tar "$TAG" | tar -x -C "$DEST"
for path in "${PRIVATE[@]}"; do rm -rf "${DEST:?}/$path"; done

secrets=$(find "$DEST" -path "$DEST/.git" -prune -o -type f \( -name '*.jks' -o -name '*.keystore' \
    -o -name '*.p12' -o -name 'local.properties' -o -name 'keystore.properties' \) -print)
secrets+=$(grep -rIl --exclude-dir=.git --include='*.gradle*' --include='*.properties' \
    -E 'storePassword|keyPassword' "$DEST" || true)
[ -z "$secrets" ] || die "signing material in the export: $secrets"

# libcrypto.a is linked into the app, so its exact source ships with each version.
version=$(sed -n 's/^OpenSSL \([0-9][0-9.]*\).*/\1/p' "$DEST/$OPENSSL_DIR/UPSTREAM.txt")
[ -n "$version" ] || die "no OpenSSL version in $OPENSSL_DIR/UPSTREAM.txt"
tarball="openssl-$version.tar.gz"
cache="$ROOT/build/export-cache"
if [ ! -f "$cache/$tarball" ]; then
    mkdir -p "$cache"
    url="https://github.com/openssl/openssl/releases/download/openssl-$version/$tarball"
    curl -fsSL -o "$cache/$tarball.part" "$url"
    expected=$(curl -fsSL "$url.sha256" | awk '{print $1}')
    actual=$(shasum -a 256 "$cache/$tarball.part" | awk '{print $1}')
    [ "$expected" = "$actual" ] || die "$tarball checksum mismatch"
    mv "$cache/$tarball.part" "$cache/$tarball"
fi
cp "$cache/$tarball" "$DEST/$OPENSSL_DIR/"

git -C "$DEST" add -A
git -C "$DEST" commit -q -m "Pantavi $TAG"
git -C "$DEST" tag -a "$TAG" -m "Pantavi $TAG"
echo "Exported $TAG to $DEST. Check that it builds on its own, then publish it:"
echo "  (cd $DEST && ./gradlew assembleRelease)"
echo "  git -C $DEST push origin HEAD --follow-tags"

#!/usr/bin/env bash
# Builds Baleia's OPA policy bundle (Baleia ADR 0028) from opa/baleia:
#
#   opa/build-bundle.sh <output.tar.gz>
#
# The bundle holds the Rego files of the packages baleia.core and
# baleia.trino, tests and test data left out. Its .manifest claims the roots
# baleia/core and baleia/trino, so a data bundle cannot replace the code, and
# its revision is the policy digest: the SHA-256 of the sorted
# "<path> <sha256>" lines of those Rego files. OPA reports the revision in its
# status, and Baleia compares it with the digest it expects. The digest is
# printed on stdout.
#
# The tarball is deterministic (sorted entries, fixed owner and mtime, gzip
# without a timestamp), so the same sources always give the same bytes.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <output.tar.gz>" >&2
  exit 2
fi
out="$(realpath -m "$1")"

cd "$(dirname "$0")"
mapfile -t files < <(find baleia -type f -name '*.rego' ! -name '*_test.rego' ! -path '*/testdata/*' | LC_ALL=C sort)
if [ "${#files[@]}" -eq 0 ]; then
  echo "$0: no Rego files under opa/baleia" >&2
  exit 1
fi

digest="$(for f in "${files[@]}"; do
  printf '%s %s\n' "$f" "$(sha256sum "$f" | cut -d' ' -f1)"
done | sha256sum | cut -d' ' -f1)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
for f in "${files[@]}"; do
  install -D -m 0644 "$f" "$work/$f"
done
printf '{"revision":"%s","roots":["baleia/core","baleia/trino"]}\n' "$digest" > "$work/.manifest"

(cd "$work" && tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
  --mode=0644 --format=ustar -cf - .manifest "${files[@]}") | gzip -9n > "$out"

echo "$digest"

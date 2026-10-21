#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
destination="${1:?usage: install.sh DESTINATION}"
mkdir -p "$destination/bin"
python3 -m venv "$destination/venv"
"$destination/venv/bin/python" -m pip install --disable-pip-version-check -r "$root/requirements.txt"
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
fetch() {
  curl --fail --location --retry 2 --max-time 180 --limit-rate 2M "$1" -o "$2"
}
fetch https://github.com/koalaman/shellcheck/releases/download/v0.11.0/shellcheck-v0.11.0.linux.x86_64.tar.xz "$temporary/shellcheck.tar.xz"
tar -xJf "$temporary/shellcheck.tar.xz" -C "$temporary"
install -m 0755 "$temporary/shellcheck-v0.11.0/shellcheck" "$destination/bin/shellcheck"
fetch https://github.com/mvdan/sh/releases/download/v3.12.0/shfmt_v3.12.0_linux_amd64 "$destination/bin/shfmt"
chmod +x "$destination/bin/shfmt"
fetch https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_linux_amd64.tar.gz "$temporary/actionlint.tar.gz"
tar -xzf "$temporary/actionlint.tar.gz" -C "$destination/bin" actionlint
printf 'PATH=%s/bin:%s/venv/bin:$PATH\n' "$destination" "$destination"

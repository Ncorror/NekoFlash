#!/data/data/com.termux/files/usr/bin/bash
# Prepare a clean Termux installation for NekoFlash source work and GitHub CI.
# Termux is intentionally not the Android build system for this project.
set -euo pipefail

pkg update -y
pkg upgrade -y
pkg install -y \
  termux-tools \
  git \
  gh \
  openssh \
  unzip \
  zip \
  rsync \
  jq \
  coreutils \
  findutils \
  grep \
  sed \
  gawk

if [ ! -d "$HOME/storage/downloads" ]; then
  termux-setup-storage
fi

printf '\nInstalled command check:\n'
for cmd in git gh ssh ssh-keygen unzip zip rsync jq sha256sum find grep sed awk; do
  if command -v "$cmd" >/dev/null 2>&1; then
    printf '%-12s OK  %s\n' "$cmd" "$(command -v "$cmd")"
  else
    printf '%-12s MISSING\n' "$cmd"
  fi
done

cat <<'NEXT'

Termux is ready for source-control and GitHub CI work.
Android SDK, Gradle build tooling, desktop adb/fastboot and native build toolchains
are intentionally not installed by this script.

Next steps:
  gh auth login
  gh auth status
  gh auth setup-git
  git config --global user.name "YOUR_GITHUB_LOGIN"
  git config --global user.email "YOUR_EMAIL"
  git config --global init.defaultBranch main

Full setup, SSH and CI instructions:
  docs/TERMUX_SETUP.md
NEXT

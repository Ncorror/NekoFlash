#!/data/data/com.termux/files/usr/bin/bash
# Prepare a clean Termux installation for NekoFlash source work and GitHub CI.
set -euo pipefail

pkg update -y
pkg upgrade -y
pkg install -y \
  termux-tools \
  git \
  gh \
  openssh \
  curl \
  wget \
  unzip \
  zip \
  rsync \
  python \
  jq \
  coreutils \
  findutils \
  grep \
  sed \
  gawk \
  tar \
  xz-utils \
  openjdk-17 \
  kotlin \
  clang \
  make \
  cmake \
  ninja

if [ ! -d "$HOME/storage/downloads" ]; then
  termux-setup-storage
fi

printf '\nInstalled command check:\n'
for cmd in git gh ssh curl wget unzip zip rsync python python3 java javac kotlinc jq sha256sum find grep sed awk tar clang make cmake ninja; do
  if command -v "$cmd" >/dev/null 2>&1; then
    printf '%-12s OK  %s\n' "$cmd" "$(command -v "$cmd")"
  else
    printf '%-12s MISSING\n' "$cmd"
  fi
done

cat <<'NEXT'

Next steps:
  gh auth login
  gh auth setup-git
  git config --global user.name "YOUR_GITHUB_LOGIN"
  git config --global user.email "YOUR_EMAIL"
  git config --global init.defaultBranch main
NEXT

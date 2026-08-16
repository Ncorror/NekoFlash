# Termux setup for NekoFlash maintenance

Termux is a **Git/GitHub workstation** for NekoFlash. It is not the Android build system.

The supported split is:

```text
Termux
  -> edit/review source
  -> git branch / commit / push
  -> GitHub CLI
  -> GitHub Actions status, logs and artifacts

GitHub Actions
  -> JDK 17
  -> Android SDK
  -> Gradle
  -> unit tests
  -> lint
  -> APK build
  -> release verification
```

Do not make a local Termux Gradle build a release or validation requirement. Do not require Android Studio, an Android SDK installation in Termux, an emulator, desktop `adb`, or desktop `fastboot` for this workflow.

## 1. Install Termux

Use a current Termux build from an official Termux distribution source. Keep Termux and any Termux plugin applications from the same distribution source so their signing identities remain compatible.

After the first launch, update the package index and install only the source-control and CI helper tools used by this repository:

```bash
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
```

The repository provides the same setup as a script:

```bash
bash scripts/termux-bootstrap.sh
```

That script intentionally does **not** install an Android SDK, Gradle toolchain, emulator, desktop ADB/Fastboot, JDK/Kotlin compiler, or native Android build toolchain.

## 2. Allow access to Android Downloads

NekoFlash helper scripts can import a reviewed ZIP from Android Downloads and can save collected CI evidence there.

Run once:

```bash
termux-setup-storage
```

Then verify:

```bash
ls "$HOME/storage/downloads"
```

If Android asks for file/storage access, grant only the access needed for this workflow.

## 3. Configure Git identity

Set the identity that should appear on commits:

```bash
git config --global user.name "YOUR_GITHUB_LOGIN"
git config --global user.email "YOUR_EMAIL"
git config --global init.defaultBranch main
```

Check it:

```bash
git config --global --get user.name
git config --global --get user.email
```

Do not place access tokens, signing passwords, keystores, or other secrets in Git configuration files committed to the repository.

## 4. Authenticate GitHub CLI

Authenticate interactively:

```bash
gh auth login
```

Select `GitHub.com`. Browser/device authentication is preferred over copying a long-lived token into shell history.

Verify the session and configure Git to use GitHub CLI credentials where applicable:

```bash
gh auth status
gh auth setup-git
```

## 5. Configure SSH for Git

Generate a dedicated Ed25519 key if this Termux installation does not already have a suitable GitHub key:

```bash
ssh-keygen -t ed25519 -C "YOUR_EMAIL"
```

Accept the default path (`$HOME/.ssh/id_ed25519`) unless you already manage multiple keys. A passphrase is recommended for a key stored on a mobile device.

Start an agent and load the key:

```bash
eval "$(ssh-agent -s)"
ssh-add "$HOME/.ssh/id_ed25519"
```

Add the public key to the authenticated GitHub account with GitHub CLI:

```bash
gh ssh-key add "$HOME/.ssh/id_ed25519.pub" \
  --type authentication \
  --title "NekoFlash Termux"
```

Test GitHub SSH authentication:

```bash
ssh -T git@github.com
```

The first connection may ask you to confirm GitHub's host key. Verify the displayed fingerprint against GitHub's published SSH host-key fingerprints before accepting it.

## 6. Clone NekoFlash

Clone through SSH:

```bash
cd "$HOME"
git clone git@github.com:Ncorror/NekoFlash.git
cd NekoFlash
```

Verify the repository and branch:

```bash
git remote -v
git switch main
git pull --ff-only origin main
git status --short
```

A clean `git status --short` produces no output.

## 7. Daily update workflow

Always start from the current protected `main`:

```bash
cd "$HOME/NekoFlash"
git switch main
git pull --ff-only origin main
git status --short
```

Create a short-lived branch before changing files:

```bash
git switch -c work/short-description
```

After reviewing the change:

```bash
git status --short
git diff --check
git diff

git add -A
git diff --cached --check
git diff --cached

git commit -m "short focused commit message"
git push -u origin HEAD
```

Open a Pull Request:

```bash
gh pr create \
  --repo Ncorror/NekoFlash \
  --base main \
  --fill
```

For the repository's guarded publish flow, the helper script can create the short-lived branch, commit, push, and open the PR:

```bash
bash scripts/termux-publish.sh "short focused commit message"
```

The helper never performs a local Android build and never pushes directly to protected `main`.

## 8. Import a reviewed source ZIP

When a reviewed source ZIP is transferred to the Android device, calculate or obtain its expected SHA-256 first. Then run from current clean `main`:

```bash
cd "$HOME/NekoFlash"
git switch main
git pull --ff-only origin main

bash scripts/termux-publish.sh \
  --source-zip "$HOME/storage/downloads/NekoFlash-source.zip" \
  --sha256 "EXPECTED_ZIP_SHA256" \
  "chore: import reviewed source"
```

The script checks that the ZIP looks like one NekoFlash project, preserves `.git`, refuses a dirty import base, verifies the optional SHA-256, and publishes through a PR branch instead of overwriting `main`.

## 9. Watch GitHub Actions

List recent runs of the source-of-truth build workflow:

```bash
gh run list \
  --repo Ncorror/NekoFlash \
  --workflow build.yml \
  --limit 10
```

View one run:

```bash
gh run view RUN_ID --repo Ncorror/NekoFlash
```

Watch it until completion:

```bash
gh run watch RUN_ID --repo Ncorror/NekoFlash --exit-status
```

Read its logs:

```bash
gh run view RUN_ID \
  --repo Ncorror/NekoFlash \
  --log
```

For a Pull Request, watch required checks:

```bash
gh pr checks PR_URL \
  --repo Ncorror/NekoFlash \
  --watch
```

The expected build order in `.github/workflows/build.yml` is:

```text
checkout
-> JDK 17
-> Gradle setup / Android SDK supplied by runner environment
-> unit tests
-> Android lint
-> assembleDebug
-> artifacts
```

A local Termux command is not a substitute for those checks.

## 10. Collect CI evidence and APK artifacts

The repository helper can trigger the `build.yml` workflow and collect its result:

```bash
bash scripts/termux-ci.sh
```

Collect an existing run without starting another:

```bash
bash scripts/termux-ci.sh --run-id RUN_ID
```

Include APK artifacts only when they are actually needed:

```bash
bash scripts/termux-ci.sh \
  --run-id RUN_ID \
  --with-apk
```

The helper writes evidence archives under Android Downloads. APKs are kept in a separate archive from CI logs/evidence.

GitHub CLI can also download workflow artifacts directly:

```bash
mkdir -p "$HOME/storage/downloads/NekoFlash-artifacts"

gh run download RUN_ID \
  --repo Ncorror/NekoFlash \
  --dir "$HOME/storage/downloads/NekoFlash-artifacts"
```

## 11. Merge only after CI is green

Review the PR and its exact head SHA:

```bash
gh pr view PR_URL \
  --repo Ncorror/NekoFlash

gh pr checks PR_URL \
  --repo Ncorror/NekoFlash \
  --watch
```

Merge only after the required checks for that exact revision are green:

```bash
gh pr merge PR_URL \
  --repo Ncorror/NekoFlash \
  --merge \
  --delete-branch
```

After merge:

```bash
git switch main
git pull --ff-only origin main
```

GitHub Actions passing is build/CI evidence. It is **not hardware evidence**. USB/ADB/Fastboot/Sideload/Mi Unlock behavior remains `NOT YET VERIFIED` until the required physical-device validation exists for the exact code/build being claimed.

## 12. Release tags

Do not create a release tag merely because a branch builds. Follow the release workflow documented in `README.md` and `.github/workflows/release.yml`.

The tag must match the project `versionName` exactly as:

```text
v${versionName}
```

Before a tag is created, the exact merged commit must have the required CI and hardware evidence. Release signing material remains in GitHub repository secrets / the controlled release environment, not in the Termux repository checkout.

## 13. What must not be required in Termux

For the NekoFlash A2 maintenance workflow, do not require:

- Android Studio;
- Android SDK / `sdkmanager`;
- local Gradle Android builds;
- an Android emulator;
- desktop `adb`;
- desktop `fastboot`;
- local release signing keys;
- local release APK validation as a replacement for GitHub Actions.

Termux is the control surface for source and CI. GitHub Actions is the build source of truth. Physical hardware is the protocol-validation source of truth.

## 14. Minimal recovery checks

If GitHub CLI authentication stops working:

```bash
gh auth status
gh auth login
```

If Git over SSH stops working:

```bash
ssh-add -l
ssh -T git@github.com
git remote -v
```

If the checkout diverged from `main`, do not force-push `main`. Inspect first:

```bash
git status
git branch -vv
git log --oneline --decorate --graph -20
```

If a CI run fails, inspect the run rather than attempting to reproduce the Android build inside Termux:

```bash
gh run view RUN_ID \
  --repo Ncorror/NekoFlash \
  --log-failed
```

# Publishing

Publishing is configured for Sonatype Central via the OSSRH Staging API compatibility service, matching the HudRendererLib release setup.

Before publishing, create a Sonatype Central account, verify the `io.github.brainage04` namespace, generate a Central Portal user token, and create a GPG key for signing releases.

## Secret handling

Never store publishing secrets in `~/.bashrc`, `$PROFILE`, project files, Gradle command-line arguments, or a repository `.env` file.

### Local workstation

Store the Central Portal token in a password manager. Keep the signing private key in the local GnuPG keyring and let `gpg-agent` provide its passphrase.

Configure only the non-secret GPG selector in `~/.gradle/gradle.properties`:

```properties
signing.gnupg.executable=gpg
signing.gnupg.keyName=<long-signing-key-id>
```

The maintainer's workstation provides `unlock-central-publishing`, `with-central-publishing`, and `lock-central-publishing` helpers. Unlock once to create a mode-`0600` Bitwarden CLI session under `$XDG_RUNTIME_DIR`; each `with-central-publishing` invocation retrieves the Portal token, exposes it only to the child command, and leaves that session available for subsequent publishing commands. Run `lock-central-publishing` when the publishing session is finished.

Prime `gpg-agent` once before running Gradle so parallel signing tasks do not open competing pinentry prompts. On a remote terminal without access to the desktop pinentry, use loopback mode; GPG reads the passphrase as hidden terminal input and caches the unlocked key in the normal per-user agent:

```bash
signing_check="$XDG_RUNTIME_DIR/central-publishing-signing-check"
printf 'Central publishing signing check\n' > "$signing_check"
GPG_TTY="$(tty)" gpg --yes --pinentry-mode loopback \
  --local-user <long-signing-key-id> \
  --detach-sign --output "$signing_check.sig" "$signing_check"
rm -f "$signing_check" "$signing_check.sig"
unset signing_check
```

### CI

CI may continue using secret-store-provided environment variables:

- `CENTRAL_PORTAL_USERNAME`
- `CENTRAL_PORTAL_PASSWORD`
- `SIGNING_KEY`
- `SIGNING_PASSWORD`

The build uses in-memory PGP signing when the signing environment variables are present and GPG-agent signing when `useGpgAgentSigning=true`.

## Publish

On a workstation with these helpers installed, unlock once and then run as many publishing commands as needed:

```bash
unlock-central-publishing
with-central-publishing ./gradlew --no-daemon :publishToMavenCentral
```

The root task stages and signs three distinct Central release concerns in one deployment:

1. the `io.github.brainage04:minecraftmodconventions` runtime artifact used by mod and GameTest code;
2. one Gradle plugin marker artifact for each published plugin ID;
3. the matching implementation module for each plugin marker.

All three are required. A marker-only release resolves the plugin ID but not its implementation; an implementation-only release cannot satisfy the Gradle plugins DSL; omitting the runtime artifact breaks recorder/GameTest helper dependencies.

By default, the staged deployment is uploaded to the Central Portal for manual review (`user_managed` mode). To ask Sonatype to release automatically after validation, run:

```bash
with-central-publishing ./gradlew --no-daemon :publishToMavenCentral -PcentralPublishingType=automatic
```

Lock the reusable vault session explicitly when publishing is complete:

```bash
lock-central-publishing
```

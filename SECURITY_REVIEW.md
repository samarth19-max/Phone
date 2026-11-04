# Security review — hardening/android-15-16

**Review status:** in progress. This is a source-level review, not a penetration test. No Android handset test or successful CI run has been observed for this branch. Do not merge or release on the basis of this review alone.

## Changes implemented on this branch

- The built-in ADB launcher quotes every dynamic shell argument using POSIX single-quote escaping. Regression tests cover spaces, empty strings, and embedded quotes with shell metacharacters.
- The file-backed shell control channel accepts only `START` (exactly three arguments), `STOP`, `RESET`, `QUIT`, and `PING` (no arguments). It rejects malformed IDs, unexpected verbs, invalid argument counts, oversized protocol files, and oversized arguments.
- Control messages are written to unique temporary files and moved into place atomically where the filesystem supports it.
- The shell process validates its startup argument count and token format.
- The privileged shell recorder checks that its output path resolves to the app's sibling `Recordings` directory. Canonical-path checks reject traversal and symlink escapes.
- Rejected ADB identities are surfaced as a re-pair condition.
- Unit tests cover shell quoting, command validation, concurrent requests, and recording-path validation.

## Open security blockers

### High — ADB server identity is not authenticated

The pinned `libadb-android:3.1.1` source uses an `X509TrustManager` that accepts every server certificate (`SslUtils.getAllAcceptingTrustManager()`). The pairing exchange uses SPAKE2 and TLS-exported key material, but the subsequent TLS ADB connection does not pin or otherwise authenticate the server certificate. The mDNS resolver checks that the advertised host is a local interface address and that a listener occupies the advertised port; it does not establish that the listener is the system ADB daemon.

A malicious local service may be able to win `_adb-tls-connect._tcp` discovery and impersonate the ADB endpoint. This needs a protocol-level fix, such as binding the post-pairing connection to an authenticated server identity, or replacing/forking the dependency with a reviewed implementation. Do not describe built-in ADB mode as protected against a malicious local app until this is fixed and tested.

### High — recordings are plaintext at rest

Recordings are written as ordinary `.m4a` files under app-specific external storage. The manifest disables app backup and the FileProvider is restricted to the recordings folder, but the audio itself is not encrypted. Implementing encryption requires coordinated changes to recording finalization, listing, duration extraction, playback, sharing/export, deletion, retention, and key-loss recovery. This has deliberately not been changed blindly because a partial encryption retrofit could make existing recordings inaccessible or leak decrypted temporary files.

### Medium — control token is passed in the shell process command line

The random control token is stored in app-private internal storage and passed as an argument when starting `app_process`. This avoids putting the token in the shared control directory, but command-line visibility within shell-level diagnostics remains a concern. A future protocol revision should transfer the token through a narrowly scoped channel that does not expose it in process arguments, without making it readable to other apps.

### Medium — hidden Android APIs

The shell recorder relies on reflection and hidden `ActivityThread`/audio-context behavior to construct a shell-identity context. These APIs are not a stable SDK contract and need validation on Android 15, 16, and 17 devices. A future OS update can break capture or change the security assumptions.

### Medium — permission revocation is not ADB pairing revocation

Revoking Android 17 local-network permission prevents new local discovery/connections, but it does not revoke the ADB pairing already stored by the OS. The app asks the helper to stop and has a watchdog lease, but physical-device testing is required to prove shutdown behavior across process death, permission revocation, and blocked audio reads.

## Validation still required

1. Obtain a successful GitHub Actions run for the latest branch head, including assemble, unit tests, lint, dependency checks, and APK alignment.
2. Test real incoming and outgoing calls, both audio directions, stop/finalization, app-process death, reboot, and permission revocation on representative Android 15, 16, and 17 devices.
3. Test built-in ADB discovery against a malicious local mDNS responder and verify authenticated server identity before enabling the feature for security-sensitive use.
4. Design and test encrypted-at-rest recordings, including migration of existing plaintext recordings and secure share/export behavior.
5. Review the Shizuku lifecycle and authorization flow, recording retention, crash diagnostics, and file-provider grants on device.

## Scope and limitations

- The review did not prove that every transitive dependency is vulnerability-free.
- Source inspection found no app-owned analytics, telemetry, or upload client in the reviewed paths; this is not proof that no dependency performs network activity.
- The consent UI records the user's attestation; it cannot independently verify that every call participant was informed or agreed.
- A green build does not establish that two-sided call audio works on a particular OEM device.

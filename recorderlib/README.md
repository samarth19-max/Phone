# Vendored callrec recorder module

This module vendors the MIT-licensed recorder source from Youbal's callrec project.
The source is kept in this repository so Android compatibility and safety fixes can be reviewed with the Phone app.

## Detached recorder safety

While the built-in recorder is actively capturing, the host app renews a token-protected owner lease once per second.
The shell server checks the lease while its audio worker is alive. If the app process disappears, the lease expires after five seconds; the shell server stops the capture, drains the encoder, finalizes the `.m4a`, and exits.
An explicit `QUIT` also drains the encoder before the process exits.

This lease is separate from call consent: the app still requires fresh, per-call confirmation before audio capture begins.

## Dependencies

- Shizuku API/provider 13.1.5
- libadb-android 3.1.1
- Conscrypt 2.8.0
- Bouncy Castle 1.86

See the root `THIRD_PARTY_NOTICES.md` for third-party license details.

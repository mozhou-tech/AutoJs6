# Phone Tailnet bridge

This module wraps `tailscale.com/tsnet` behind a small gomobile API used by the
AutoJs6 Phone MCP foreground service. It creates a tailnet-only HTTP listener,
validates the MCP bearer token and forwards JSON-RPC messages to Kotlin.
The tsnet `ipn.StateStore` is delegated back to Kotlin so node identity state is
encrypted with Android Keystore AES-GCM instead of using `tailscaled.state`.

The checked-in `app/libs/phone-tailnet.aar` is built from the versions pinned in
`go.mod` and contains armeabi-v7a and arm64-v8a binaries. x86 emulator ABIs are
intentionally omitted to keep the checked-in artifact small.

Rebuild it with:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./phone-tailnet/build-android.sh
```

The build requires the Go version declared by `go.mod`, an Android SDK/NDK, and
network access for the first module download. `GOSUMDB` defaults to the official
Go checksum database for reproducible dependency verification.

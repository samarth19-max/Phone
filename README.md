# Callrec Phone

A plain, private phone app for Android that **records your calls**. It replaces the dialer, shows who is calling, and saves each call as an audio file on your phone. No account, no ads, no tracking, no cloud: your calls, contacts and recordings never leave the device.
This is a Public build version for the app I made for my current employers. 

It is also the reference app for [**callrec**](https://github.com/Youbal-oops/callrec), the small open-source library that does the recording.

> **Not yet verified on many handsets.** Call-audio capture depends on the phone maker. Please report what works and what does not.

## What you get

- **Recents, dialpad and contacts**, with search that ignores case, accents and punctuation, and finds a number however it is written.
- **A full call screen**: answer, decline, silence the ringer, mute, speaker or Bluetooth, hold, keypad, end.
- **Saved names from everywhere**: Google, phone storage and SIM contacts are merged, and each name shows where it is saved.
- **A note per number**, shown on the call screen when that person calls or you call them.
- **Optional call recording**, initially off. Turn it on explicitly in Settings → Recording; once enabled, eligible one-to-one calls are recorded automatically. Play, share or delete recordings, and optionally delete old ones after 7, 30 or 90 days.
- **A built-in setup guide** (Settings → Setup) with a tick for each step, and a "how it works" list.
- **Problem reports stay on the phone.** If something breaks, Settings shows the error with a Copy button.

## Setup

The same steps are in the app under **Settings → Setup**, with a tick for each one that is done.

1. **Allow restricted settings, then the permissions.** Phone Settings → Apps → Callrec Phone → ⋮ (top right) → **Allow restricted settings** (needed for apps installed from an APK). Then tap **Allow permissions** in the app (contacts, call log, phone, microphone, notifications).
2. **Set Callrec Phone as your default phone app.**
3. **Start recording access** (to record *both* sides of a call; skip it to record with the microphone only). In the app tap **Pair and start**, then:
   1. Turn on **Developer options** (tap *Build number* seven times in *About phone*) and, inside it, **Wireless debugging**. Wi-Fi must be on.
   2. Tap **Pair device with pairing code** and leave that box open.
   3. Pull down the notification from Callrec Phone and type the six-digit code into it.
   You only pair once. **After every restart, open the app and tap Start** (Wireless debugging has to be on while it starts). Needs Android 11 or newer.
   *Prefer the Shizuku app?* Install [Shizuku](https://shizuku.rikka.app/download/), start it ([its guide](https://shizuku.rikka.app/guide/setup/)), and allow Callrec Phone in it. Both work; the app uses whichever is on.
4. **Opt in to recording.** In Settings → Recording, tap **Turn recording on**. Recording stays off until you do this. Once enabled, make a short test call; the recording should appear in the **Recordings** tab. Tell everyone on the call that it is being recorded and follow the law where you are.

## How recording works

Android only lets *system* apps capture the other person's voice during a call. There is no setting to switch on. The ways around it are root, the phone maker's own recorder, or borrowing the rights of Android's `shell` user, which is what `adb` runs as.

Callrec Phone does the last one **by itself**. Wireless debugging (Android 11+) lets a device be paired with a code and then run shell commands. The app pairs with *its own phone*, then starts a tiny recorder process as the shell user. That process records the call audio and saves it in the app's folder; the app only tells it when to start and stop. This is the same idea as Shizuku, built in, so no second app is needed.

It has the same limits as Shizuku: the recorder process does not survive a restart (tap **Start** again), Wireless debugging has to be on while it starts, and phones can differ in whether call audio can be captured at all. Without it, the app records with the microphone, which hears the other person only on speakerphone.

## Privacy

- Recording is off on a fresh install and starts only after you enable it in Settings. There is no account, analytics backend or upload service in the reviewed application/library source. The built-in setup uses local Wireless Debugging discovery and a local-device ADB connection. The app pins Bouncy Castle 1.86 and Conscrypt 2.8.0; CI checks the resolved versions and the native libraries' 16 KB alignment. The bundled libadb-android 3.1.1 discovery code accepts only addresses assigned to this phone's own network interfaces and verifies that the discovered port is listening locally; it does not accept an arbitrary remote mDNS host.
- **About the network permissions.** The app asks for `INTERNET` and Wi-Fi permissions for the local Wireless Debugging discovery and connection. On Android 17 it also requests `ACCESS_LOCAL_NETWORK` at the point when you enable the built-in Wireless Debugging recorder; this is not requested for microphone or Shizuku recording. Android requires `INTERNET` for *any* network socket, even one to the phone itself, and that is the only reason: pairing uses `127.0.0.1`; starting the recorder uses the ADB endpoint discovered by mDNS and validated by libadb-android 3.1.1 against this phone's own network interfaces and a locally listening port. The app does not accept arbitrary remote mDNS hosts. Nothing in the code contacts any other server, and the source is here to check (search for `http` or `Socket`). If that is not acceptable to you, the microphone mode and the Shizuku-app mode need none of it, and you can remove the permission and build the app yourself.
- Recordings are kept in the app's own folder (`Android/data/dev.youbal.callrecphone/files/Recordings`). Other apps cannot read them. Sharing is always an explicit action you take.
- The recorder process talks to the app through a few small files in that folder, protected by a random secret kept in the app's private storage, so another app cannot give it orders.
- Saved names are read from your contacts only to show and search them, and are not stored by the app.

## Recording calls and the law

Recording a call can require the consent of everyone on it, depending on where you live. **You are responsible for following the law.** Tell people when you record them.

## Build it yourself

You need JDK 17 and the Android SDK (API 37). The project targets API 37 and uses Android Gradle Plugin 9.5.0 with Gradle 9.6.0.

```
git clone https://github.com/samarth19-max/Phone
cd Phone
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The recording library comes from JitPack. To work on the library and the app together, clone [callrec](https://github.com/Youbal-oops/callrec) **next to this folder**; the build uses that copy automatically.

## Android compatibility notes

The app's minimum Android version is API 26 (Android 8.0); it is built and targeted against API 37. The source includes edge-to-edge inset handling and predictive Back handling for the custom contacts-detail screen.

The built-in recorder discovers Wireless Debugging using mDNS and connects to a local interface address discovered by mDNS and verified as belonging to this device. Because this app targets API 37, Android 17 blocks local-network communication until the user grants `ACCESS_LOCAL_NETWORK`. The app declares this permission and requests it only when the user starts or pairs the built-in Wireless Debugging recorder. If the user denies it, built-in ADB recording remains unavailable, while Shizuku and microphone modes remain available. Android 15 and 16 do not prompt for this permission.

Android 15+ compatibility still needs device-side testing. Call-audio capture depends on the handset/OEM, and the hidden `AudioRecord.Builder.setContext` API used by the shell recorder is not a stable Android SDK contract. Passing a build does not prove that two-sided call recording works on every device.

## Roadmap

- Start the recorder automatically after a restart when Wireless debugging is already on.
- Optional on-device transcription.
- Voicemail and call blocking.

## Licence

MIT. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

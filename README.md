# Digital Volume for Android TV

Lets the remote's volume keys control the **optical (S/PDIF) output** on Android TVs whose firmware sends it at a
fixed level, the way a Chromecast scales its own output. The TV speakers stay silent.

| Volume bar | Settings |
|---|---|
| ![Volume bar](docs/volume-bar.png) | ![Settings](docs/settings.png) |

## The problem

On some MediaTek Android TVs (found on a Noblex/Skyworth set, codename `shinjuku`, Android TV 11) the optical output
always plays at full level:

- The volume keys only move Android's media volume, and the audio HAL applies it to the TV speakers alone. S/PDIF is
  taken before that point.
- "Speakers: External audio system" only sets that volume to 0. The next Vol+ turns the TV speakers back on.
- The volume keys never reach apps or accessibility key filters: the system routes them straight to AudioService.

With speakers or a DAC on the optical output, the only volume control left is the one on the amplifier.

## How it works

- A `DynamicsProcessing` effect on the global output mix (session 0) sets an input gain. The mix is attenuated
  before the HAL splits it to the speakers and S/PDIF, so the optical output follows it.
- The app listens to the system's volume broadcasts, turns each key press into a step of its own level
  (30 steps of 1.5 dB) and puts the media volume straight back to 0, so the TV speakers stay silent.
- Its own volume bar sits over the system one, which no app can turn off.
- Mute toggles the digital level as well.
- Some apps (Disney+) send Dolby Digital Plus to a compress-offload output that the chip decodes, past the mix.
  Shortly after a new player starts, the app enables a neutral effect for a moment. Android invalidates offloaded
  tracks when a non-offloadable effect is enabled, and media3 (the player Disney+ uses) recovers by reopening the
  track as PCM through the mixer, where the gain applies. You see a short buffering pause at the start of each title.

## What it cannot do

**HDMI, AV and Live TV.** Their audio is a hardware patch (`hdmi-in -> speaker`, no software bridge in
`dumpsys media.audio_flinger`): it never passes through Android's mix, so no app can change its level. The settings
screen lets you pick what the keys do there:

- **TV control** (default): the keys drive the TV speakers with the factory volume bar, and that volume is
  remembered separately from the apps' level.
- **Fixed**: the TV speakers stay off; control the volume on the connected device or on the amplifier.

**Apps whose player does not fall back to PCM.** The offload workaround above relies on the app's player
reopening the track as PCM. Tested: Disney+, Netflix, YouTube. Not tested: Max, Prime Video and others. An app that
reopens offload again is left alone after one try, so it plays normally with a fixed level. Check any app with
`adb shell dumpsys media.audio_flinger`: an active track on the `MIXER` thread is controlled.

**Passthrough audio.** Set the TV's digital output to **PCM**. With an encoded (passthrough) setting the TV sends the
Dolby stream as it is, and nothing on the TV can change its level.

## When it is useful

- Your TV feeds powered speakers, a DAC or an amplifier through the optical output, and the TV volume does not change
  what you hear there.
- Your amplifier has no ARC/CEC, so the TV remote cannot control it.
- Most of what you watch plays in apps on the TV itself (Netflix, YouTube, Disney+ here).

## Tested on

| TV | Chip | Android | Firmware |
|---|---|---|---|
| Noblex NoblexTV (Skyworth, `shinjuku`), Argentina | MediaTek m5621 | Android TV 11 | RTO9.230812.001 |

The key handling relies on how this firmware reports volume changes. Other firmware may report them differently;
`adb logcat -s DigitalVolume` shows what the service sees.

## Install

You need adb access to the TV (Developer options > USB debugging; network debugging on most sets).

```sh
adb connect <tv ip>
./install.sh
```

Then set the TV's digital output to PCM and turn the amplifier up to the loudest level you will want: the TV remote
now goes from silence to that level.

Open **Digital Volume** from the app grid to turn it off (the TV works as it came from the factory) or to choose what
the keys do on HDMI, AV and Live TV.

To remove it: `./install.sh --uninstall`.

## Permissions and privacy

- `MODIFY_AUDIO_SETTINGS`: the gain effect, the media volume reset and the short neutral effect for offloaded
  tracks. `registerAudioPlaybackCallback` (no permission) tells it when a player starts.
- Accessibility service: only window-change events, to know whether an app or an input is in front and to show the
  volume bar over the system one. It does not read screen content or filter keys.
- No internet permission. Nothing leaves the TV.

## License

MIT

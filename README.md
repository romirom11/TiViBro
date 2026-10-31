<p align="center"><img src="design/banner_tivibro.png" alt="TiViBro" width="640"></p>

**TiViBro** is a free IPTV player for Android TV. Add the playlist from your IPTV provider and watch live TV, or go
back in time: pause, rewind and play any past programme from the archive, all with the TV remote.

<p align="center"><img src="docs/screenshots/home.png" alt="Home: continue watching and what is on now, over the playing video"></p>

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/home-movies.png" alt="Films and series with posters"><br>Films and series on your channels today</td>
    <td width="50%"><img src="docs/screenshots/programme-view.png" alt="Programme view"><br>TV guide: watch any past programme</td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/channel-list.png" alt="Channel list"><br>Channel list with what is on now and next</td>
    <td><img src="docs/screenshots/player.png" alt="Player"><br>Pause, rewind, restart or go back to live</td>
  </tr>
</table>

## What it does

- **Watch the archive.** Pause live TV, rewind, start the current programme from the beginning, or pick any past
  programme in the TV guide (if your provider keeps an archive).
- **Continue watching.** Stop in the middle of a programme and pick it up later where you left off.
- **TV guide** for every channel, with days, descriptions and progress.
- **Home page** over the playing video: what you are watching, what to continue, what is on now, what has just
  started, what starts soon, and films and series with posters and ratings. You choose which channel groups it
  looks at in Settings > Home page.
- **Favourites, recent channels and hidden channels.** Hide whole groups you never watch.
- **Several playlists**, each with its own favourites and settings.
- **Android TV home screen.** A TiViBro row with what to continue and what is on now (Android 8 and newer).
- **7 languages:** Ukrainian, English, Russian, German, Polish, Spanish and Kazakh.
- **Bigger or smaller text.** Make the whole interface from 90% to 150% of its normal size.
- **Updates itself** when a new version comes out.
- Runs well on older and slower TVs.

## Install

TiViBro works on Android TV and Google TV devices with Android 6.0 or newer.

1. Get the latest `TiViBro-vX.Y.Z.apk` from the [Releases](https://github.com/romirom11/TiViBro/releases) page onto
   your TV. Two easy ways:
   - install the **Downloader** app from the Play Store on your TV and open the Releases page in it, or
   - download the APK on a computer, copy it to a USB stick and open it with a file manager on the TV.
2. When Android asks, allow that app (Downloader or the file manager) to install unknown apps.
3. Install, then open TiViBro from your apps.

## Add your playlist

You need the link to your M3U playlist. Your IPTV provider gives it to you, usually on your account page.

1. Open TiViBro. It shows a QR code.
2. Scan the QR code with your phone (the phone must be on the same Wi-Fi as the TV).
3. Paste the playlist link into the page that opens and tap **Send**.

The TV loads your channels and starts playing. To add another playlist later, open Settings > Playlists > Add
playlist.

## Remote control

| Key | What it does |
| --- | --- |
| ▲ ▼ or CH+ CH- | Next / previous channel |
| ◀ | Channel list |
| ▶ | TV guide of the channel |
| OK | Player panel: ◀ ▶ rewind and fast-forward, OK pause, ▼ for restart, previous / next programme and back to live |
| Hold OK | Channel menu: add to favourites, hide |
| BACK | Home page. BACK on Home returns to the channel. In the channel list or TV guide, BACK closes it |
| PLAY/PAUSE | Pause, even live TV |
| MENU | Main menu: Home, channel groups, settings, exit |

## FAQ

**Where do I get channels?**
From your IPTV provider. TiViBro is only a player: it has no channels of its own and does not sell subscriptions.

**The archive does not work on a channel.**
Your provider has to offer an archive (often called catch-up or timeshift) for that channel. Channels without one can
only be watched live.

**There is no TV guide.**
In Settings > TV guide, keep "Auto" or choose another guide source. You can also send your own guide link from your
phone.

**How do I update?**
TiViBro checks for new versions by itself and tells you when one is ready. Choose "Update to X.Y.Z" in the main menu,
or Settings > Check for updates. The first time, Android asks you to allow TiViBro to install apps.

**Is my data sent anywhere?**
No. There are no accounts, no analytics and no tracking. Your playlist, favourites and history stay on the TV. The app
only connects to your provider (channels), your TV guide source, GitHub (to check for updates) and, if posters are
turned on, TMDB: then only the titles of the programmes shown on Home are sent to find posters, never your playlist
or links. You can turn posters off in Settings > Home page.

## More

Building from source and technical details: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

TiViBro is free software under the [GNU General Public License v3.0](LICENSE). Posters and ratings come from
[TMDB](https://www.themoviedb.org); this product uses the TMDB API but is not endorsed or certified by TMDB.

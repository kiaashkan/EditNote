# EditNote

[فارسی](README.fa.md) | English

A tiny, fully offline Android text editor — no ads, no account, and no
internet permission at all. Written from scratch in native Kotlin using only
the Android framework (no third-party libraries), so the APK stays small.

Built by KiA Ashkan, with the help of Muse, as a clean replacement for ad-heavy editors.

**Download:** see the [Releases](../../releases) page — grab
the latest `EditNote-vX.Y.Z.apk` and install it. Android 8.0 or newer; the same APK
works on every phone and processor (the app contains no native code).

## Your files stay yours

EditNote keeps **no private file storage inside the app**. The home screen
shows the device files you recently opened, most recent first — you can
star files as bookmarks, but a bookmark never changes a file's place in
the list. New
files are created through the system file picker — you choose the name and
the folder. If a file is deleted on the device, it is simply gone; nothing
important hides inside the app.

## Features

**Editor**
- Multiple open files with tabs (`*` marks unsaved changes), line numbers
- Icon mini toolbar: Open, Undo, Redo, Find, Save, Close tab — and Find opens an inline find bar with a match counter and previous/next buttons
- Three tidy header menus: **File** (New, Open, Save, Save As, Bookmarked,
  Reload, Close tab, Share, Print, Settings), **Edit** (Select All, Paste,
  Insert date & time, Increase/Decrease indent, Go to Line,
  Find & Replace, Statistics) and **Options** (Visual style, Encoding,
  Syntax, Word Wrap, Read Only), drawn as modern card popups
- Find & Replace (single or replace all), Go to Line, text statistics
- Real syntax highlighting for 22 languages and 16 visual styles
  (GitHub, Monokai, Dracula, Solarized, …)
- A custom-built editor engine that opens very large files — 100,000+
  lines — smoothly, with progressive syntax highlighting
- Opens **any** file as text: the file picker has no format filter, so
  .js, .json, .xml, .md, extension-less files and everything else open
  without being greyed out
- Per-file encoding: UTF-8, UTF-16, UTF-32, windows-1256, windows-1252,
  windows-1251, ISO-8859-1, US-ASCII, UTF-16BE, UTF-16LE, with BOM detection
- Files opened read-only from other apps (for example Google Drive) are
  marked "Read-only from source"; Save simply creates a copy in a place
  you pick — no error, and from then on the copy saves normally
- Print support
- Optional accessory symbol bar (Tab, cursor arrows, common symbols)
- Undo/redo history per tab

**Home**
- Recent device files with bookmarks and search; a natural A–Z sort
  (numbered files order 1, 2, 5, 10), reset list and settings are direct
  icon buttons in the header
- Floating **+** button: two small actions (New file, Open a file) pop up right above it

**Settings**
- A modern grouped-cards layout with switches; an About card with the
  maker's GitHub, an **Update EditNote** row that opens the latest
  GitHub release (the app still requests zero permissions, so updates
  happen in your browser), and a **Report a bug** row that opens your
  mail app addressed to the maker
- Theme (System / Light / Dark) and accent colors (Orange by default, plus
  Black/White that adapts to the theme, Green, Pink, Purple, Cream) — every
  control in the app follows the chosen color
- Font size, line spacing, monospace font, line numbers, word wrap
- Auto indent, indentation unit (Tab / 4 spaces), word suggestions,
  auto capitalize, undo with the Back button
- Autosave with optional confirmation message and interval (30 s – 5 min)
- Default encoding (UTF-8, UTF-16, UTF-32, Windows-1256, Windows-1252,
  Windows-1251, ISO-8859-1, US-ASCII, UTF-16BE, UTF-16LE)
- Line break style (LF / CRLF)

## Privacy

- Zero permissions requested — not even INTERNET
- Fully offline; no analytics, no ads, no accounts
- Files are read and written only where you open or save them

## Release naming

Releases follow [Semantic Versioning](https://semver.org):

- Tag: `v<major>.<minor>.<patch>` (for example `v1.0.0`)
- Release title: `EditNote v<major>.<minor>.<patch>`
- APK asset: `EditNote-v<major>.<minor>.<patch>.apk`
- Every release APK is signed with the same release certificate, so new
  versions install as updates over older ones.

## Installing

When you install the APK, Google Play Protect may say
**"App scan recommended — Play Protect hasn't seen this app before."**
That prompt appears for *every* app installed from outside Google Play;
it is not an error and it does not mean the app is unsafe. Choose
**Install without scanning** (or let it scan once — EditNote requests zero
permissions and works fully offline). After a clean scan, the prompt
normally stops appearing for EditNote.

## Building

Normal build: `./gradlew assembleDebug` (Android Studio / Gradle).

This repo also ships `build-manual.sh`, a Gradle-free build that uses only
`aapt2` + `kotlinc` + `d8` from a local Android SDK — handy in restricted
environments. Releases are signed with a private release keystore that is
**not** part of this repository.

## Project facts

- Package: `com.kiaashkan.editnote`
- minSdk 26 (Android 8.0), targetSdk 34
- Kotlin, Android framework only, no third-party dependencies

## ❤️ Support EditNote

EditNote is free — no ads, no tracking. If it helps you and you'd like
to support its development, you can donate here:

- **Coin:** GRAM
- **Network:** TON — please send **only GRAM on the TON network** to this
  address. Sending a different coin, or using a different network, will
  lose the funds.

```
UQB6Wphj-CHN1kSwU5fzgMUval6SdJljEhX-52q5qP-yKbTg
```

Thank you! ❤️

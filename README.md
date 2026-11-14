# EditNote

A tiny, fully offline Android text editor — no ads, no account, and no
internet permission at all. Written from scratch in native Kotlin using only
the Android framework (no third-party libraries), so the APK stays small.

Made by KiA as a clean replacement for ad-heavy editors.

**Download:** see the [Releases](../../releases) page — grab
`EditNote-v1.0.0.apk` and install it. Android 8.0 or newer; the same APK
works on every phone and processor (the app contains no native code).

## Your files stay yours

EditNote keeps **no private file storage inside the app**. The home screen
shows the device files you recently opened (bookmarks pinned first). New
files are created through the system file picker — you choose the name and
the folder. If a file is deleted on the device, it is simply gone; nothing
important hides inside the app.

## Features

**Editor**
- Multiple open files with tabs (`*` marks unsaved changes), line numbers
- Icon mini toolbar: Open, Undo, Redo, Find, Find previous/next, Save, Close tab
- Full menu: New, Open, Open recent, Save, Save As, Reload, Close tab,
  Select All, Paste, Insert date & time, Increase/Decrease indent,
  Go to Line, Find & Replace, Statistics, Share, Print, Input method,
  Word Wrap, Read Only, Settings
- Find & Replace (single or replace all), Go to Line, text statistics
- Basic syntax highlighting for code files; print support
- Optional accessory symbol bar (Tab, cursor arrows, common symbols)
- Undo/redo history per tab

**Home**
- Recent device files with bookmarks, search, sort A–Z, clear history
- Floating **+** button with a slide-up panel: create a new file or open one
- Resume last session with the same tabs

**Settings**
- Theme (System / Light / Dark) and accent colors (Orange by default, plus
  Black/White that adapts to the theme, Green, Blue, Purple, Teal) — every
  control in the app follows the chosen color
- Font size, line spacing, monospace font, line numbers, word wrap
- Auto indent, indentation unit (Tab / 4 spaces), word suggestions,
  auto capitalize, undo with the Back button
- Autosave with optional confirmation message and interval (30 s – 5 min)
- Default encoding (UTF-8, UTF-16, Windows-1256, Windows-1252, ISO-8859-1)
- Line break style (LF / CRLF), text-file filter for the open picker,
  resume session

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

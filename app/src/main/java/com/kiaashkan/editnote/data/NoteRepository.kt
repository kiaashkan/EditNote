package com.kiaashkan.editnote.data

// Removed in v0.3.0.
//
// EditNote used to keep its own notes inside the app's private folder.
// KiA asked for that to go away: files the user works on must live on the
// device where the user put them — if a file is deleted, it is gone, and
// nothing important hides inside the app. The home screen now shows
// recently opened device files (see RecentFiles), and new files are
// created through the system file picker so the user chooses the location.
//
// This file intentionally contains no code; it stays in the repository
// only so the removal is visible in history.

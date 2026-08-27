Bundled Emoji Font Packs
========================

Drop any .ttf emoji font file(s) directly into this folder.

Naming rule: the filename (without ".ttf") becomes the name shown to the user
in Settings > Emoji > Emoji Font Packs - exactly as you type it, including
spaces and capital letters. No code changes needed.

Example:
  "Bubble Style.ttf"   ->  shown as  "Bubble Style"
  "Cute Round.ttf"     ->  shown as  "Cute Round"

The app scans this folder at runtime (BundledEmojiFonts.kt), so packs are
picked up automatically the next time the app runs after you add/rename/
remove a file here. This is intentionally an assets/ folder (not res/font/)
because res/font/ resource names cannot contain spaces, capital letters, or
most punctuation - assets/ has no such restriction.

# Segmentation test harness (ML Kit Subject Segmentation)

A debug-only screen that answers one question before you build the editor: **does on-device ML Kit cut out real reseller photos well enough to post?** Pick photos per category, eyeball the cutouts on white and on dark, rate each one, and get a CSV with timing and mask-quality signals.

A standalone app ("Seg Test"). Every push to `main` is built by GitHub Actions and the APK is published as the **latest** release.

## 1. Install

**Easiest:** open this repo's **Releases → Latest build** on your phone and install `app-debug.apk`. Each build is signed with a fresh debug key, so uninstall the previous Seg Test before installing a new one.

**Or build it yourself:** open the folder in Android Studio and run the `app` configuration. There's no Gradle wrapper checked in, so let Android Studio use its bundled Gradle (8.11+), or run `gradle wrapper` once.

**Or drop it into your own app:** copy `app/src/main/java/com/example/segtest/`, add the dependencies from `app/build.gradle.kts`, and add the `meta-data` and `activity` entries from `app/src/main/AndroidManifest.xml`.

The model is delivered by Play services, not bundled. The harness waits for it, runs one warm-up inference, and reports that cold-start time separately so it doesn't skew the per-image numbers.

**How it runs:** each photo is decoded at 2048 px (the export size). ML Kit segments a downscaled copy at the size you pick (1024 / 1600 / 2048), using per-subject masks only, because the whole-image foreground outputs came back corrupted on the first test phone. The mask is scaled up to 2048 px, and an edge-cleaned version (shrink ~2 px, then soften) is produced alongside the raw one.

## 2. Shoot the test set

Shoot like a real seller: phone camera, home lighting, bed or floor or door, no studio. **5 shots per category, 40 total.** Mix your own clothes with a few borrowed items.

| Category | What to shoot | What it tests |
| --- | --- | --- |
| White on white | White tee / shirt / sneakers on a white sheet or wall | Low-contrast edges, holes punched in the garment |
| On hanger | Dress or jacket on a hanger against a door | Does the hanger come out as part of the item or as a separate subject |
| Fringe / fur / knit | Fringe bag, faux fur, chunky knit, lace | Fuzzy edges |
| Shiny / metallic | Sequins, patent leather, jewellery, sunglasses | Reflections of the room read as background |
| Black on dark | Black jeans or bag on a dark floor or sofa | The mirror of white-on-white |
| Busy background | Item on a patterned bedspread or cluttered room | Clutter rejection |
| Flat lay | Folded or laid-flat clothing from above | Thin parts (straps, sleeves, laces) |
| Easy control | Coloured item on a plain contrasting background | Baseline; should almost always pass |

Run the set at **1024 px**, then again at **1600 px**. First results showed 2048 px gives the same masks as 1600 px, only slower.

## 3. Rate and read the results

Rate the **cleaned** cutout against one question: **would I post this as-is?**

- **Pass**: post it.
- **Fixable**: a 5-second touch-up (brush a small missed bit) would make it postable.
- **Fail**: needs real work or a reshoot.

Then compare the Cleaned and Raw previews and mark the edge cleanup **better / same / worse**. "Cleaned · dark" is the default preview because dark backgrounds make white-on-white misses obvious. "Edges" paints uncertain pixels magenta.

Auto-flags are hints, not verdicts:

| Flag | Meaning | Likely cause |
| --- | --- | --- |
| HOLES | Background showing through inside the item (>2% of its area) | White on white, sheer fabric |
| FRAGMENTED | Mask split into pieces; the biggest piece is <90% | Hanger separated, straps lost |
| SOFT_EDGES | >20% of the item is uncertain | Fringe, fur, motion blur |
| NO_SUBJECT / ATE_BACKGROUND | Grabbed almost nothing / almost everything | Low contrast, item fills frame |
| TOUCHES_EDGE | Item runs off the photo | Framing, informational only |

Output lands in `Android/data/<app id>/files/segtest/session_<time>/`: `results.csv` (rewritten on every rating), plus, for each shot, the original, raw and cleaned transparent cutouts (`_cutout_raw.png`, `_cutout_clean.png`), raw and cleaned on-dark images, and the edge map. Pull it with:

```
adb pull /sdcard/Android/data/com.oam.segtest/files/segtest/
```

## 4. Decide

Suggested bar for "on-device ML Kit is good enough for v1" (test on a mid-range phone, not just your daily driver):

- **Pass + Fixable ≥ 85% overall**, and **Pass ≥ 70%** for white-on-white and hanger, the two shots resellers take most.
- **Median warm latency ≤ 1.5 s** at 1600 px (a batch of 10 should finish in about 15 s).

What to do with failures:

- **White on white or black on dark fails** → add capture guidance ("use a contrasting backdrop") and a quick brush to restore missed areas. Only if that's not enough, consider a server model for those shots, which breaks the near-zero-cost plan.
- **Hanger comes out as its own subject** → good news: you can let the user tap to drop it, or drop small separate subjects automatically. Check `subjects` and `subject_box_shares` in the CSV.
- **Hanger merges with the garment** → plan a manual eraser for v1.
- **Fringe/fur is soft** → acceptable on white backgrounds; an edge-refinement pass (guided filter on the mask) can come later.
- **Shiny items fail** → small category for most resellers; don't let it block v1.

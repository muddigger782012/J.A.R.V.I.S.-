# Approved Developer artwork

Imported from the owner's `1-jarvis_approved_artwork.zip` without redrawing or recoloring.
The original archive and its `README.txt` are retained here, outside Android resources.

| Asset | Android resource | Original SHA-256 |
| --- | --- | --- |
| `developer_header_reference.png` — actually JPEG, 1536×512 | `drawable-nodpi/developer_approved_header.jpg` | `5eda3e2efed91ee83761b989c06d4f926a5071ed790e0dc79acbec40b68c40d5` |
| `developer_reactor_launcher.png` — RGBA PNG, 318×318 | `drawable-nodpi/developer_arc_reactor_icon.png` | `bcd0fe7499b57c43907e9f1b6cf82ca4dad84741403a0dacf666871ce7a5ff06` |

Archive SHA-256: `92b34c80a031f82f0c4123e0e798313a2e6a271f1da107fcc8b4253178bf37f9`.
Both resource files retain the exact supplied bytes. Only the header's extension changes
to match its actual format. The already imported launcher PNG is reused unchanged.

MainActivity references `R.drawable.developer_approved_header` directly and displays the
whole composite with `FIT_CENTER`, adjusted bounds and no tint. Its original lettering,
reactor details, border, colors and 3:1 aspect ratio remain together. No generated
wordmark, animated ring layers or separate title is drawn over it. These fixed artwork
colors are intentional and owner-approved; this does not establish all-theme visual validation.

The application manifest references `@drawable/developer_arc_reactor_icon`. Android
launchers may apply their own masks; actual launcher/device review remains pending, as
the supplied README explains. Build verification does not establish device appearance.

`python3 developer/tools/verify_approved_artwork.py` verifies the source archive and
resource bytes without modifying them. Ordinary debug/signed-preview builds verify
committed artwork and do not extract, convert, commit or push replacement images.

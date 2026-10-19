# Approved J.A.R.V.I.S. Developer artwork (locked reference)

The approved artwork must be integrated **without redrawing or substituting** the header.

## Required files

- `developer/src/main/res/drawable-nodpi/developer_approved_header.png` — exact 1536 × 512 reference; SHA-256 `5eda3e2efed91ee83761b989c06d4f926a5071ed790e0dc79acbec40b68c40d5`.
- `developer/src/main/res/drawable-nodpi/developer_arc_reactor_icon.png` — transparent 512 × 512 icon; SHA-256 `a0fc91552667885a126b9509366938f070d004435e6a5114f70045fbc8e278f0`.

The files are provided in `jarvis_artwork_integration_ready.zip` from the project conversation. They are **not present in the repository yet**. Never replace them with approximated XML/vector art.

## Rendering requirements

1. Preserve the exact header wordmark, divider, cyan frame, reactor details and aspect ratio. Do not separately render a second wordmark or second border over the approved banner.
2. The reactor center remains stationary. Only the reactor arc and outer ring rotate, in opposite directions. The banner text and frame never rotate.
3. The launcher icon uses the transparent reactor image, not the banner and not an opaque black/white square. Apply Android adaptive icon safe-area sizing.
4. Never apply `developer/tools/transparent_reactor.py` to these approved PNGs; the thresholding step can erase copper/cyan details.
5. Keep the personal Developer app distinct from Jarvis Beta and do not modify Beta artwork or configuration.

## Release gate

Before publishing an artwork release, verify the two files exist, match their hashes, build a signed Developer APK, and compare a device screenshot against the approved reference. A passing build alone does not establish a visual match.

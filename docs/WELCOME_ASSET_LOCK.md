# Welcome Artwork Lock

The welcome artwork is a user-owned project asset.

- Source file: `app/src/main/res/drawable/bg_welcome.jpg`
- Dimensions: `900 × 1600`
- SHA-256: `d16195d6ab022a4ec8f9686a1d750a4dd83595140e68f718c992df8a0a60a8f7`

## Elements that must remain unchanged

- girl;
- cat;
- green Android device;
- cable between the phone and Android;
- rainy alley;
- illuminated signs and lamps;
- original composition and relative placement.

## Allowed changes

Only native Android UI overlays may change: title, permission indicators, consent control, buttons, hints and scrims. The JPEG must not be regenerated, stretched, recolored, cropped into a replacement asset or have UI baked into it.

The permanent guard in `scripts/check_project.py` verifies the exact SHA-256 before CI proceeds.

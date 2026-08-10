# USB connection troubleshooting

## USB-C role negotiation

When using a direct USB-C to USB-C cable, Android may sometimes negotiate the wrong USB role or expose the wrong device first.

Expected state:

- NekoFlash host phone: USB host
- target phone: USB device

Wrong state:

- the NekoFlash host phone shows that it is charging from the target device;
- an unexpected device appears first;
- normal Android ADB `DEVICE` mode does not attach to the intended target.

Recovery steps:

1. Unplug and reconnect the cable.
2. Swap cable ends.
3. Rotate one or both USB-C connectors.
4. Try another data-capable USB-C data cable.
5. Wait until NekoFlash shows the intended target device.
6. Only then start ADB shell, sideload or flash operations.

Do not start flash, sideload or shell operations while the detected device is not the intended target.

This note applies to normal Android ADB `DEVICE` mode. Fastboot and Recovery/SIDELOAD are separate modes and remain covered by their own validation tests.

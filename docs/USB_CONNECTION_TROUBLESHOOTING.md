# USB connection troubleshooting

## USB-C role negotiation

When using a direct USB-C to USB-C cable, Android may sometimes negotiate the wrong USB data role or USB power role.

## Expected connection

- NekoFlash phone: USB host
- NekoFlash phone: power source
- target/patient phone: USB device
- target/patient phone: power sink
- power direction: from the NekoFlash host phone to the target/patient phone

In this state, the target/patient phone may show that it is charging from the NekoFlash host phone. That is expected.

## Incorrect USB role

The connection is wrong if:

- the NekoFlash host phone shows that it is charging from the target/patient device;
- the target/patient device behaves as the USB host;
- an unexpected device appears first;
- normal Android ADB `DEVICE` mode does not attach to the intended target.

Do not start flash, sideload or shell operations while the detected device is not the intended target.

## Recovery steps

1. Unplug and reconnect the cable.
2. Swap cable ends.
3. Rotate one or both USB-C connectors.
4. Try another data-capable USB-C cable.
5. Wait until NekoFlash shows the intended target device.
6. Confirm that the NekoFlash phone is the host and the target/patient phone is the connected USB device.
7. Only then start ADB shell, sideload or flash operations.

## Scope

This issue is documented for normal Android ADB `DEVICE` mode.

Fastboot and Recovery/SIDELOAD are separate modes and remain covered by their own validation tests.

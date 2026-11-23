# Cherry Kernel All-in-One for the Galaxy Tab S9 FE+ Wi-Fi

Requires SM-X610 (gts9fepwifi), Samsung Android 15 firmware X610XXU7CYE1, and TWRP R7. This release builds on the Cherry Kernel 5.15.153 device tree.

## Installation

1. In TWRP, open **Install** and select **SM-X610-X610XXU7CYE1-Cherry-All-in-One-TWRP.zip**.
2. Use **Volume Up** to select Samsung stock or **Volume Down** to open the Cherry choices.
3. Under Cherry, use **Volume Down** to cycle through the options and **Volume Up** to select.
4. Choose whether to include Cherry Manager 1.3.0. The app needs root permission for full kernel control.
5. Check the final selection. **Volume Up** installs it. **Volume Down** cancels.

| Choice | boot | init_boot |
| --- | --- | --- |
| Samsung stock | Original Samsung image | Original Samsung image |
| Cherry without root | Cherry Kernel | Original Samsung image |
| Cherry with KernelSU Next | Cherry Kernel with KernelSU Next 3.4.2 | Original Samsung image |
| Cherry with Magisk | Cherry Kernel | Stock ramdisk patched with Magisk 31.1 |

The package verifies the current firmware and both target images before writing. It verifies both partitions after writing and attempts to restore the prior images if a write fails. It uses the stock Samsung boot image layout and writes only the boot and init_boot partitions.

With a writable data partition, the rooted choices can install Cherry Manager on the next Android boot using the selected root manager's late-start service. The one-time script removes itself after the attempt. For the rootless choices or if recovery cannot prepare that service, the APK is copied to accessible storage for installation from Android. Cherry-Manager.apk can also be extracted directly from the ZIP. Grant the app root permission in the applicable root manager to enable kernel writes.

## Changes and credits

- Cherry defaults to [TCP BBR](https://github.com/google/bbr/blob/master/Documentation/bbr-quick-start.md) with fq packet scheduling and keeps CUBIC available.
- The kernel enables [power-efficient workqueues](https://docs.kernel.org/core-api/workqueue.html) by default for eligible queues.
- [Samsung Open Source](https://opensource.samsung.com/) provides the device kernel baseline. [KernelSU Next](https://github.com/KernelSU-Next/KernelSU-Next/tree/v3.4.2) and [Magisk](https://github.com/topjohnwu/Magisk/tree/v31.1) supply the optional root components.
- The [AROMA Installer](https://github.com/amarullz/AROMA-Installer) inspired the selection flow. The menu uses the TWRP install log and volume keys because the SM-X610's R7 recovery kernel has no legacy framebuffer driver.
- Cherry Manager's features drew inspiration from [MaxManager](https://github.com/nader295/MaxManager).

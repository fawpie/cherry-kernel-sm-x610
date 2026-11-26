# Cherry Kernel for Galaxy Tab S9 FE+ Wi-Fi

Cherry Kernel is a custom Linux 5.15.153 kernel built from Samsung’s stock source for the **Galaxy Tab S9 FE+ Wi-Fi (SM-X610, gts9fepwifi)**. It runs with Samsung’s stock Android userspace and vendor modules. A single **multi-choice TWRP installer** lets you install Cherry with or without root or restore the Samsung stock kernel.

> **Test release.** The target is Samsung **One UI 7.0 (Android 15), build X610XXU7CYE1**, with TWRP R7 for SM-X610. The earlier Cherry KernelSU Next build booted and provided working root on this firmware. This release’s updated kernel images passed build, installer and module ABI checks. On-device boot testing of the exact ZIP and its four paths is still pending.

[Download the test release](https://github.com/fawpie/cherry-kernel-sm-x610/releases/tag/v0.1.0-test.1) · [Samsung firmware release notes](https://doc.samsungmobile.com/SM-X610/027790230914/tur.html)

## What Cherry adds to Samsung stock

| Area | Change |
| --- | --- |
| TCP congestion control | Builds in BBR and selects it by default. CUBIC remains available. These choices apply to TCP traffic. |
| Packet scheduling | Builds in `fq` and selects it as the default queue discipline, providing per-flow queuing and pacing support. |
| Workqueues | Enables power-efficient scheduling by default for workqueues that opt into the kernel’s power-efficient behavior. |
| Root choice | Offers a rootless Cherry build, a build with KernelSU Next 3.4.2, and a Cherry option using Magisk 31.1 in `init_boot`. |
| KernelSU Next integration | Uses device-specific static ARM64 syscall dispatch for its root hooks and retains Samsung’s RKP, KDP, UH, and Clang CFI settings. |
| Device compatibility | Preserves Samsung’s `5.15.153-android13-3-31153516` kernel release string so the stock module ABI and loading paths can be retained. Cherry branding appears in the kernel build line. |
| Manager integration | Exposes a read-only kernel ABI and variant identifier to Cherry Manager. Runtime tuning uses the device’s available kernel and Android controls. |

Cherry keeps the stock CPU and GPU frequency tables and Samsung’s thermal limits. The changes above are build and configuration changes. This test release has no battery, latency, FPS, or throughput measurements.

[Linux workqueues](https://docs.kernel.org/core-api/workqueue.html) · [Google BBR](https://github.com/google/bbr/blob/master/Documentation/bbr-quick-start.md)

## Compatibility

| Component | Supported test target |
| --- | --- |
| Tablet | Samsung Galaxy Tab S9 FE+ Wi-Fi, `SM-X610`, `gts9fepwifi` |
| System | Samsung stock One UI 7.0, Android 15 |
| Recovery | TWRP R7 built for SM-X610 |
| Bootloader | Unlocked |

Use the exact firmware above. Support for other AP builds, the SM-X616 cellular model, and GSIs has not been established.

## Installation

1. Confirm the model and build number under **Settings → About tablet → Software information**. Back up your data and keep the matching stock `boot` and `init_boot` images available before starting.
2. Download `SM-X610-Cherry-All-in-One-TWRP.zip` and verify it against `SHA256SUMS.txt` from the release. On Windows, use `Get-FileHash <file> -Algorithm SHA256`. On Linux, use `sha256sum -c SHA256SUMS.txt --ignore-missing`.
3. Boot into TWRP R7, select the ZIP under **Install**, and swipe to start. The installer menu appears in the recovery log. **Volume Up** selects Samsung stock. **Volume Down** opens the Cherry choices.
4. In the Cherry menu, use **Volume Down** to cycle through the root options. Press **Volume Up** to select. The installer then asks whether to include Cherry Manager. **Volume Up** includes it and **Volume Down** skips it.
5. Review the final choice. Press **Volume Up** to flash or **Volume Down** to cancel. The installer times out without flashing if a choice is left unanswered for 60 seconds.
6. After the installer reports success, select **Reboot System**. Check the kernel build line in **Software information**. For a rooted choice, use the matching root manager to verify root and grant it to Cherry Manager if desired.

The package uses the volume keys and TWRP’s install log for its interactive menu. TWRP R7 for this tablet lacks the legacy framebuffer driver used by the original AROMA GUI.

| Menu choice | `boot` | `init_boot` |
| --- | --- | --- |
| Samsung stock | Original Samsung kernel | Original Samsung ramdisk |
| Cherry without root | Cherry Kernel | Original Samsung ramdisk |
| Cherry with KernelSU Next 3.4.2 | Cherry Kernel with built-in KernelSU Next | Original Samsung ramdisk |
| Cherry with Magisk 31.1 | Cherry Kernel | Samsung ramdisk patched with Magisk 31.1 |

The stock and rootless choices restore the original `init_boot` and remove Magisk root. Each choice sets the `boot` and `init_boot` pair as listed. The installer checks the model, bootloader build, partition sizes, installed images and payload hashes. It verifies partition writes and attempts to restore the original images if a write or verification fails. Keep the stock images available in case recovery is interrupted.

## Cherry Manager 1.3.0

**Full kernel control requires root permission.** With a rooted installation and writable `/data`, the optional app is installed on the next Android boot. For rootless installs, or when the boot hook cannot be queued, the installer copies the APK to accessible storage. The same signed APK is available as a separate release asset and inside the ZIP.

The app shows available CPU, GPU, thermal, memory, storage and network controls. It provides five profiles, per-app rules, a temperature-based frequency cap, read-back verification, a one-step undo, and optional profile application at startup. Samsung RAM Plus offers **2, 4, 6 and 8 GiB** on this firmware. Changing the size saves it for the next reboot. Available readings can be viewed without root.

## Validation

- **On device, stock One UI 7.0 / X610XXU7CYE1:** the earlier Cherry KernelSU Next build booted and provided root. Cherry Manager 1.3.0 was installed. Its RAM Plus control saved a 6 GiB choice and restored the original 4 GiB choice without changing active swap during the test.
- **This release, build verification:** both kernel variants built successfully. All 286 unique Samsung stock modules were checked with zero unresolved symbols and zero symbol CRC mismatches.
- **This release, installer verification:** the four choices, APK copy and queue paths, cancellation, and rollback after an injected write failure were verified with test images. The finished ZIP passed archive and SHA-256 verification.
- **Pending:** a full flash and boot of this exact ZIP on the tablet through TWRP R7. Use the first boot to confirm the selected kernel and, where applicable, the root manager.

## Source and credits

The release includes a complete corresponding kernel source archive with the Samsung tree, local KernelSU Next source, and both embedded configuration files. Review the source changes in [kernel/](kernel/) and see [BUILD.md](BUILD.md) for the source baselines and build procedure. The TWRP scripts and Cherry Manager app source are included in this repository.

Cherry is maintained by [fawpie](https://github.com/fawpie). Kernel sources and root components come from [Samsung Open Source](https://opensource.samsung.com/), [Linux](https://www.kernel.org/), [KernelSU Next v3.4.2](https://github.com/KernelSU-Next/KernelSU-Next/tree/v3.4.2), and [Magisk v31.1](https://github.com/topjohnwu/Magisk/tree/v31.1). The multi-choice recovery flow was inspired by [AROMA Installer](https://github.com/amarullz/AROMA-Installer). Cherry Manager’s device-aware controls drew inspiration from [MaxManager](https://github.com/nader295/MaxManager).

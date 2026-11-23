## Cherry Kernel v0.1.0-test.1

**First public test release for the Samsung Galaxy Tab S9 FE+ Wi-Fi (SM-X610).** Built on Samsung’s stock Linux 5.15.153 source for **One UI 7.0 / Android 15, X610XXU7CYE1**.

### Changes in this build

- TCP BBR and the `fq` queue discipline are built in and selected by default. CUBIC remains available.
- Eligible kernel workqueues use power-efficient scheduling by default.
- Both Cherry variants retain the Samsung module ABI, frequency tables and thermal limits.
- One multi-choice TWRP ZIP installs Samsung stock, rootless Cherry, Cherry with KernelSU Next 3.4.2, or Cherry with Magisk 31.1.
- Optional Cherry Manager 1.3.0 exposes the supported device controls and Samsung RAM Plus size presets. **Full kernel control requires root permission.**

### Install and verify

Use [the installation guide](https://github.com/fawpie/cherry-kernel-sm-x610/blob/v0.1.0-test.1/README.md#installation) for the exact supported device, partition behavior, and volume key selections. Use `SHA256SUMS.txt` to verify the ZIP and other downloads.

An earlier Cherry KernelSU Next build booted with working root on the same stock firmware. This release passed build and installer simulations, including write-failure recovery. Both kernels matched the symbol versions of all 286 checked stock modules. A full TWRP flash and boot of this exact release is pending.

### Downloads

| Asset | Contents |
| --- | --- |
| `SM-X610-X610XXU7CYE1-Cherry-All-in-One-TWRP.zip` | Multi-choice TWRP installer |
| `Cherry-Manager-1.3.0.apk` | Optional Android app |
| `Cherry-Kernel-SM-X610-v0.1.0-test.1-source.tar.gz` | Corresponding Samsung/Cherry and KernelSU Next source, configs and patches |
| `SHA256SUMS.txt` | SHA-256 checksums |

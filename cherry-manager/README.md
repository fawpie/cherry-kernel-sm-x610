# Cherry Manager

Cherry Manager is an Android kernel manager for the Galaxy Tab S9 FE+ Wi-Fi (SM-X610), developed on Samsung Android 15 firmware `X610XXU7CYE1` with Cherry Kernel and KernelSU Next. It reads the kernel's ABI and variant when Android grants access. The Samsung kernel exposes compatible controls on the same model.

- English tablet UI with Light, Dark and System appearance options
- Live CPU cluster and Mali GPU clocks, thermal sensors, cooling states, ZRAM, display, power and network readings
- Five profiles, per-app rules and a temperature-based frequency cap. Ultra Battery Saver lowers CPU and GPU frequencies with separately verified limits for each CPU cluster
- Supported CPU/GPU minimums, maximums and governors, Android power and animation controls, memory reclaim, storage scheduler and queue settings, and TCP controls
- Samsung RAM Plus size selection from the choices reported by the tablet. The selected size takes effect on the next reboot
- Local suggestions based on battery and thermal readings
- Read-back verification, a local change history and one-step undo
- An optional startup profile after Android starts. A profile applied manually in that boot takes priority

Magisk or KernelSU Next is required for kernel writes. App-specific rules additionally require usage access. Automatic sessions start on demand and can be stopped from their notification. Accessible readings remain available without root. CPU and GPU profiles use the device's frequency tables and leave Samsung's thermal protections in place.

## Build

Run `Build.ps1` with Java, a directory containing Android Build Tools 35, and an Android 35 `android.jar`. The script produces `Cherry-Manager.apk`. It keeps the signing key outside the project directory.

## Credits

Feature inspiration from [MaxManager by nader295](https://github.com/nader295/MaxManager), especially device-dependent controls, per-app profiles and checking written values. Cherry Manager has its own source, interface and artwork.

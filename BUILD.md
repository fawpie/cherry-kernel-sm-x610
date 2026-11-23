# Building Cherry Kernel

## Source baselines

- Samsung Open Source package `SM-X610_EUR_15_Opensource.zip`, published for `X610XXU7CYE1`. The kernel source is its `Kernel.tar.gz` payload. The device configuration is `s5e8835-gts9fepwifixx_defconfig`.
- KernelSU Next `v3.4.2` at `4c706b5938665866ebe16944a481a7b91e3396af`. The KSU variant uses the Cherry changes recorded in `kernel/kernelsu-next-cherry.patch`.
- Android LLVM `clang-r450784d` (Android build 8490178, Clang/LLD 14.0.6).

Download and extract `Cherry-Kernel-SM-X610-v0.1.0-test.1-source.tar.gz` from the release on a Linux filesystem. It contains the complete modified source tree and the configuration embedded in each released kernel. The archive keeps `source/drivers/kernelsu` as a relative symlink to the included KernelSU Next source. The patches show the changes against the listed upstream sources.

## Build the kernel image and modules

Set `CLANG_DIR` to the extracted Android LLVM directory that contains `bin/clang`. Install the usual Linux kernel host dependencies, including GCC/build tools, bc, Bison, Flex, OpenSSL and ELF development headers, pahole, cpio, XZ, Python 3, dtc and depmod. From the extracted source archive:

```sh
export CLANG_DIR=/path/to/clang-r450784d
export PATH="$CLANG_DIR/bin:$PATH"
export ARCH=arm64 LLVM=1 LLVM_IAS=1
export PLATFORM_VERSION=13 TARGET_SOC=s5e8835 DEPMOD=depmod DTC_FLAGS=-@
export KBUILD_BUILD_VERSION=2 KBUILD_BUILD_USER=fawpie KBUILD_BUILD_HOST=Cherry-Kernel
export KBUILD_KERNEL_BRAND='Cherry Kernel'

# Choose standard or ksun.
variant=standard
out="$PWD/out/$variant"
mkdir -p "$out"
cp "configs/$variant.config" "$out/.config"

# The release archive omits Git history. Pass the original KSU version metadata.
make -C source O="$out" LOCALVERSION=-android13-3-31153516 \
    KSU_GIT_VERSION_VALID=1 KSU_GIT_VERSION=3355 KSU_GIT_TAG=v3.4.2 olddefconfig
make -C source O="$out" LOCALVERSION=-android13-3-31153516 \
    KSU_GIT_VERSION_VALID=1 KSU_GIT_VERSION=3355 KSU_GIT_TAG=v3.4.2 \
    -j"$(nproc)" Image modules
```

The kernel image is `out/<variant>/arch/arm64/boot/Image`. The `kernel/validation/` reports summarize the comparison with the firmware’s stock modules. Preserve the exact version suffix when building against those modules.

## Package layout

The [installer](installer/) verifies and installs paired `boot` and `init_boot` images. Release assembly repacks the kernel images into the matching Samsung v4 boot container. The Magisk option uses the official v31.1 patcher on the stock `init_boot` ramdisk. The Galaxy Tab S9 FE+ Wi-Fi builds carry no kernel in `init_boot`. The APK and hashes are bundled during assembly.

`installer/build.py` records the release assembly and validation steps. It takes the firmware images, verified toolchain outputs and signed APK as inputs. The script expects the original workspace layout shown by its input paths. [Cherry Manager’s build instructions](cherry-manager/README.md) cover its separate Android build.

[Samsung Open Source](https://opensource.samsung.com/) · [KernelSU Next source](https://github.com/KernelSU-Next/KernelSU-Next/tree/v3.4.2) · [Magisk v31.1 source](https://github.com/topjohnwu/Magisk/tree/v31.1) · [Android LLVM prebuilts](https://android.googlesource.com/platform/prebuilts/clang/host/linux-x86/)

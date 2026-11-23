#!/usr/bin/env python3
"""Assemble the SM-X610 TWRP package from the verified WSL build outputs."""

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


PROJECT = Path(__file__).resolve().parent
WORKSPACE = PROJECT.parent.parent
FIRMWARE = "X610XXU7CYE1"
MAGISK_APK_SHA256 = "89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7"
BOOT_SIZE = 64 * 1024**2
INIT_BOOT_SIZE = 16 * 1024**2


def hash_file(path, algorithm="sha256"):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def magiskboot(tool, directory, *args):
    subprocess.run([tool, *map(str, args)], cwd=directory, check=True,
                   env={**os.environ, "PATCHVBMETAFLAG": "false"})


def unpack_boot(tool, image, directory, has_kernel):
    directory.mkdir()
    magiskboot(tool, directory, "unpack", "-h", image)
    require((directory / "kernel").is_file() == has_kernel and
            (directory / "ramdisk.cpio").is_file() != has_kernel,
            f"Unexpected kernel or ramdisk layout in {image}")


def repack_boot(tool, stock, kernel, output, work, stock_header):
    stage = work / output.stem
    unpack_boot(tool, stock, stage, has_kernel=True)
    shutil.copyfile(kernel, stage / "kernel")
    magiskboot(tool, stage, "repack", stock, output)
    require(output.stat().st_size == BOOT_SIZE, f"Unexpected boot image size for {output}")

    check = work / (output.stem + "-verify")
    unpack_boot(tool, output, check, has_kernel=True)
    require(hash_file(check / "kernel") == hash_file(kernel), f"Kernel repack changed {kernel}")
    require(hash_file(check / "header") == stock_header, f"Header repack changed {stock}")


def archive_file(zip_file, source, name, mode=0o644):
    entry = ZipInfo(name)
    entry.create_system = 3
    entry.external_attr = (0o100000 | mode) << 16
    entry.compress_type = ZIP_DEFLATED
    with source.open("rb") as reader, zip_file.open(entry, "w") as writer:
        shutil.copyfileobj(reader, writer)


def build(base):
    firmware = base / "firmware-check"
    tool = base / "toolchains/magiskboot-31.1-x86_64"
    stock_boot = firmware / "boot.img"
    stock_init = WORKSPACE / f"kurtarma-{FIRMWARE}/init_boot-stock-{FIRMWARE}.img"
    magisk_init = firmware / "cherry-magisk-v31.1/init_boot-magisk-v31.1.img"
    magisk_apk = WORKSPACE / "Magisk-v31.1.apk"
    manager_apk = WORKSPACE / "cherry-manager/Cherry-Manager.apk"
    standard = base / "out/cherry-tuned-standard/Image"
    kernelsu = base / "out/cherry-tuned-ksun/Image"
    require(hash_file(magisk_apk) == MAGISK_APK_SHA256, "Magisk APK hash mismatch")
    require(stock_boot.stat().st_size == BOOT_SIZE, "Unexpected stock boot size")
    require(stock_init.stat().st_size == magisk_init.stat().st_size == INIT_BOOT_SIZE,
            "Unexpected init_boot image size")

    output = PROJECT.parent / f"SM-X610-{FIRMWARE}-Cherry-All-in-One-TWRP.zip"
    with TemporaryDirectory(prefix="cherry-flasher-") as temporary:
        work = Path(temporary)
        stock_boot_stage = work / "stock-boot-unpacked"
        stock_init_stage = work / "stock-init-unpacked"
        magisk_init_stage = work / "magisk-init-unpacked"
        unpack_boot(tool, stock_boot, stock_boot_stage, has_kernel=True)
        unpack_boot(tool, stock_init, stock_init_stage, has_kernel=False)
        unpack_boot(tool, magisk_init, magisk_init_stage, has_kernel=False)
        stock_header = hash_file(stock_boot_stage / "header")
        init_header = hash_file(stock_init_stage / "header")
        init_sha1 = hash_file(stock_init, "sha1")
        require(hash_file(magisk_init_stage / "header") == init_header, "Magisk changed the init_boot header")
        patched = subprocess.run([tool, "cpio", "ramdisk.cpio", "test"], cwd=magisk_init_stage)
        require(patched.returncode == 1, "Magisk ramdisk is not marked as patched")
        magiskboot(tool, magisk_init_stage, "cpio", "ramdisk.cpio", "extract .backup/.magisk magisk-config")
        magisk_config = magisk_init_stage / "magisk-config"
        magisk_config.chmod(0o600)
        require(f"SHA1={init_sha1}" in magisk_config.read_text().splitlines(),
                "Magisk init_boot was patched from a different firmware image")

        standard_boot = work / "cherry-standard-boot.img"
        kernelsu_boot = work / "cherry-ksun-boot.img"
        repack_boot(tool, stock_boot, standard, standard_boot, work, stock_header)
        repack_boot(tool, stock_boot, kernelsu, kernelsu_boot, work, stock_header)

        magiskboot_arm64 = work / "magiskboot"
        with ZipFile(magisk_apk) as magisk:
            magiskboot_arm64.write_bytes(magisk.read("lib/arm64-v8a/libmagiskboot.so"))

        payload = {
            "cherry/stock-boot.img": stock_boot,
            "cherry/stock-init_boot.img": stock_init,
            "cherry/cherry-standard-boot.img": standard_boot,
            "cherry/cherry-ksun-boot.img": kernelsu_boot,
            "cherry/magisk-init_boot.img": magisk_init,
            "cherry/magiskboot": magiskboot_arm64,
            "cherry/install-manager-on-boot.sh": PROJECT / "install-manager-on-boot.sh",
            "Cherry-Manager.apk": manager_apk,
        }
        manifest = [f"{Path(name).name} {hash_file(path)}" for name, path in payload.items()]
        manifest.extend([
            f"stock-boot-header {stock_header}",
            f"stock-init-header {init_header}",
            f"stock-init-ramdisk {hash_file(stock_init_stage / 'ramdisk.cpio')}",
            f"stock-init-sha1 {init_sha1}",
        ])
        accepted_kernels = [stock_boot_stage / "kernel", firmware / "rebuilt-baseline.Image",
                            standard, kernelsu]
        for name in ("manager-standard", "manager-ksun", "manager-ksun-bootdiag", "cherry-ksun-3.4.2"):
            candidate = base / "out" / name / "Image"
            if candidate.is_file():
                accepted_kernels.append(candidate)
        manifest.extend(f"kernel {digest}" for digest in sorted({hash_file(path) for path in accepted_kernels}))

        partial = output.with_suffix(".zip.building")
        with ZipFile(partial, "x", compression=ZIP_DEFLATED, compresslevel=6) as package:
            archive_file(package, PROJECT / "update-binary", "META-INF/com/google/android/update-binary", 0o755)
            package.writestr("META-INF/com/google/android/updater-script", "# See update-binary\n")
            package.writestr("cherry/manifest", "\n".join(manifest) + "\n")
            archive_file(package, PROJECT / "README.md", "README.md")
            archive_file(package, base / "source/COPYING", "licenses/Linux-COPYING")
            for name, path in payload.items():
                archive_file(package, path, name, 0o755 if name.endswith("/magiskboot") else 0o644)
        try:
            with ZipFile(partial) as package:
                require(package.testzip() is None, "Archive failed its CRC check")
            partial.replace(output)
        finally:
            partial.unlink(missing_ok=True)
    print(f"{output}\nSHA-256 {hash_file(output)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, default=Path.home() / f"kernel-builds/SM-X610-{FIRMWARE}")
    build(parser.parse_args().base.resolve())

#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repository=${1:-$(CDPATH= cd -- "$script_dir/.." && pwd -P)}
output=${2:-$repository/build/bsa-preload-camera}
toolchain=${OM1_ARM_TOOLCHAIN:-$repository/.toolchains/gcc-arm-10.3-2021.07-x86_64-arm-none-linux-gnueabihf}
rootfs=${OM1_CAMERA_ROOTFS:-$repository/firmware-extracted/derived/rootfs}
source_dir=$repository/camera/linux/bluetooth/preload

cc=$toolchain/bin/arm-none-linux-gnueabihf-gcc
readelf=$toolchain/bin/arm-none-linux-gnueabihf-readelf
strip=$toolchain/bin/arm-none-linux-gnueabihf-strip
objects=$output/.objects

test -x "$cc"
test -x "$readelf"
test -x "$strip"
test -f "$rootfs/lib/libc.so.6"
test -f "$rootfs/lib/libdl.so.2"
test -f "$rootfs/lib/libpthread.so.0"
mkdir -p "$objects"

crt1=$($cc -print-file-name=crt1.o)
crti=$($cc -print-file-name=crti.o)
crtn=$($cc -print-file-name=crtn.o)
crtbegin=$($cc -print-file-name=crtbegin.o)
crtbegin_s=$($cc -print-file-name=crtbeginS.o)
crtend=$($cc -print-file-name=crtend.o)
crtend_s=$($cc -print-file-name=crtendS.o)
libgcc=$($cc -print-libgcc-file-name)
libc_nonshared=$($cc -print-file-name=libc_nonshared.a)

common_cflags="-march=armv8-a -mfpu=neon-fp-armv8 -mfloat-abi=hard -O2 -std=c11 -Wall -Wextra -Werror -ffunction-sections -fdata-sections"
includes="-I$source_dir/include -I$repository/camera/linux/bluetooth/include -I$repository/camera/linux/star-worker"

$cc $common_cflags -fPIC -Dlstat=om1_lstat $includes \
    -c "$source_dir/src/libbsa_gatt_shim.c" \
    -o "$objects/libbsa_gatt_shim.o"
$cc $common_cflags -fPIC \
    -c "$source_dir/src/compat/lstat.c" -o "$objects/compat_lstat.o"
$cc $common_cflags \
    -c "$source_dir/src/compat/clock_gettime_arm.c" \
    -o "$objects/compat_clock_gettime_arm.o"
$cc $common_cflags $includes \
    -c "$source_dir/src/bsa_gatt_proxy_client.c" \
    -o "$objects/bsa_gatt_proxy_client.o"
$cc -c "$source_dir/services/firmware_constants.S" \
    -o "$objects/firmware_constants.o"

$cc -nostdlib -nodefaultlibs -shared \
    -Wl,-soname,libbsa_gatt_shim.so -Wl,--no-undefined \
    -Wl,--gc-sections -Wl,-z,relro,-z,now \
    -o "$output/libbsa_gatt_shim.so" \
    "$crti" "$crtbegin_s" "$objects/libbsa_gatt_shim.o" \
    "$objects/compat_lstat.o" -Wl,--no-as-needed \
    "$rootfs/lib/libdl.so.2" "$rootfs/lib/libpthread.so.0" \
    "$rootfs/lib/libc.so.6" "$libgcc" -Wl,--as-needed \
    "$crtend_s" "$crtn"

build_server()
{
    name=$1
    source=$source_dir/examples/$name.c
    extra_cflags=
    extra_objects=
    if test "$name" = star_gatt_server; then
        source=$source_dir/services/star_gatt_server.c
        extra_cflags="-Dfstat=om1_fstat -Dclock_gettime=om1_clock_gettime"
        extra_objects="$objects/compat_lstat.o $objects/compat_clock_gettime_arm.o $objects/firmware_constants.o"
    fi
    $cc $common_cflags $extra_cflags $includes \
        -c "$source" -o "$objects/$name.o"
    $cc -nostdlib -nodefaultlibs -no-pie \
        -Wl,--dynamic-linker=/lib/ld-linux-armhf.so.3 \
        -Wl,--no-undefined -Wl,--gc-sections -Wl,-z,relro,-z,now \
        -o "$output/$name" "$crt1" "$crti" "$crtbegin" \
        "$objects/$name.o" "$objects/bsa_gatt_proxy_client.o" \
        $extra_objects \
        -Wl,--no-as-needed "$rootfs/lib/libc.so.6" \
        "$libc_nonshared" "$libgcc" -Wl,--as-needed "$crtend" "$crtn"
}

build_server star_gatt_server

artifacts="$output/libbsa_gatt_shim.so $output/star_gatt_server"
$strip --strip-unneeded $artifacts

for artifact in $artifacts; do
    "$readelf" --file-header "$artifact" | grep -Eq 'Class:[[:space:]]+ELF32'
    "$readelf" --file-header "$artifact" | grep -Eq 'Machine:[[:space:]]+ARM'
    "$readelf" --arch-specific "$artifact" | \
        grep -q 'Tag_ABI_VFP_args: VFP registers'
    if "$readelf" --dynamic "$artifact" | \
        grep -Eq 'NEEDED.*\[libbsa\.so([^]]*)?\]'; then
        echo "$artifact: direct libbsa dependency is forbidden" >&2
        exit 1
    fi
    "$readelf" --version-info "$artifact" | \
        awk '
            BEGIN { found = 0 }
            {
                line = $0
                while (match(line, /GLIBC_[0-9]+\.[0-9]+/)) {
                    found = 1
                    version = substr(line, RSTART + 6, RLENGTH - 6)
                    split(version, part, ".")
                    if (part[1] + 0 > 2 ||
                        (part[1] + 0 == 2 && part[2] + 0 > 15))
                        exit 1
                    line = substr(line, RSTART + RLENGTH)
                }
            }
            END { if (!found) exit 1 }
        '
done

for symbol in BSA_MgtOpen BSA_MgtClose BSA_BleEnable BSA_BleDisable \
    BSA_BleSeAppRegister BSA_BleSeAppDeregister; do
    "$readelf" --wide --dyn-syms "$output/libbsa_gatt_shim.so" | \
        grep -Eq "GLOBAL[[:space:]]+DEFAULT[[:space:]]+[0-9]+[[:space:]]+$symbol$"
done

echo "camera build passed: ARM32 hard-float; GLIBC <= 2.15; no libbsa dependency"

#define _POSIX_C_SOURCE 200809L

#include <asm/unistd.h>
#include <errno.h>
#include <time.h>

#ifndef __arm__
#error "the camera clock_gettime compatibility wrapper requires 32-bit ARM"
#endif

#ifndef __NR_clock_gettime
#error "the ARM toolchain does not define __NR_clock_gettime"
#endif

/*
 * The OM-1 rootfs exports clock_gettime at GLIBC_2.17 even though the rest of
 * the companion is constrained to GLIBC_2.15.  Use the stable ARM EABI kernel
 * entry point directly for camera-only objects; host builds keep libc's
 * clock_gettime so their sanitizers and test interposition remain ordinary.
 */
int om1_clock_gettime(clockid_t clock_id, struct timespec *value)
{
    register long r0 __asm__("r0") = (long)clock_id;
    register long r1 __asm__("r1") = (long)value;
    register long r7 __asm__("r7") = __NR_clock_gettime;

    __asm__ volatile("svc 0"
                     : "+r"(r0)
                     : "r"(r1), "r"(r7)
                     : "cc", "memory");
    if ((unsigned long)r0 >= (unsigned long)-4095L) {
        errno = (int)-r0;
        return -1;
    }
    return (int)r0;
}

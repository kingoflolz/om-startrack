#include <sys/stat.h>

/*
 * Camera glibc 2.25 exposes the public lstat()/fstat() entry points through its
 * development-time libc_nonshared.a, which is not part of the extracted
 * runtime rootfs.  __lxstat version 3 is the ARM EABI stat layout used by
 * both the camera and the GCC 10.3 headers.
 */
extern int __lxstat(int version, const char *path, struct stat *status);
extern int __fxstat(int version, int fd, struct stat *status);

int om1_lstat(const char *path, struct stat *status)
{
    return __lxstat(3, path, status);
}

int om1_fstat(int fd, struct stat *status)
{
    return __fxstat(3, fd, status);
}

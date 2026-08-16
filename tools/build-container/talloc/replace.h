#ifndef MAZEVM_REPLACE_H
#define MAZEVM_REPLACE_H
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdarg.h>
#include <unistd.h>
#include <errno.h>
#define HAVE_VA_COPY 1
#ifndef va_copy
#define va_copy(d, s) __va_copy(d, s)
#endif
/* Normally pulled in from samba's libreplace, which is not vendored here. */
#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif
#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif
#endif

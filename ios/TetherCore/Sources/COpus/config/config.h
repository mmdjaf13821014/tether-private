/* Build configuration for the vendored libopus 1.5.2 (floating point, no
   assembly or intrinsics, no DNN extensions). Written for Tether; not part of
   the upstream release. */
#ifndef COPUS_CONFIG_H
#define COPUS_CONFIG_H

#define OPUS_BUILD 1
#define PACKAGE_VERSION "1.5.2"
#define USE_ALLOCA 1
#define HAVE_LRINTF 1
#define HAVE_LRINT 1

#endif

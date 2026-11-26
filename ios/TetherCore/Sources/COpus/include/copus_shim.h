/* Swift cannot call the variadic opus_*_ctl functions: thin wrappers (Tether, not part of libopus). */
#ifndef COPUS_SHIM_H
#define COPUS_SHIM_H

#include "opus.h"

static inline int copus_encoder_set_bitrate(OpusEncoder *e, opus_int32 v) { return opus_encoder_ctl(e, OPUS_SET_BITRATE(v)); }
static inline int copus_encoder_set_complexity(OpusEncoder *e, opus_int32 v) { return opus_encoder_ctl(e, OPUS_SET_COMPLEXITY(v)); }
static inline int copus_encoder_set_signal_voice(OpusEncoder *e) { return opus_encoder_ctl(e, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)); }
static inline int copus_encoder_set_vbr(OpusEncoder *e, opus_int32 v) { return opus_encoder_ctl(e, OPUS_SET_VBR(v)); }
static inline int copus_encoder_set_inband_fec(OpusEncoder *e, opus_int32 v) { return opus_encoder_ctl(e, OPUS_SET_INBAND_FEC(v)); }

#endif

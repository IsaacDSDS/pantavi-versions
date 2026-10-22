/*
 * Thin C wrapper around Apple's open-source ALAC decoder (Apache 2.0),
 * used to turn AirPlay "ct=2" audio packets into 16-bit stereo PCM.
 */
#ifndef AIRCAST_ALAC_BRIDGE_H
#define AIRCAST_ALAC_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct alac_bridge_s alac_bridge_t;

/* AirPlay ALAC is always 44100 Hz / 16 bit / 2 channels; only the frame length varies. */
alac_bridge_t *alac_bridge_create(unsigned int frames_per_packet);

/* Returns the number of PCM bytes written to out, or -1 on error. */
int alac_bridge_decode(alac_bridge_t *bridge, const unsigned char *in, int in_len,
                       unsigned char *out, int out_capacity);

void alac_bridge_destroy(alac_bridge_t *bridge);

#ifdef __cplusplus
}
#endif

#endif

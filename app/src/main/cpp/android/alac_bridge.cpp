#include "alac_bridge.h"

#include <cstdint>
#include <new>

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"

#define ALAC_CHANNELS 2
#define ALAC_BYTES_PER_FRAME (ALAC_CHANNELS * 2)

struct alac_bridge_s {
    ALACDecoder decoder;
    uint32_t frames_per_packet;
};

extern "C" alac_bridge_t *alac_bridge_create(unsigned int frames_per_packet) {
    if (frames_per_packet == 0) {
        frames_per_packet = 352;
    }
    /* ALACSpecificConfig (big-endian), same values UxPlay hands to GStreamer:
     * frameLength, compatibleVersion 0, bitDepth 16, pb 40, mb 10, kb 14,
     * numChannels 2, maxRun 255, maxFrameBytes 0, avgBitRate 0, sampleRate 44100 */
    uint8_t cookie[24] = {
        (uint8_t) (frames_per_packet >> 24), (uint8_t) (frames_per_packet >> 16),
        (uint8_t) (frames_per_packet >> 8), (uint8_t) frames_per_packet,
        0x00, 0x10, 0x28, 0x0a, 0x0e, ALAC_CHANNELS, 0x00, 0xff,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0xac, 0x44,
    };

    alac_bridge_t *bridge = new (std::nothrow) alac_bridge_t();
    if (!bridge) {
        return nullptr;
    }
    if (bridge->decoder.Init(cookie, sizeof(cookie)) != 0) {
        delete bridge;
        return nullptr;
    }
    bridge->frames_per_packet = frames_per_packet;
    return bridge;
}

extern "C" int alac_bridge_decode(alac_bridge_t *bridge, const unsigned char *in, int in_len,
                                  unsigned char *out, int out_capacity) {
    if (!bridge || !in || in_len <= 0) {
        return -1;
    }
    if (out_capacity < (int) (bridge->frames_per_packet * ALAC_BYTES_PER_FRAME)) {
        return -1;
    }
    BitBuffer bits;
    BitBufferInit(&bits, const_cast<uint8_t *>(in), (uint32_t) in_len);
    uint32_t decoded_frames = 0;
    int32_t status = bridge->decoder.Decode(&bits, out, bridge->frames_per_packet, ALAC_CHANNELS,
                                            &decoded_frames);
    if (status != 0) {
        return -1;
    }
    return (int) (decoded_frames * ALAC_BYTES_PER_FRAME);
}

extern "C" void alac_bridge_destroy(alac_bridge_t *bridge) {
    delete bridge;
}

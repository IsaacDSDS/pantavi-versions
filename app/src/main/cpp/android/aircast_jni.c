/*
 * AirCastTV JNI bridge.
 *
 * Drives the UxPlay AirPlay core (RAOP/AirPlay HTTP server, pairing, FairPlay,
 * mirror/audio RTP, internal mDNS responder) and forwards decrypted media and
 * session events to the Kotlin receiver (NativeCallbacks). Startup order,
 * advertised feature bits and the reset/reconnect loop follow uxplay.cpp.
 */
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "alac_bridge.h"
#include "dnssd.h"
#include "logger.h"
#include "raop.h"
#include "stream.h"

#define TAG "AirCastNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MISSED_FEEDBACK_LIMIT 15      /* seconds of client silence before the server resets */
#define SUPERVISOR_TICK_US 250000
#define PCM_BUFFER_BYTES (4096 * 4)
#define PLAYBACK_INFO_FIELDS 7        /* NativeCallbacks.onStreamPlaybackInfo() layout */

#define ERR_BAD_MAC        -1
#define ERR_DNSSD_INIT     -2
#define ERR_RAOP_INIT      -3
#define ERR_HTTPD_START    -4
#define ERR_DNSSD_REGISTER -5
#define ERR_ALREADY        -6

/* defined in uxplay/mdnsd/dnssd_mdnsd.c (AirCastTV patch) */
extern char dnssd_host_label[64];

static JavaVM *g_vm = NULL;
static pthread_key_t g_env_key;

static jobject g_callbacks = NULL;
static jmethodID m_on_client_request;
static jmethodID m_on_connection_count;
static jmethodID m_on_video_codec;
static jmethodID m_on_video_frame;
static jmethodID m_on_video_size;
static jmethodID m_on_video_stopped;
static jmethodID m_on_video_flush;
static jmethodID m_on_mirror_running;
static jmethodID m_on_audio_format;
static jmethodID m_on_audio_encoded;
static jmethodID m_on_audio_pcm;
static jmethodID m_on_audio_flush;
static jmethodID m_on_audio_stopped;
static jmethodID m_on_volume;
static jmethodID m_on_metadata;
static jmethodID m_on_cover_art;
static jmethodID m_on_progress;
static jmethodID m_on_stream_play;
static jmethodID m_on_stream_seek;
static jmethodID m_on_stream_rate;
static jmethodID m_on_stream_stop;
static jmethodID m_on_stream_playback_info;
static jmethodID m_on_stream_playlist_remove;

static raop_t *raop = NULL;
static dnssd_t *dnssd = NULL;

static pthread_mutex_t g_alac_lock = PTHREAD_MUTEX_INITIALIZER;
static alac_bridge_t *g_alac = NULL;
static unsigned char g_pcm[PCM_BUFFER_BYTES];

static volatile int open_connections = 0;
static volatile unsigned int missed_feedback = 0;
static volatile bool reset_loop = false;
static volatile bool reset_httpd = false;
static volatile bool relaunch_video = false;
static volatile bool preserve_connections = false;
static volatile double current_volume = 0.0;

/* AirPlay video (URL / HLS playback), uxplay -hls */
static bool hls_support = false;
/* raop_set_lang() keeps these pointers, so they must outlive the server */
static char hls_languages[256];
static char hls_system_language[32];

static pthread_t supervisor_thread;
static volatile bool supervisor_running = false;

/* ------------------------------------------------------------------ JNI plumbing */

static void detach_current_thread(void *env) {
    (void) env;
    if (g_vm) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
        return NULL;
    }
    /* raop threads are native; detach automatically when they exit */
    pthread_setspecific(g_env_key, env);
    return env;
}

static void check_exception(JNIEnv *env, const char *where) {
    if ((*env)->ExceptionCheck(env)) {
        LOGE("Java exception in %s", where);
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static jbyteArray new_byte_array(JNIEnv *env, const void *data, int len) {
    jbyteArray array = (*env)->NewByteArray(env, len);
    if (array && len > 0) {
        (*env)->SetByteArrayRegion(env, array, 0, len, (const jbyte *) data);
    }
    return array;
}

static jstring new_string(JNIEnv *env, const char *str) {
    return (*env)->NewStringUTF(env, str ? str : "");
}

#define WITH_CALLBACKS(env)                         \
    JNIEnv *env = g_callbacks ? get_env() : NULL;   \
    if (!env || !g_callbacks) return

static void call_void(jmethodID method, const char *name) {
    WITH_CALLBACKS(env);
    (*env)->CallVoidMethod(env, g_callbacks, method);
    check_exception(env, name);
}

static void call_bool(jmethodID method, jboolean value, const char *name) {
    WITH_CALLBACKS(env);
    (*env)->CallVoidMethod(env, g_callbacks, method, value);
    check_exception(env, name);
}

static void call_float(jmethodID method, float value, const char *name) {
    WITH_CALLBACKS(env);
    (*env)->CallVoidMethod(env, g_callbacks, method, (jfloat) value);
    check_exception(env, name);
}

static void call_bytes(jmethodID method, const void *data, int len, const char *name) {
    WITH_CALLBACKS(env);
    jbyteArray array = new_byte_array(env, data, len);
    if (!array) {
        check_exception(env, name);
        return;
    }
    (*env)->CallVoidMethod(env, g_callbacks, method, array);
    check_exception(env, name);
    (*env)->DeleteLocalRef(env, array);
}

/* ------------------------------------------------------------------ raop callbacks */

static void log_callback(void *cls, int level, const char *msg) {
    (void) cls;
    int prio;
    switch (level) {
    case LOGGER_EMERG:
    case LOGGER_ALERT:
    case LOGGER_CRIT:
    case LOGGER_ERR:
        prio = ANDROID_LOG_ERROR;
        break;
    case LOGGER_WARNING:
        prio = ANDROID_LOG_WARN;
        break;
    case LOGGER_NOTICE:
    case LOGGER_INFO:
        prio = ANDROID_LOG_INFO;
        break;
    default:
        prio = ANDROID_LOG_DEBUG;
        break;
    }
    __android_log_write(prio, "AirCastRaop", msg);
}

static void conn_init(void *cls) {
    (void) cls;
    open_connections++;
    LOGI("open connections: %d", open_connections);
    WITH_CALLBACKS(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_on_connection_count, (jint) open_connections);
    check_exception(env, "conn_init");
}

static void conn_destroy(void *cls) {
    (void) cls;
    open_connections--;
    LOGI("open connections: %d", open_connections);
    if (open_connections == 0) {
        call_void(m_on_audio_stopped, "conn_destroy/audio_stopped");
    }
    WITH_CALLBACKS(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_on_connection_count, (jint) open_connections);
    check_exception(env, "conn_destroy");
}

static void conn_feedback(void *cls) {
    (void) cls;
    missed_feedback = 0;
}

static void conn_reset(void *cls, int reason) {
    (void) cls;
    LOGW("conn_reset: reason %d", reason);
    reset_httpd = true;
    relaunch_video = true;
    reset_loop = true;
}

static void video_reset(void *cls, reset_type_t type) {
    (void) cls;
    switch (type) {
    case RESET_TYPE_NOHOLD:
        if (hls_support) {
            raop_destroy_airplay_video(raop, -1);
        }
        /* fall through */
    case RESET_TYPE_HLS_EOS:
        call_void(m_on_stream_stop, "video_reset");
        call_void(m_on_video_stopped, "video_reset");
        preserve_connections = false;
        relaunch_video = true;
        break;
    case RESET_TYPE_RTP_TO_HLS_TEARDOWN:
        preserve_connections = true;
        /* fall through */
    case RESET_TYPE_RTP_SHUTDOWN:
        call_void(m_on_video_stopped, "video_reset");
        relaunch_video = true;
        break;
    case RESET_TYPE_HLS_SHUTDOWN:
        call_void(m_on_stream_stop, "video_reset");
        call_void(m_on_video_stopped, "video_reset");
        if (hls_support) {
            raop_destroy_airplay_video(raop, -1);
        }
        raop_remove_hls_connections(raop);
        preserve_connections = true;
        relaunch_video = true;
        break;
    case RESET_TYPE_ON_VIDEO_PLAY:  /* the Kotlin player starts by itself: nothing to relaunch */
    default:
        break;
    }
    reset_loop = true;
}

static int video_set_codec(void *cls, video_codec_t codec) {
    (void) cls;
    call_bool(m_on_video_codec, (jboolean) (codec == VIDEO_CODEC_H265), "video_set_codec");
    return 0;
}

static void report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    (void) cls;
    LOGI("connection request from %s (%s) deviceID=%s", name, model, deviceid);
    *admit = true;
    WITH_CALLBACKS(env);
    jstring j_id = new_string(env, deviceid);
    jstring j_model = new_string(env, model);
    jstring j_name = new_string(env, name);
    (*env)->CallVoidMethod(env, g_callbacks, m_on_client_request, j_id, j_model, j_name);
    check_exception(env, "report_client_request");
    (*env)->DeleteLocalRef(env, j_id);
    (*env)->DeleteLocalRef(env, j_model);
    (*env)->DeleteLocalRef(env, j_name);
}

static void video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) cls;
    (void) ntp;
    WITH_CALLBACKS(env);
    jbyteArray array = new_byte_array(env, data->data, data->data_len);
    if (!array) {
        check_exception(env, "video_process");
        return;
    }
    (*env)->CallVoidMethod(env, g_callbacks, m_on_video_frame, array, (jboolean) data->is_h265,
                           (jlong) (data->ntp_time_remote / 1000));
    check_exception(env, "video_process");
    (*env)->DeleteLocalRef(env, array);
}

static void video_report_size(void *cls, float *width_source, float *height_source, float *width, float *height) {
    (void) cls;
    WITH_CALLBACKS(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_on_video_size, (jfloat) *width_source, (jfloat) *height_source,
                           (jfloat) *width, (jfloat) *height);
    check_exception(env, "video_report_size");
}

static void video_flush(void *cls) {
    (void) cls;
    call_void(m_on_video_flush, "video_flush");
}

static void video_pause(void *cls) {
    (void) cls;
}

static void video_resume(void *cls) {
    (void) cls;
}

static void mirror_video_running(void *cls, bool is_running) {
    (void) cls;
    call_bool(m_on_mirror_running, (jboolean) is_running, "mirror_video_running");
}

static void audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen, bool *isMedia,
                             uint64_t *audioFormat) {
    (void) cls;
    LOGI("audio format: ct=%d spf=%d usingScreen=%d isMedia=%d audioFormat=0x%llx", *ct, *spf, *usingScreen,
         *isMedia, (unsigned long long) *audioFormat);
    pthread_mutex_lock(&g_alac_lock);
    if (g_alac) {
        alac_bridge_destroy(g_alac);
        g_alac = NULL;
    }
    if (*ct == 2) {
        g_alac = alac_bridge_create(*spf);
        if (!g_alac) {
            LOGE("could not create ALAC decoder (spf=%d)", *spf);
        }
    }
    pthread_mutex_unlock(&g_alac_lock);

    WITH_CALLBACKS(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_on_audio_format, (jint) *ct, (jint) *spf, (jboolean) *usingScreen,
                           (jboolean) *isMedia);
    check_exception(env, "audio_get_format");
}

static void audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) cls;
    (void) ntp;
    jlong pts_us = (jlong) (data->ntp_time_remote / 1000);
    WITH_CALLBACKS(env);
    jbyteArray array = NULL;

    switch (data->ct) {
    case 2: {
        int pcm_len = -1;
        pthread_mutex_lock(&g_alac_lock);
        if (g_alac) {
            pcm_len = alac_bridge_decode(g_alac, data->data, data->data_len, g_pcm, sizeof(g_pcm));
            if (pcm_len > 0) {
                array = new_byte_array(env, g_pcm, pcm_len);
            }
        }
        pthread_mutex_unlock(&g_alac_lock);
        if (array) {
            (*env)->CallVoidMethod(env, g_callbacks, m_on_audio_pcm, array, pts_us);
        }
        break;
    }
    case 4:
    case 8:
        array = new_byte_array(env, data->data, data->data_len);
        if (array) {
            (*env)->CallVoidMethod(env, g_callbacks, m_on_audio_encoded, array, (jint) data->ct, pts_us);
        }
        break;
    default:
        array = new_byte_array(env, data->data, data->data_len);
        if (array) {
            (*env)->CallVoidMethod(env, g_callbacks, m_on_audio_pcm, array, pts_us);
        }
        break;
    }
    check_exception(env, "audio_process");
    if (array) {
        (*env)->DeleteLocalRef(env, array);
    }
}

static void audio_flush(void *cls) {
    (void) cls;
    call_void(m_on_audio_flush, "audio_flush");
}

static double audio_set_client_volume(void *cls) {
    (void) cls;
    return current_volume;
}

static void audio_set_volume(void *cls, float volume) {
    (void) cls;
    current_volume = (double) volume;
    WITH_CALLBACKS(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_on_volume, (jfloat) volume);
    check_exception(env, "audio_set_volume");
}

static void audio_set_metadata(void *cls, const void *buffer, int buflen) {
    (void) cls;
    call_bytes(m_on_metadata, buffer, buflen, "audio_set_metadata");
}

static void audio_set_coverart(void *cls, const void *buffer, int buflen) {
    (void) cls;
    call_bytes(m_on_cover_art, buffer, buflen, "audio_set_coverart");
}

static void audio_stop_coverart_rendering(void *cls) {
    (void) cls;
    call_bytes(m_on_cover_art, NULL, 0, "audio_stop_coverart_rendering");
}

static void audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    (void) cls;
    WITH_CALLBACKS(env);
    (*env)->CallVoidMethod(env, g_callbacks, m_on_progress, (jlong) *start, (jlong) *curr, (jlong) *end);
    check_exception(env, "audio_set_progress");
}

static void audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote_header) {
    (void) cls;
    (void) dacp_id;
    (void) active_remote_header;
}

static void display_pin(void *cls, char *pin) {
    (void) cls;
    LOGI("pairing pin requested: %s", pin);
}

static void register_client(void *cls, const char *device_id, const char *pk_str, const char *name) {
    (void) cls;
    (void) pk_str;
    LOGI("registered client %s (%s)", name, device_id);
}

static bool check_register(void *cls, const char *pk_str) {
    (void) cls;
    (void) pk_str;
    return true;
}

static const char *passwd(void *cls, int *len) {
    (void) cls;
    *len = 0;
    return NULL;
}

static void export_dacp(void *cls, const char *active_remote, const char *dacp_id) {
    (void) cls;
    (void) active_remote;
    (void) dacp_id;
}

/* AirPlay video: the client hands over a URL (Safari, ...) or, for the YouTube app, HLS playlists
 * that raop serves at http://localhost:<port>/master.m3u8. Playback runs in the Kotlin player. */
static void on_video_play(void *cls, const char *location, const float start_position) {
    (void) cls;
    LOGI("AirPlay video: play %s from %.1f s", location, start_position);
    WITH_CALLBACKS(env);
    jstring j_location = new_string(env, location);
    (*env)->CallVoidMethod(env, g_callbacks, m_on_stream_play, j_location, (jfloat) start_position);
    check_exception(env, "on_video_play");
    (*env)->DeleteLocalRef(env, j_location);
}

static void on_video_scrub(void *cls, const float position) {
    (void) cls;
    call_float(m_on_stream_seek, position, "on_video_scrub");
}

static void on_video_rate(void *cls, const float rate) {
    (void) cls;
    call_float(m_on_stream_rate, rate, "on_video_rate");
}

static void on_video_stop(void *cls) {
    (void) cls;
    call_void(m_on_stream_stop, "on_video_stop");
}

static void on_video_acquire_playback_info(void *cls, playback_info_t *playback_info) {
    (void) cls;
    /* AirPlay video clients poll this about once a second but may send no RTSP /feedback,
     * so a poll also proves the client is alive. */
    missed_feedback = 0;
    /* position -1 = "not available yet"; duration -1 = finished (http_handler_playback_info) */
    playback_info->duration = 0.0;
    playback_info->position = -1.0;
    playback_info->seek_start = 0.0;
    playback_info->seek_duration = 0.0;
    playback_info->rate = 0.0f;
    playback_info->ready_to_play = true;
    playback_info->playback_buffer_empty = false;
    playback_info->playback_buffer_full = false;
    playback_info->playback_likely_to_keep_up = true;
    WITH_CALLBACKS(env);
    jdoubleArray values = (jdoubleArray) (*env)->CallObjectMethod(env, g_callbacks, m_on_stream_playback_info);
    check_exception(env, "on_video_acquire_playback_info");
    if (!values) {
        return;
    }
    if ((*env)->GetArrayLength(env, values) >= PLAYBACK_INFO_FIELDS) {
        jdouble v[PLAYBACK_INFO_FIELDS];
        (*env)->GetDoubleArrayRegion(env, values, 0, PLAYBACK_INFO_FIELDS, v);
        playback_info->duration = v[0];
        playback_info->position = v[1];
        playback_info->rate = (float) v[2];
        playback_info->seek_start = v[3];
        playback_info->seek_duration = v[4];
        playback_info->playback_buffer_empty = v[5] != 0.0;
        playback_info->playback_buffer_full = v[6] != 0.0;
    }
    (*env)->DeleteLocalRef(env, values);
}

/* YouTube app: the current item was removed (e.g. an ad ended). Pause and report where we were. */
static float on_video_playlist_remove(void *cls) {
    (void) cls;
    JNIEnv *env = g_callbacks ? get_env() : NULL;
    if (!env || !g_callbacks) {
        return 0.0f;
    }
    jfloat position = (*env)->CallFloatMethod(env, g_callbacks, m_on_stream_playlist_remove);
    check_exception(env, "on_video_playlist_remove");
    return position;
}

/* ------------------------------------------------------------------ supervisor (uxplay main_loop) */

static void *supervisor_main(void *arg) {
    (void) arg;
    unsigned int ticks = 0;
    while (supervisor_running) {
        usleep(SUPERVISOR_TICK_US);
        if (!supervisor_running) {
            break;
        }
        if (++ticks % (1000000 / SUPERVISOR_TICK_US) == 0) {
            if (open_connections) {
                if (missed_feedback > MISSED_FEEDBACK_LIMIT) {
                    LOGW("no client feedback for %u s: resetting connection", missed_feedback);
                    reset_httpd = true;
                    relaunch_video = true;
                    reset_loop = true;
                } else if (missed_feedback > 2) {
                    LOGW("%u s since last client feedback (expected every 2 s)", missed_feedback);
                }
                missed_feedback++;
            } else {
                missed_feedback = 0;
            }
        }
        if (!reset_loop) {
            continue;
        }
        reset_loop = false;
        if (!relaunch_video) {
            continue;
        }
        if (reset_httpd) {
            raop_stop_httpd(raop);
        }
        call_void(m_on_audio_stopped, "supervisor/audio_stopped");
        call_void(m_on_video_stopped, "supervisor/video_stopped");
        if (!preserve_connections) {
            raop_remove_known_connections(raop);
        }
        preserve_connections = false;
        if (reset_httpd) {
            unsigned short port = raop_get_port(raop);
            raop_start_httpd(raop, &port);
            raop_set_port(raop, port);
            LOGI("http server restarted on port %u", port);
        }
        reset_httpd = false;
        relaunch_video = false;
        missed_feedback = 0;
    }
    return NULL;
}

/* ------------------------------------------------------------------ setup (uxplay start_dnssd / start_raop_server) */

static int parse_mac(const char *mac, char hw_addr[6]) {
    unsigned int b[6];
    if (sscanf(mac, "%2x:%2x:%2x:%2x:%2x:%2x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6) {
        return -1;
    }
    for (int i = 0; i < 6; i++) {
        hw_addr[i] = (char) b[i];
    }
    return 0;
}

static void set_features(bool h265_support) {
    /* Same feature set as uxplay.cpp start_dnssd(): mirroring + audio, FairPlay auth,
     * metadata, legacy pairing off; AirPlay video (bits 0/4) as with uxplay -hls. */
    static const int bits_on[] = {1, 2, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 25, 28, 30};
    static const int bits_off[] = {3, 8, 23, 24, 26, 27, 29, 31};
    for (size_t i = 0; i < sizeof(bits_on) / sizeof(bits_on[0]); i++) {
        dnssd_set_airplay_features(dnssd, bits_on[i], 1);
    }
    for (size_t i = 0; i < sizeof(bits_off) / sizeof(bits_off[0]); i++) {
        dnssd_set_airplay_features(dnssd, bits_off[i], 0);
    }
    dnssd_set_airplay_features(dnssd, 0, hls_support ? 1 : 0);   /* Video (URL playback) */
    dnssd_set_airplay_features(dnssd, 4, hls_support ? 1 : 0);   /* HTTP Live Streaming */
    dnssd_set_airplay_features(dnssd, 42, h265_support ? 1 : 0);  /* Screen Multi Codec (HEVC) */
}

static void fill_callbacks(raop_callbacks_t *cbs) {
    memset(cbs, 0, sizeof(*cbs));
    cbs->conn_init = conn_init;
    cbs->conn_destroy = conn_destroy;
    cbs->conn_reset = conn_reset;
    cbs->conn_feedback = conn_feedback;
    cbs->audio_process = audio_process;
    cbs->video_process = video_process;
    cbs->audio_flush = audio_flush;
    cbs->video_flush = video_flush;
    cbs->video_pause = video_pause;
    cbs->video_resume = video_resume;
    cbs->video_reset = video_reset;
    cbs->audio_set_client_volume = audio_set_client_volume;
    cbs->audio_set_volume = audio_set_volume;
    cbs->audio_get_format = audio_get_format;
    cbs->video_report_size = video_report_size;
    cbs->audio_set_metadata = audio_set_metadata;
    cbs->audio_set_coverart = audio_set_coverart;
    cbs->audio_stop_coverart_rendering = audio_stop_coverart_rendering;
    cbs->audio_set_progress = audio_set_progress;
    cbs->audio_remote_control_id = audio_remote_control_id;
    cbs->report_client_request = report_client_request;
    cbs->display_pin = display_pin;
    cbs->register_client = register_client;
    cbs->check_register = check_register;
    cbs->passwd = passwd;
    cbs->export_dacp = export_dacp;
    cbs->video_set_codec = video_set_codec;
    cbs->mirror_video_running = mirror_video_running;
    cbs->on_video_play = on_video_play;
    cbs->on_video_scrub = on_video_scrub;
    cbs->on_video_rate = on_video_rate;
    cbs->on_video_stop = on_video_stop;
    cbs->on_video_acquire_playback_info = on_video_acquire_playback_info;
    cbs->on_video_playlist_remove = on_video_playlist_remove;
}

static bool resolve_methods(JNIEnv *env, jobject callbacks) {
    jclass cls = (*env)->GetObjectClass(env, callbacks);
#define METHOD(var, name, sig)                                  \
    var = (*env)->GetMethodID(env, cls, name, sig);             \
    if (!var) {                                                 \
        LOGE("missing callback method %s%s", name, sig);        \
        (*env)->DeleteLocalRef(env, cls);                       \
        return false;                                           \
    }
    METHOD(m_on_client_request, "onClientRequest", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    METHOD(m_on_connection_count, "onConnectionCount", "(I)V");
    METHOD(m_on_video_codec, "onVideoCodec", "(Z)V");
    METHOD(m_on_video_frame, "onVideoFrame", "([BZJ)V");
    METHOD(m_on_video_size, "onVideoSize", "(FFFF)V");
    METHOD(m_on_video_stopped, "onVideoStopped", "()V");
    METHOD(m_on_video_flush, "onVideoFlush", "()V");
    METHOD(m_on_mirror_running, "onMirrorRunning", "(Z)V");
    METHOD(m_on_audio_format, "onAudioFormat", "(IIZZ)V");
    METHOD(m_on_audio_encoded, "onAudioEncoded", "([BIJ)V");
    METHOD(m_on_audio_pcm, "onAudioPcm", "([BJ)V");
    METHOD(m_on_audio_flush, "onAudioFlush", "()V");
    METHOD(m_on_audio_stopped, "onAudioStopped", "()V");
    METHOD(m_on_volume, "onVolume", "(F)V");
    METHOD(m_on_metadata, "onMetadata", "([B)V");
    METHOD(m_on_cover_art, "onCoverArt", "([B)V");
    METHOD(m_on_progress, "onProgress", "(JJJ)V");
    METHOD(m_on_stream_play, "onStreamPlay", "(Ljava/lang/String;F)V");
    METHOD(m_on_stream_seek, "onStreamSeek", "(F)V");
    METHOD(m_on_stream_rate, "onStreamRate", "(F)V");
    METHOD(m_on_stream_stop, "onStreamStop", "()V");
    METHOD(m_on_stream_playback_info, "onStreamPlaybackInfo", "()[D");
    METHOD(m_on_stream_playlist_remove, "onStreamPlaylistRemove", "()F");
#undef METHOD
    (*env)->DeleteLocalRef(env, cls);
    return true;
}

static void release_native(JNIEnv *env) {
    if (supervisor_running) {
        supervisor_running = false;
        pthread_join(supervisor_thread, NULL);
    }
    if (dnssd) {
        dnssd_unregister_raop(dnssd);
        dnssd_unregister_airplay(dnssd);
    }
    if (raop) {
        raop_destroy(raop);
        raop = NULL;
    }
    if (dnssd) {
        dnssd_destroy(dnssd);
        dnssd = NULL;
    }
    pthread_mutex_lock(&g_alac_lock);
    if (g_alac) {
        alac_bridge_destroy(g_alac);
        g_alac = NULL;
    }
    pthread_mutex_unlock(&g_alac_lock);
    if (g_callbacks) {
        jobject ref = g_callbacks;
        g_callbacks = NULL;
        (*env)->DeleteGlobalRef(env, ref);
    }
    open_connections = 0;
    missed_feedback = 0;
    reset_loop = reset_httpd = relaunch_video = preserve_connections = false;
}

JNIEXPORT jint JNICALL
Java_com_isaacbenavides_aircast_features_receiver_data_nativebridge_AirPlayNative_nativeStart(
        JNIEnv *env, jobject thiz, jobject callbacks, jstring j_name, jstring j_host_label, jstring j_mac,
        jstring j_keyfile, jint width, jint height, jint refresh_rate, jint max_fps, jboolean h265_support,
        jboolean j_hls_support, jstring j_languages, jboolean debug_log) {
    (void) thiz;
    if (raop) {
        return ERR_ALREADY;
    }
    if (!resolve_methods(env, callbacks)) {
        return ERR_RAOP_INIT;
    }

    const char *name = (*env)->GetStringUTFChars(env, j_name, NULL);
    const char *host_label = (*env)->GetStringUTFChars(env, j_host_label, NULL);
    const char *mac = (*env)->GetStringUTFChars(env, j_mac, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, j_keyfile, NULL);
    int result = 0;
    int dnssd_error = 0;
    char hw_addr[6];

    g_callbacks = (*env)->NewGlobalRef(env, callbacks);
    current_volume = 0.0;
    hls_support = j_hls_support;

    if (parse_mac(mac, hw_addr)) {
        LOGE("invalid MAC address %s", mac);
        result = ERR_BAD_MAC;
        goto done;
    }

    snprintf(dnssd_host_label, sizeof(dnssd_host_label), "%s", host_label);
    dnssd = dnssd_init(name, (int) strlen(name), hw_addr, sizeof(hw_addr), 0, &dnssd_error);
    if (!dnssd || dnssd_error) {
        LOGE("dnssd_init failed: %d", dnssd_error);
        result = ERR_DNSSD_INIT;
        goto done;
    }
    set_features(h265_support);

    raop_callbacks_t cbs;
    fill_callbacks(&cbs);
    raop = raop_init(&cbs);
    if (!raop) {
        LOGE("raop_init failed");
        result = ERR_RAOP_INIT;
        goto done;
    }
    raop_set_log_callback(raop, log_callback, NULL);
    raop_set_log_level(raop, debug_log ? LOGGER_DEBUG : LOGGER_INFO);
    /* nohold = 1: a new client may take over the screen, like an Apple TV */
    if (raop_init2(raop, 1, mac, keyfile)) {
        LOGE("raop_init2 failed");
        free(raop);
        raop = NULL;
        result = ERR_RAOP_INIT;
        goto done;
    }

    if (width > 0) raop_set_plist(raop, "width", width);
    if (height > 0) raop_set_plist(raop, "height", height);
    if (refresh_rate > 0) raop_set_plist(raop, "refreshRate", refresh_rate);
    if (max_fps > 0) raop_set_plist(raop, "maxFPS", max_fps);
    if (hls_support) {
        raop_set_plist(raop, "hls", 1);
        /* YouTube audio-track choice: colon-separated preference list (uxplay -lang); the first
         * entry doubles as the "system" language uxplay reads from $LANG. */
        const char *languages = (*env)->GetStringUTFChars(env, j_languages, NULL);
        snprintf(hls_languages, sizeof(hls_languages), "%s", languages);
        snprintf(hls_system_language, sizeof(hls_system_language), "%s", languages);
        char *separator = strchr(hls_system_language, ':');
        if (separator) {
            *separator = '\0';
        }
        (*env)->ReleaseStringUTFChars(env, j_languages, languages);
        raop_set_lang(raop, hls_languages, NULL, hls_system_language);
    }

    unsigned short tcp[3] = {0}, udp[3] = {0};  /* 0 = dynamically assigned */
    raop_set_tcp_ports(raop, tcp);
    raop_set_udp_ports(raop, udp);

    unsigned short port = raop_get_port(raop);
    if (raop_start_httpd(raop, &port) < 0 || port == 0) {
        LOGE("could not start the AirPlay http server");
        result = ERR_HTTPD_START;
        goto done;
    }
    raop_set_port(raop, port);
    raop_set_dnssd(raop, dnssd);

    if (dnssd_register_raop(dnssd, port) || dnssd_register_airplay(dnssd, port)) {
        LOGE("mDNS registration failed (UDP 5353 / multicast unavailable?)");
        result = ERR_DNSSD_REGISTER;
        goto done;
    }
    LOGI("AirPlay receiver \"%s\" ready on port %u, features 0x%llX", name, port,
         (unsigned long long) dnssd_get_airplay_features(dnssd));

    supervisor_running = true;
    if (pthread_create(&supervisor_thread, NULL, supervisor_main, NULL)) {
        supervisor_running = false;
        LOGW("could not start supervisor thread; automatic connection reset disabled");
    }
    result = port;

done:
    (*env)->ReleaseStringUTFChars(env, j_name, name);
    (*env)->ReleaseStringUTFChars(env, j_host_label, host_label);
    (*env)->ReleaseStringUTFChars(env, j_mac, mac);
    (*env)->ReleaseStringUTFChars(env, j_keyfile, keyfile);
    if (result < 0) {
        release_native(env);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_isaacbenavides_aircast_features_receiver_data_nativebridge_AirPlayNative_nativeStop(JNIEnv *env,
                                                                                          jobject thiz) {
    (void) thiz;
    release_native(env);
}

JNIEXPORT void JNICALL
Java_com_isaacbenavides_aircast_features_receiver_data_nativebridge_AirPlayNative_nativeEndAirPlayVideo(
        JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    /* Only flags the connections; the httpd thread closes them, as when a video ends. */
    if (raop && hls_support) {
        raop_remove_hls_connections(raop);
    }
}

JNIEXPORT void JNICALL
Java_com_isaacbenavides_aircast_features_receiver_data_nativebridge_AirPlayNative_nativeDisconnectClients(
        JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    /* Same path as a client that stopped answering: on its next tick the supervisor thread stops
       audio and video and drops every connection. */
    if (raop) {
        preserve_connections = false;
        relaunch_video = true;
        reset_loop = true;
    }
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    pthread_key_create(&g_env_key, detach_current_thread);
    ntp_global_init();
    return JNI_VERSION_1_6;
}

#ifndef RELAY_VITA_AUDIO_H
#define RELAY_VITA_AUDIO_H

typedef enum {
    RELAY_AUDIO_FOCUS,
    RELAY_AUDIO_CONFIRM,
    RELAY_AUDIO_BACK,
    RELAY_AUDIO_OPEN,
    RELAY_AUDIO_CLOSE,
    RELAY_AUDIO_REVEAL,
    RELAY_AUDIO_COUNT
} RelayAudioCue;

/* Captured Android menu cues decoded ahead of time for SceAudioOut. */
void relay_audio_init(void);
void relay_audio_play(RelayAudioCue cue);
void relay_audio_shutdown(void);

#endif

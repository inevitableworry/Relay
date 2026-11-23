// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Real native Vita menu playback. Each source OGG is predecoded into signed
// 16-bit stereo 48 kHz PCM; the render loop never blocks on the audio port.
#include "relay_audio.h"
#include <psp2/audioout.h>
#include <psp2/kernel/threadmgr.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define AUDIO_FRAMES 512
typedef struct {
    int16_t *samples;
    int frames;
} RelayClip;

static const char *const cueNames[RELAY_AUDIO_COUNT] = {
    "menu_focus", "menu_confirm", "menu_back",
    "menu_open", "menu_close", "menu_reveal"
};
static RelayClip clips[RELAY_AUDIO_COUNT];
static SceUID audioThread=-1;
static volatile int audioRunning=0;
static volatile int requestedCue=-1;

static void loadClip(int cue) {
    char fileName[96];
    snprintf(fileName,sizeof(fileName),"app0:/assets/%s.pcm",cueNames[cue]);
    FILE *file=fopen(fileName,"rb");
    if(!file)return;
    if(fseek(file,0,SEEK_END)!=0){fclose(file);return;}
    long len=ftell(file);
    if(len<=0 || len>2000000L || (len%4)!=0 ||
       fseek(file,0,SEEK_SET)!=0) {fclose(file);return;}
    int16_t *data=(int16_t*)malloc((size_t)len);
    if(!data){fclose(file);return;}
    size_t read=fread(data,1,(size_t)len,file);
    fclose(file);
    if(read!=(size_t)len){free(data);return;}
    clips[cue].samples=data;
    clips[cue].frames=(int)(len/4);
}

static int relayAudioThread(SceSize argc, void *argp) {
    (void)argc;(void)argp;
    const int port=sceAudioOutOpenPort(SCE_AUDIO_OUT_PORT_TYPE_MAIN,
                  AUDIO_FRAMES,48000,SCE_AUDIO_OUT_MODE_STEREO);
    if(port<0)return port; // Missing output device never prevents UI startup.
    int volume[2]={26000,26000};
    sceAudioOutSetVolume(port,SCE_AUDIO_VOLUME_FLAG_L_CH|
                        SCE_AUDIO_VOLUME_FLAG_R_CH,volume);
    int active=-1;
    int offset=0;
    int16_t frame[AUDIO_FRAMES*2];
    while(audioRunning) {
        int next=__sync_lock_test_and_set(&requestedCue,-1);
        if(next>=0 && next<RELAY_AUDIO_COUNT && clips[next].samples) {
            active=next;
            offset=0;
        }
        if(active<0) {
            sceKernelDelayThread(7000);
            continue;
        }
        memset(frame,0,sizeof(frame));
        const RelayClip *clip=&clips[active];
        int remaining=clip->frames-offset;
        int count=remaining<AUDIO_FRAMES?remaining:AUDIO_FRAMES;
        if(count>0) memcpy(frame,clip->samples+offset*2,(size_t)count*4);
        offset+=count;
        if(offset>=clip->frames)active=-1;
        if(sceAudioOutOutput(port,frame)<0)break;
    }
    sceAudioOutReleasePort(port);
    return 0;
}

void relay_audio_init(void) {
    for(int cue=0;cue<RELAY_AUDIO_COUNT;cue++)loadClip(cue);
    audioRunning=1;
    requestedCue=-1;
    audioThread=sceKernelCreateThread("relay-menu-audio",relayAudioThread,
                            0x10000100,0x10000,0,0,NULL);
    if(audioThread<0){audioRunning=0;return;}
    if(sceKernelStartThread(audioThread,0,NULL)<0){
        sceKernelDeleteThread(audioThread);
        audioThread=-1;
        audioRunning=0;
    }
}

void relay_audio_play(RelayAudioCue cue) {
    if(audioRunning && (unsigned)cue<RELAY_AUDIO_COUNT)
        __sync_lock_test_and_set(&requestedCue,(int)cue);
}

void relay_audio_shutdown(void) {
    audioRunning=0;
    if(audioThread>=0) {
        sceKernelWaitThreadEnd(audioThread,NULL,NULL);
        sceKernelDeleteThread(audioThread);
        audioThread=-1;
    }
    for(int cue=0;cue<RELAY_AUDIO_COUNT;cue++) {
        free(clips[cue].samples);
        clips[cue].samples=NULL;
        clips[cue].frames=0;
    }
}

// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Independent native Xbox 360 Relay development edition. Uses the existing
// Android Relay particle distribution and tab/animation constants, but the
// xsdk bitmap font and GPU text path are not yet pixel-identical to Android.
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>

#include "xsdk/gpu.h"
#include "xsdk/kernel.h"
#include "xsdk/log.h"
#include "xsdk/present.h"
#include "xsdk/text.h"

#include "relay_particles.h"

enum {
    SCREEN_W = 1280, SCREEN_H = 720,
    BUFFER_COUNT = 2, SWAP_INTERVAL = 1,
    FRAME_TIMEOUT_MS = 5000,
    COLOR_BACKGROUND = 0xFF030307,
    WHITE = 0xFFF4F3FF,
    MUTED = 0xFFBBB7D2,
    NAV = 0xFF8F8CA7,
    MAX_FRAMES_PER_TEST = 0x7FFFFFFF
};

static double seconds(void)
{
    struct timespec now = { 0 };
    (void)clock_gettime(CLOCK_MONOTONIC, &now);
    return (double)now.tv_sec + (double)now.tv_nsec / 1000000000.0;
}

static void draw_tab_line(uint32_t tab)
{
    if (tab == 0) {
        xsdk_text_draw(60, 70, 1, WHITE, "[ Remote Play ]");
        xsdk_text_draw(250, 70, 1, NAV, "Cloud");
        xsdk_text_draw(365, 70, 1, NAV, "Search");
    } else if (tab == 1) {
        xsdk_text_draw(60, 70, 1, NAV, "Remote Play");
        xsdk_text_draw(235, 70, 1, WHITE, "[ Cloud ]");
        xsdk_text_draw(365, 70, 1, NAV, "Search");
    } else {
        xsdk_text_draw(60, 70, 1, NAV, "Remote Play");
        xsdk_text_draw(235, 70, 1, NAV, "Cloud");
        xsdk_text_draw(365, 70, 1, WHITE, "[ Search ]");
    }
}

static void draw_sphere(double t)
{
    const float cx = SCREEN_W*.50f;
    const float cy = SCREEN_H*.50f;
    const float radius = SCREEN_H*.3315f;
    const float c = cosf((float)t*.075f);
    const float s = sinf((float)t*.075f);
    const float motion = .81f+.19f*sinf((float)t*1.3f);

    // xSDK currently draws its screen text with a fixed-width bitmap font.
    // The particles below use the exact 2,600-source Android positions but
    // only a subset of them until a textured sprite shader is integrated.
    // The dense orb remains visually approximate, not frame-identical.
    for (int i = 0; i < RELAY_POINTS; i += 5) {
        const float *point = relay_particles[i];
        const float x = point[0]*c+point[2]*s;
        const float depth = -point[0]*s+point[2]*c;
        const float y = point[1]+.014f*sinf((float)t*.55f+point[3])*(1-point[1]*point[1]);
        const float px = cx+(x*.974f+y*.225f)*radius;
        const float py = cy+(y*.974f-x*.225f)*radius;
        if (px < 440 || px > 1050 || py < 165 || py > 610) continue;
        const float visibility = point[4]*(.52f+.48f*(depth+1)*.5f)*motion;
        const uint32_t alpha = (uint32_t)(40.f+visibility*175.f);
        const uint32_t tint = ((alpha>255?255:alpha)<<24)|0x00372CFF;
        xsdk_text_draw((int32_t)px,(int32_t)py,1,tint,"\xC2\xB7");
    }
    for (int i=0; i<RELAY_POINTS; i+=31) {
        const float *p=relay_particles[i];
        const float x=p[0]*c+p[2]*s;
        const float depth=-p[0]*s+p[2]*c;
        if(depth < -.4f)continue;
        const float y=p[1]+.014f*sinf((float)t*.55f+p[3])*(1-p[1]*p[1]);
        const int32_t px=(int32_t)(cx+(x*.974f+y*.225f)*radius);
        const int32_t py=(int32_t)(cy+(y*.974f-x*.225f)*radius);
        xsdk_text_draw(px,py,1,0xA0A8B4FF,"\xE2\x80\xA2");
    }
}

static void draw_menu(uint32_t tab, bool overlay, double t)
{
    draw_sphere(t);
    draw_tab_line(tab);
    if(tab==0){
        xsdk_text_draw(66,263,2,WHITE,"Connect to your PS5");
        xsdk_text_draw(66,325,1,MUTED,"Remote Play JB  -  configure console host");
    }else if(tab==1){
        xsdk_text_draw(66,263,2,WHITE,"Cloud Streaming");
        xsdk_text_draw(66,325,1,MUTED,"PlayStation Plus services");
    }else{
        xsdk_text_draw(66,263,2,WHITE,"Search");
        xsdk_text_draw(66,325,1,MUTED,"Find a nearby PS5");
    }
    xsdk_text_draw(66,643,1,WHITE,"LB/RB: tabs    A: select    B: back    BACK: exit");
    if(overlay){
        xsdk_text_draw(135,410,1,WHITE,"Relay Xbox 360: developer preview");
        xsdk_text_draw(135,442,1,MUTED,"PS5 video, input and audio are not connected.");
        xsdk_text_draw(135,475,1,MUTED,"Press B to return to Relay Home.");
    }
}

static int run(uint32_t width, uint32_t height)
{
    uint32_t tab=0, previous=0;
    bool overlay=false;
    const double start=seconds();
    for(uint32_t frame=0; frame<MAX_FRAMES_PER_TEST;frame++){
        INPUT_STATE pad={0};
        if(XamInputGetState(0,0,&pad)!=ERROR_SUCCESS)pad=(INPUT_STATE){0};
        const uint32_t buttons=pad.gamepad.buttons;
        const uint32_t newly=buttons & ~previous;
        previous=buttons;
        if(newly & INPUT_GAMEPAD_BACK)break;
        if(newly & INPUT_GAMEPAD_LEFT_SHOULDER)tab=(tab+2)%3;
        if(newly & INPUT_GAMEPAD_RIGHT_SHOULDER)tab=(tab+1)%3;
        if(newly & INPUT_GAMEPAD_A)overlay=true;
        if(newly & INPUT_GAMEPAD_B)overlay=false;

        uint32_t address=0;
        const NTSTATUS status=xsdk_present_begin(&address,FRAME_TIMEOUT_MS);
        if(!NT_SUCCESS(status)){
            xsdk_log_write("Relay: present begin failed 0x%08X",(unsigned)status);
            return 1;
        }
        xsdk_gpu_set_target(width,height);
        draw_menu(tab,overlay,seconds()-start);
        xsdk_gpu_resolve(address,width,height,COLOR_BACKGROUND);
        xsdk_present_end();
    }
    return 0;
}

int main(void)
{
    xsdk_log_write("Relay Xbox 360 developer edition: xsdk native PowerPC");
    NTSTATUS status=xsdk_gpu_open();
    if(!NT_SUCCESS(status))return 1;
    status=xsdk_text_open();
    if(!NT_SUCCESS(status)){
        xsdk_log_write("Relay: text renderer failed 0x%08X",(unsigned)status);
        xsdk_gpu_close();return 2;
    }
    uint32_t width=0,height=0;
    status=xsdk_present_open(XSDK_DISPLAY_720P,BUFFER_COUNT,SWAP_INTERVAL,&width,&height);
    if(!NT_SUCCESS(status)){
        xsdk_log_write("Relay: display unavailable 0x%08X",(unsigned)status);
        xsdk_text_close();xsdk_gpu_close();return 3;
    }
    xsdk_log_write("Relay: native screen %ux%u",(unsigned)width,(unsigned)height);
    const int result=run(width,height);
    xsdk_present_close();xsdk_text_close();xsdk_gpu_close();
    xsdk_log_write("Relay: closed, status %d",result);
    return result;
}

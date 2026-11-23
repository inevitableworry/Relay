// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Native Vita2D Relay menu, based on the existing Android PortalBackdropView.
// A functional PS5 streaming decoder/controller session is not implemented.
#include <psp2/ctrl.h>
#include <psp2/touch.h>
#include <psp2/kernel/processmgr.h>
#include <vita2d.h>
#include <math.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include "relay_particles.h"
#include "relay_audio.h"

#define W 960
#define H 544
#define PI 3.14159265358979323846f
#define COL_WHITE RGBA8(248,248,255,255)
#define COL_MUTED RGBA8(202,199,219,255)

typedef struct {
    vita2d_texture *background,*sphere,*shadow,*gloss,*halo,*cloud,*dot,*floor;
    vita2d_texture *quickPanel,*dialogPanel,*focusRow,*connectButton;
    vita2d_texture *nav[3],*wifi,*battery,*settings;
    vita2d_font *font;
} RelayAssets;

static double now_sec(void) {
    struct timespec ts={0};
    clock_gettime(CLOCK_MONOTONIC,&ts);
    return (double)ts.tv_sec+((double)ts.tv_nsec/1e9);
}

static vita2d_texture* load(const char*file) {
    char path[180];
    snprintf(path,sizeof(path),"app0:/assets/%s",file);
    return vita2d_load_PNG_file(path);
}

static bool prepare(RelayAssets *a) {
    memset(a,0,sizeof(*a));
    a->background=load("background.png");
    a->sphere=load("sphere.png");
    a->shadow=load("sphere_shadow.png");
    a->gloss=load("sphere_gloss.png");
    a->halo=load("halo.png");
    a->cloud=load("cloud.png");
    a->dot=load("dot.png");
    a->floor=load("floor.png");
    a->quickPanel=load("quick_panel.png");
    a->dialogPanel=load("dialog_panel.png");
    a->focusRow=load("focus_row.png");
    a->connectButton=load("connect_button.png");
    a->nav[0]=load("portal_nav_remote.png");
    a->nav[1]=load("portal_nav_cloud.png");
    a->nav[2]=load("portal_nav_search.png");
    a->wifi=load("portal_status_wifi.png");
    a->battery=load("portal_status_battery.png");
    a->settings=load("portal_status_sliders.png");
    a->font=vita2d_load_font_file("app0:/assets/inter-variable.ttf");
    return a->background && a->sphere && a->shadow && a->gloss &&
        a->halo && a->cloud && a->dot && a->floor &&
        a->quickPanel && a->dialogPanel && a->focusRow && a->connectButton &&
        a->font && a->nav[0] && a->nav[1] && a->nav[2];
}

static void shutdown(RelayAssets *a) {
    vita2d_texture *list[]={a->background,a->sphere,a->shadow,a->gloss,a->halo,
        a->cloud,a->dot,a->floor,a->quickPanel,a->dialogPanel,a->focusRow,
        a->connectButton,a->nav[0],a->nav[1],a->nav[2],
        a->wifi,a->battery,a->settings};
    for(unsigned i=0;i<sizeof(list)/sizeof(list[0]);++i)
        if(list[i])vita2d_free_texture(list[i]);
    if(a->font)vita2d_free_font(a->font);
}

static void drawScaled(vita2d_texture *t,float x,float y,float width,float height,
                       uint32_t tint) {
    if(!t)return;
    const float w=(float)vita2d_texture_get_width(t);
    const float h=(float)vita2d_texture_get_height(t);
    if(w>0 && h>0)vita2d_draw_texture_tint_scale(t,x,y,width/w,height/h,tint);
}

static float ease(float x) {
    if(x<0)x=0;else if(x>1)x=1;
    return 1.f-(1.f-x)*(1.f-x);
}

static void drawOrb(RelayAssets*a,float time) {
    const float cx=W*.5f,cy=H*.5f;
    const float base=H*.3315f;
    const float growth=ease((time-.1f)/.9f);
    const float radius=base*(.04f+.96f*growth);
    const float c=cosf(time*.075f),s=sinf(time*.075f);
    drawScaled(a->floor,cx-base*1.4f,H*.916f-base*.085f,
               base*2.8f,base*.17f,RGBA8(255,255,255,255));
    drawScaled(a->halo,cx-radius*1.85f,cy-radius*1.85f,
               radius*3.7f,radius*3.7f,RGBA8(255,255,255,(int)(growth*240)));
    drawScaled(a->sphere,cx-radius,cy-radius,radius*2.f,radius*2.f,
               RGBA8(255,255,255,(int)(growth*255)));
    drawScaled(a->shadow,cx-radius,cy-radius,radius*2.f,radius*2.f,
               RGBA8(255,255,255,(int)(growth*235)));
    // Source-defined 19 elliptical cloud regions. Clip to the sphere's square.
    vita2d_set_clip_rectangle((int)(cx-radius),(int)(cy-radius),
                              (int)(cx+radius),(int)(cy+radius));
    vita2d_enable_clipping();
    for(int i=0;i<19;i++) {
        const float *cl=relay_clusters[i];
        float x=cl[0]*c+cl[2]*s;
        float depth=-cl[0]*s+cl[2]*c;
        float y=cl[1];
        float spread=.12f+cl[3]*1.45f;
        float px=cx+(x*.974f+y*.225f)*.94f*radius;
        float py=cy+(y*.974f-x*.225f)*.94f*radius;
        int alpha=(int)((85.f+(depth+1.f)*53.f)*growth);
        // Keep the diffuse ellipses inside the orb's curved silhouette.
        const float dist=sqrtf((px-cx)*(px-cx)+(py-cy)*(py-cy));
        const float boundary=1.f-fmaxf(0.f,(dist/radius-.76f)*2.1f);
        alpha=(int)(alpha*fmaxf(0.f,boundary));
        if(alpha<0)alpha=0;
        if(alpha>255)alpha=255;
        drawScaled(a->cloud,px-spread*radius,py-spread*1.6f*radius,
                   spread*2*radius,spread*3.2f*radius,RGBA8(255,255,255,alpha));
    }
    vita2d_disable_clipping();

    // Vita3K's Vulkan renderer cannot reliably render Vita2D's un-sized
    // SCE_GXM_PRIMITIVE_POINTS shader: it reports missing gl_PointSize and
    // intermittently drops/flickers the entire frame. Render small textured
    // sprites instead. Sparse sampling retains the deterministic starfield
    // while avoiding the invalid point-list graphics pipeline.
    if(a->dot) {
        for(int i=0;i<RELAY_PARTICLE_COUNT;i+=2) {
            const float *p=relay_particle_data[i];
            float x=p[0]*c+p[2]*s;
            float depth=-p[0]*s+p[2]*c;
            float y=p[1]+.014f*sinf(time*.55f+p[3])*(1-p[1]*p[1]);
            float strength=p[4]*(.52f+.48f*(depth+1)*.5f)*(.81f+.19f*sinf(time*1.3f+p[3]));
            int bin=(int)(strength*7.99f);
            if(bin<0)bin=0;
            if(bin>7)bin=7;
            int alpha=(int)((48+bin*25)*growth);
            if(alpha>255)alpha=255;
            const float px=cx+(x*.974f+y*.225f)*radius;
            const float py=cy+(y*.974f-x*.225f)*radius;
            if((px-cx)*(px-cx)+(py-cy)*(py-cy)>radius*radius)
                continue;
            const float dim=(1.1f+strength*1.6f)*radius/180.f;
            if(i%12==0 && depth>-.2f) {
                const float haze=(5.f+strength*10.f)*radius/180.f;
                drawScaled(a->dot,px-haze*.5f,py-haze*.5f,haze,haze,
                    RGBA8(98,81,255,(int)(growth*(22+strength*24))));
            }
            drawScaled(a->dot,px-dim*.5f,py-dim*.5f,dim,dim,
                       RGBA8(20+bin*3,22+bin*3,255,alpha));
            // Sparse optical bloom, original i % 31 and camera-depth rule.
            if(i%31==0 && depth>-.4f) {
                float dot=(6.f+strength*11.f)*radius/358.f;
                drawScaled(a->dot,px-dot,py-dot,dot*2,dot*2,
                           RGBA8(255,255,255,(int)(190.f*growth)));
            }
        }
    }
    // A soft specular and upper-left rim bring back the spherical depth
    // without an unsupported Vita3K point or programmable shader pipeline.
    drawScaled(a->gloss,cx-radius,cy-radius,radius*2.f,radius*2.f,
               RGBA8(255,255,255,(int)(growth*(229+14*sinf(time*.32f)))));
    // Original menu makes one intro rim arc and then stays animated without
    // repetitive replacement effects.
    if(time<2.7f && growth>0) {
        const float opacity=fmaxf(0.f,fminf(1.f,(2.7f-time)/1.8f));
        const float from=(time*570.f-90.f)*PI/180.f;
        int steps=(int)fminf(growth*430.f,360.f);
        for(int j=1;j<=steps;j++){
            const float a0=from+(float)(j-1)*PI/180.f;
            const float a1=from+(float)j*PI/180.f;
            vita2d_draw_line(cx+radius*cosf(a0),cy+radius*sinf(a0),
                             cx+radius*cosf(a1),cy+radius*sinf(a1),
                             RGBA8(223,225,255,(int)(245.f*opacity)));
        }
    }
}

static void text(RelayAssets*a,int x,int y,int size,unsigned color,const char*value){
    if(a->font)vita2d_font_draw_text(a->font,x,y,color,size,value);
}

/* Panel coordinates map Android's 704 x 1080 quick menu to Vita's 960 x 544
 * 16:9 viewport. The full-height list scrolls exactly as Android ScrollView
 * does; each controller-focused tile stays within the visible viewport. */
typedef enum {
    PAGE_HOME, PAGE_SETTINGS, PAGE_NETWORK, PAGE_SYSTEM, PAGE_DISPLAY,
    PAGE_CONTROLLER, PAGE_AUDIO, PAGE_PSLINK, PAGE_CLOUD, PAGE_NETWORK_STATUS,
    PAGE_PLAYER, PAGE_CONNECT, PAGE_REMOTE, PAGE_TRANSLATE
} RelayPage;
#define QUICK_COUNT 11
#define QUICK_LEFT 608.f
#define QUICK_WIDTH 352.f
static const char *const quickLabels[QUICK_COUNT]={
    "Remote Play JB","P1","P2","P3","P4","Brightness",
    "Audio Devices","Network","Network Status","Settings","Close"
};
static const char *const settingsLabels[6]={
    "Network","System","Display and Brightness","Controller",
    "PS Link Device","Cloud Streaming"
};

static void quickRect(int index,float *x,float *y,float *w,float *h){
    *x=QUICK_LEFT+22;*w=QUICK_WIDTH-44;
    if(index==0){*y=104;*h=73;}
    else if(index>=1 && index<=4){
        *w=145;*h=49;*x+=((index-1)%2)*153;
        *y=204+((index-1)/2)*54;
    }else if(index==5){*y=331;*h=72;}
    else {*y=414+(index-6)*84;*h=72;}
}

static void menuTile(RelayAssets*a,float x,float y,float w,float h,bool selected,
                     unsigned opacity){
    // Android's translucent slate/mauve tile surfaces, curved focus edge.
    drawScaled(a->focusRow,x,y,w,h,
        RGBA8(222,212,232,(int)((selected?110:45)*opacity/255)));
    if(selected){
        vita2d_draw_rectangle(x+6,y+4,3,h-8,RGBA8(238,236,255,(int)(210*opacity/255)));
    }
}

static void strokeCircle(float x,float y,float radius,unsigned color){
    for(int i=0;i<32;i++){
        float a=(float)i*PI/16.f,b=(float)(i+1)*PI/16.f;
        vita2d_draw_line(x+radius*cosf(a),y+radius*sinf(a),
                         x+radius*cosf(b),y+radius*sinf(b),color);
    }
}

static void drawMenuGlyph(RelayAssets*a,int index,float x,float y,int alpha){
    unsigned white=RGBA8(247,246,255,alpha);
    if(index==0){
        drawScaled(a->nav[0],x-8,y-1,48,35,RGBA8(255,255,255,alpha));
    }else if(index==5){
        strokeCircle(x+16,y+17,10,white);
        for(int i=0;i<8;i++){
            float ang=(float)i*PI*.25f;
            vita2d_draw_line(x+16+14*cosf(ang),y+17+14*sinf(ang),
                x+16+18*cosf(ang),y+17+18*sinf(ang),white);
        }
    }else if(index==6){
        strokeCircle(x+16,y+17,13,white);
        vita2d_draw_rectangle(x+1,y+18,5,14,white);
        vita2d_draw_rectangle(x+26,y+18,5,14,white);
    }else if(index==7 || index==8){
        strokeCircle(x+16,y+16,13,white);
        vita2d_draw_line(x+3,y+16,x+29,y+16,white);
        vita2d_draw_line(x+16,y+3,x+16,y+29,white);
    }else if(index==9){
        for(int i=0;i<3;i++){
            float py=y+8+i*9;
            vita2d_draw_line(x+1,py,x+31,py,white);
            vita2d_draw_fill_circle(x+8+i*7,py,3,white);
        }
    }else if(index==10){
        vita2d_draw_line(x+5,y+5,x+27,y+27,white);
        vita2d_draw_line(x+27,y+5,x+5,y+27,white);
    }
}

static void drawQuickMenu(RelayAssets*a,float amount,int selected,float scroll,
                          int brightness){
    if(amount<=.001f)return;
    return; // diagnostic fixture: skip all quick draw operations
    const float t=ease(amount),left=W-QUICK_WIDTH*t;
    // Diagnostic: no fullscreen solid-color quad.
    drawScaled(a->quickPanel,left,0,QUICK_WIDTH,H,
               RGBA8(255,255,255,(int)(255*t)));
    return; // diagnostic fixture: isolate quick panel base layers
    // Keep menu sprite clipping in bounds checks for Vita3K compatibility.
    // Clock/status is part of the scrollable panel on Android.
    time_t wall=time(NULL);struct tm *tm=localtime(&wall);char clockLabel[20]="--:--";
    if(tm)strftime(clockLabel,sizeof(clockLabel),"%I:%M",tm);
    text(a,(int)(left+26),(int)(49-scroll),20,COL_WHITE,clockLabel);
    drawScaled(a->wifi,left+236,28-scroll,21,21,RGBA8(255,255,255,255));
    drawScaled(a->battery,left+288,29-scroll,28,21,RGBA8(255,255,255,255));

    for(int i=0;i<QUICK_COUNT;i++){
        float x,y,w,h;quickRect(i,&x,&y,&w,&h);
        x+=left-QUICK_LEFT;y-=scroll;
        if(y+h<0 || y>=H)continue;
        if(i==1){
            menuTile(a,left+22,185-scroll,QUICK_WIDTH-44,126,false,(unsigned)(255*t));
            text(a,(int)(left+40),(int)(203-scroll),16,COL_WHITE,"Players");
        }
        menuTile(a,x,y,w,h,i==selected,(unsigned)(255*t));
        if(i>=1 && i<=4){
            vita2d_draw_fill_circle(x+23,y+24,16,
                                    RGBA8(119,111,143,(int)(230*t)));
            char number[4];snprintf(number,sizeof(number),"%d",i);
            text(a,(int)(x+18),(int)(y+30),15,COL_WHITE,number);
            text(a,(int)(x+45),(int)(y+21),14,COL_WHITE,quickLabels[i]);
            text(a,(int)(x+45),(int)(y+37),10,COL_MUTED,"Available");
        }else if(i==5){
            drawMenuGlyph(a,i,x+12,y+18,(int)(255*t));
            const float trackX=x+75,trackY=y+35;
            vita2d_draw_rectangle(trackX,trackY,w-103,5,
                                  RGBA8(113,109,128,(int)(178*t)));
            vita2d_draw_rectangle(trackX,trackY,(w-103)*brightness/100.f,5,
                                  RGBA8(247,247,255,(int)(255*t)));
            vita2d_draw_fill_circle(trackX+(w-103)*brightness/100.f,
                                    trackY+2,9,COL_WHITE);
        }else{
            drawMenuGlyph(a,i,x+21,y+19,(int)(255*t));
            text(a,(int)(x+79),(int)(y+(i==0?30:43)),
                 i==0?16:17,COL_WHITE,quickLabels[i]);
            if(i==0)text(a,(int)(x+79),(int)(y+51),13,COL_MUTED,"Connect");
        }
    }
}

static const char *pageTitle(RelayPage page){
    switch(page){
        case PAGE_SETTINGS:return "Settings";
        case PAGE_NETWORK:return "Network";
        case PAGE_SYSTEM:return "System";
        case PAGE_DISPLAY:return "Display and Brightness";
        case PAGE_CONTROLLER:return "Controller";
        case PAGE_AUDIO: case PAGE_PSLINK:return "PS Link Device";
        case PAGE_CLOUD:return "Cloud Streaming";
        case PAGE_NETWORK_STATUS:return "Network Status";
        case PAGE_PLAYER:return "Players";
        case PAGE_CONNECT:return "Remote Play JB";
        case PAGE_REMOTE:return "Remote Play Settings";
        case PAGE_TRANSLATE:return "Game Translation";
        default:return "Relay";
    }
}

static void drawSettings(RelayAssets*a,RelayPage page,int selected,int player){
    vita2d_draw_rectangle(0,0,W,H,RGBA8(6,6,13,255));
    drawScaled(a->dialogPanel,29,32,50,51,RGBA8(255,255,255,165));
    text(a,48,65,24,COL_WHITE,"<");
    text(a,96,67,25,COL_WHITE,pageTitle(page));
    if(page==PAGE_SETTINGS){
        for(int i=0;i<6;i++){
            int col=i%3,row=i/3;
            float x=95+col*262.f,y=119+row*144.f;
            menuTile(a,x,y,247,130,selected==i,255);
            // The categories and three-column geometry follow Android's
            // Settings landing page; no unsupported streaming menu is implied.
            if(i==0 || i==2 || i==4)strokeCircle(x+122,y+46,17,COL_WHITE);
            else vita2d_draw_rectangle(x+107,y+29,30,31,COL_WHITE);
            text(a,(int)(x+20),(int)(y+103),
                 i==2?14:17,COL_WHITE,settingsLabels[i]);
        }
        menuTile(a,95,414,770,55,selected==6,255);
        text(a,121,450,18,COL_WHITE,"Remote Play Settings");
        menuTile(a,95,476,770,55,selected==7,255);
        text(a,121,510,18,COL_WHITE,"Game Translation");
        return;
    }
    if(page==PAGE_NETWORK){
        text(a,97,150,19,COL_WHITE,"Wi-Fi Network");
        text(a,97,184,14,COL_MUTED,"Vita wireless configuration is managed by System Settings.");
        menuTile(a,95,211,770,74,selected==0,255);
        text(a,126,254,19,COL_WHITE,"Show Network Status");
        menuTile(a,95,299,770,74,selected==1,255);
        text(a,126,342,19,COL_WHITE,"Remote Access");
        return;
    }
    if(page==PAGE_NETWORK_STATUS){
        text(a,95,154,20,COL_WHITE,"Connection information");
        text(a,95,207,17,COL_MUTED,"Network diagnostic data is unavailable in this Vita build.");
        text(a,95,242,16,COL_MUTED,"Vita networking and PS5 connection probing remain to be added.");
        return;
    }
    if(page==PAGE_PLAYER){
        char line[80];snprintf(line,sizeof(line),"Player %d",player);
        text(a,95,161,21,COL_WHITE,line);
        text(a,95,204,16,COL_MUTED,"Player numbers are assigned automatically in connection order.");
        text(a,95,237,16,COL_MUTED,"Connect to a PS5, then choose an account or Guest there.");
        text(a,95,269,16,COL_MUTED,"No player has been preassigned on this Vita.");
        return;
    }
    if(page==PAGE_CONNECT){
        text(a,95,160,20,COL_WHITE,"Connect to your PS5");
        text(a,95,202,16,COL_MUTED,"Vita streaming, game audio and controller relay are pending.");
        text(a,95,233,16,COL_MUTED,"A native PS5 session must be implemented before connecting.");
        return;
    }
    if(page==PAGE_DISPLAY){
        text(a,95,156,20,COL_WHITE,"Display and Brightness");
        text(a,95,200,16,COL_MUTED,"Use Quick Menu to adjust the Relay brightness preview.");
        text(a,95,237,16,COL_MUTED,"Vita system brightness is controlled through system settings.");
        return;
    }
    if(page==PAGE_CLOUD){
        text(a,95,160,19,COL_WHITE,"Cloud game streaming");
        text(a,95,200,16,COL_MUTED,"PlayStation Plus cloud servers are not connected to this client.");
        return;
    }
    if(page==PAGE_REMOTE){
        text(a,95,154,19,COL_WHITE,"Console pairing and streaming quality");
        text(a,95,202,16,COL_MUTED,"PS5 discovery, pairing and native Vita decoding are in progress.");
        return;
    }
    if(page==PAGE_TRANSLATE){
        text(a,95,154,19,COL_WHITE,"Game Translation");
        text(a,95,202,16,COL_MUTED,"Android's OCR, ASR and TTS have no Vita implementation yet.");
        return;
    }
    text(a,95,160,19,COL_WHITE,pageTitle(page));
    text(a,95,205,16,COL_MUTED,"These Android controls need a native Vita implementation.");
    text(a,95,238,16,COL_MUTED,"Circle returns to the previous menu.");
}

static void drawUI(RelayAssets*a,int tab,float tabElapsed,float quickAmount,
                   int quickSelected,float scroll,int brightness,
                   RelayPage page,int settingsSelected,int player,
                   bool connectFocus){
    if(page!=PAGE_HOME){drawSettings(a,page,settingsSelected,player);return;}
    const float content=ease(tabElapsed/.30f);
    // Matched to Android PortalMainActivity.applyGeometry at 1920x1080 / 2.
    drawScaled(a->nav[tab],39,40,170,49,RGBA8(255,255,255,255));
    drawScaled(a->wifi,795,57,17,17,RGBA8(255,255,255,255));
    drawScaled(a->battery,827,57,22,18,RGBA8(255,255,255,255));
    time_t wall=time(NULL);struct tm *tm=localtime(&wall);char label[20]="";
    if(tm)strftime(label,sizeof(label),"%I:%M %p",tm);
    text(a,858,71,12,COL_WHITE,label);
    drawScaled(a->settings,926,54,24,24,RGBA8(255,255,255,255));
    const int fade=(int)(255*content),x=(int)(42+(1-content)*13);
    if(tab==0){
        text(a,x,151,24,RGBA8(248,248,255,fade),"Connect to your PS5");
        text(a,x,174,13,RGBA8(202,199,219,fade),"Remote Play JB");
        if(connectFocus)drawScaled(a->focusRow,755,474,167,54,
                                   RGBA8(255,255,255,170));
        drawScaled(a->connectButton,760,483,158,41,RGBA8(255,255,255,255));
        text(a,808,509,17,RGBA8(22,21,31,255),"Connect");
    }else if(tab==1){
        text(a,x,151,19,RGBA8(248,248,255,fade),
             "Cloud Streaming with PlayStation Plus Premium");
        text(a,x,199,15,RGBA8(202,199,219,fade),
             "Cloud game streaming is not available in this Remote Play client.");
    }else{
        drawScaled(a->focusRow,322,114,316,42,RGBA8(246,246,255,75));
        text(a,348,141,17,RGBA8(202,199,219,fade),"Search paired consoles");
        text(a,42,205,18,RGBA8(248,248,255,fade),"Your Consoles");
        text(a,42,242,14,RGBA8(202,199,219,fade),
             "No consoles are registered on this Vita.");
    }
    text(a,42,519,13,COL_WHITE,"L1 / R1  Switch Tabs");
    drawQuickMenu(a,quickAmount,quickSelected,scroll,brightness);
}

/* The Vita front-panel touch digitizer has a nominal 1920x1088 coordinate
 * range, independent of the 960x544 display. Query the installed hardware
 * panel rather than assuming the same range on Vita3K and PSTV. */
static bool touchPoint(int *screenX,int *screenY) {
    SceTouchData data={0};
    if(sceTouchPeek(SCE_TOUCH_PORT_FRONT,&data,1)<1 || data.reportNum==0)
        return false;
    static SceTouchPanelInfo panel;
    static bool initialized=false;
    if(!initialized) {
        if(sceTouchGetPanelInfo(SCE_TOUCH_PORT_FRONT,&panel)<0 ||
           panel.maxDispX<=panel.minDispX || panel.maxDispY<=panel.minDispY) {
            panel.minDispX=0;panel.maxDispX=1919;
            panel.minDispY=0;panel.maxDispY=1087;
        }
        initialized=true;
    }
    int x=(int)data.report[0].x;
    int y=(int)data.report[0].y;
    *screenX=(x-panel.minDispX)*W/(panel.maxDispX-panel.minDispX+1);
    *screenY=(y-panel.minDispY)*H/(panel.maxDispY-panel.minDispY+1);
    if(*screenX<0)*screenX=0;
    if(*screenY<0)*screenY=0;
    if(*screenX>=W)*screenX=W-1;
    if(*screenY>=H)*screenY=H-1;
    return true;
}

static void openQuickItem(int item,bool *quick,RelayPage *page,int *player,
                          RelayPage *parent){
    if(item==5)return; // Brightness is a continuous slider.
    *quick=false;
    *parent=PAGE_HOME;
    if(item==0)*page=PAGE_CONNECT;
    else if(item>=1 && item<=4){*player=item;*page=PAGE_PLAYER;}
    else if(item==6)*page=PAGE_AUDIO;
    else if(item==7)*page=PAGE_NETWORK;
    else if(item==8)*page=PAGE_NETWORK_STATUS;
    else if(item==9)*page=PAGE_SETTINGS;
}

static void openSettingsItem(int selected,RelayPage *page,RelayPage *parent){
    static const RelayPage pages[8]={
        PAGE_NETWORK,PAGE_SYSTEM,PAGE_DISPLAY,PAGE_CONTROLLER,
        PAGE_PSLINK,PAGE_CLOUD,PAGE_REMOTE,PAGE_TRANSLATE
    };
    if(selected<0 || selected>=8)return;
    *parent=PAGE_SETTINGS;
    *page=pages[selected];
}

static void touchNavigate(int x,int y,int *tab,bool *quick,RelayPage *page,
                          RelayPage *parent,int *quickSelected,int *settingsSelected,
                          int *player,int *brightness,float quickScroll,
                          bool *connectFocus){
    if(*page!=PAGE_HOME){
        if(x<80 && y<100){*page=*parent;*parent=PAGE_HOME;return;}
        if(*page==PAGE_SETTINGS){
            for(int i=0;i<8;i++){
                float left,top,right,bottom;
                if(i<6){left=95+(i%3)*262;top=119+(i/3)*144;right=left+247;bottom=top+130;}
                else {left=95;top=i==6?414:476;right=865;bottom=top+55;}
                if(x>=left && x<right && y>=top && y<bottom){
                    *settingsSelected=i;openSettingsItem(i,page,parent);return;
                }
            }
        }else if(*page==PAGE_NETWORK && x>=95 && x<865 && y>=211 && y<285){
            *parent=PAGE_NETWORK;*page=PAGE_NETWORK_STATUS;
        }
        return;
    }
    if(*quick){
        if(x<(int)QUICK_LEFT){*quick=false;return;}
        float contentY=y+quickScroll;
        for(int i=0;i<QUICK_COUNT;i++){
            float rx,ry,rw,rh;quickRect(i,&rx,&ry,&rw,&rh);
            if(x>=rx && x<rx+rw && contentY>=ry && contentY<ry+rh){
                *quickSelected=i;
                if(i==5){
                    float amount=(x-(rx+75))/(rw-103);
                    if(amount<.03f)amount=.03f;
                    if(amount>1.f)amount=1.f;
                    *brightness=(int)(amount*100);
                }else openQuickItem(i,quick,page,player,parent);
                return;
            }
        }
        return;
    }
    if(y>=40 && y<=91){
        if(x>=39 && x<96)*tab=0;
        else if(x<152 && x>=96)*tab=1;
        else if(x>=152 && x<209)*tab=2;
        else if(x>=914)*quick=true;
        return;
    }
    if(*tab==0 && x>=750 && y>=472){
        *connectFocus=true;*page=PAGE_CONNECT;*parent=PAGE_HOME;
    }
}

int main(void){
    sceCtrlSetSamplingMode(SCE_CTRL_MODE_ANALOG);
    /* Full Vita application only: handheld front touch; PSTV continues to
     * navigate with the physical controller if no touch digitizer exists. */
    sceTouchSetSamplingState(SCE_TOUCH_PORT_FRONT,SCE_TOUCH_SAMPLING_STATE_START);
    vita2d_init();
    vita2d_set_vblank_wait(1);
    vita2d_set_clear_color(RGBA8(3,3,7,255));
    RelayAssets assets;
    bool loaded=prepare(&assets);
    if(!loaded){shutdown(&assets);vita2d_fini();sceKernelExitProcess(2);return 2;}
    relay_audio_init();
    relay_audio_play(RELAY_AUDIO_REVEAL);
    uint32_t previous=0;
    int tab=0;
    int quickSelected=0,settingsSelected=0,player=1,brightness=50;
    RelayPage page=PAGE_HOME,parent=PAGE_HOME;
    bool quick=true,connectFocus=false; // screenshot fixture: revert after capture
    bool touching=false;
    const double startup=now_sec();
    double frameTime=startup,tabChanged=startup,stickMove=0;
    float quickAmount=0,quickScroll=0;
    for(;;){
        double current=now_sec();
        float dt=(float)fmax(0.,fmin(.055,current-frameTime));
        frameTime=current;
        SceCtrlData pad={0};
        sceCtrlPeekBufferPositive(0,&pad,1);
        uint32_t pressed=(pad.buttons & ~previous);
        previous=pad.buttons;
        if(current-stickMove>.20) {
            if(pad.lx<50){pressed|=SCE_CTRL_LEFT;stickMove=current;}
            else if(pad.lx>205){pressed|=SCE_CTRL_RIGHT;stickMove=current;}
            else if(pad.ly<50){pressed|=SCE_CTRL_UP;stickMove=current;}
            else if(pad.ly>205){pressed|=SCE_CTRL_DOWN;stickMove=current;}
        }
        const int oldTab=tab,oldQuickSelected=quickSelected,oldBrightness=brightness;
        const bool oldQuick=quick,oldConnectFocus=connectFocus;
        const RelayPage oldPage=page;
        int touchX=0,touchY=0;
        bool currentlyTouching=touchPoint(&touchX,&touchY);
        if(currentlyTouching && !touching)
            touchNavigate(touchX,touchY,&tab,&quick,&page,&parent,
                          &quickSelected,&settingsSelected,&player,&brightness,
                          quickScroll,&connectFocus);
        touching=currentlyTouching;
        if((pad.buttons&(SCE_CTRL_START|SCE_CTRL_SELECT))==
                        (SCE_CTRL_START|SCE_CTRL_SELECT))break;
        if(page!=PAGE_HOME){
            if(pressed&(SCE_CTRL_CIRCLE|SCE_CTRL_TRIANGLE)){
                page=parent;parent=PAGE_HOME;
            }else if(page==PAGE_SETTINGS){
                if(pressed&SCE_CTRL_LEFT && settingsSelected>0)settingsSelected--;
                if(pressed&SCE_CTRL_RIGHT && settingsSelected<7)settingsSelected++;
                if(pressed&SCE_CTRL_UP){
                    if(settingsSelected==7)settingsSelected=6;
                    else settingsSelected=settingsSelected>=3?settingsSelected-3:0;
                }
                if(pressed&SCE_CTRL_DOWN)
                    settingsSelected=settingsSelected<3?settingsSelected+3:6;
                if(pressed&SCE_CTRL_CROSS)
                    openSettingsItem(settingsSelected,&page,&parent);
            }else if(page==PAGE_NETWORK && pressed&SCE_CTRL_CROSS){
                parent=PAGE_NETWORK;page=PAGE_NETWORK_STATUS;
            }
        }else if(quick){
            if(pressed&(SCE_CTRL_CIRCLE|SCE_CTRL_TRIANGLE|SCE_CTRL_SELECT))
                quick=false;
            if(quick){
                if(pressed&SCE_CTRL_UP)
                    quickSelected=(quickSelected+QUICK_COUNT-1)%QUICK_COUNT;
                if(pressed&SCE_CTRL_DOWN)
                    quickSelected=(quickSelected+1)%QUICK_COUNT;
                if(quickSelected==5){
                    if(pressed&SCE_CTRL_LEFT)brightness=fmaxf(3,brightness-5);
                    if(pressed&SCE_CTRL_RIGHT)brightness=fminf(100,brightness+5);
                }else if(quickSelected>=1 && quickSelected<=4){
                    if(pressed&SCE_CTRL_LEFT && quickSelected%2==0)quickSelected--;
                    if(pressed&SCE_CTRL_RIGHT && quickSelected%2==1)quickSelected++;
                }
                if(pressed&SCE_CTRL_CROSS)
                    openQuickItem(quickSelected,&quick,&page,&player,&parent);
            }
        }else{
            if(pressed&SCE_CTRL_LTRIGGER)tab=(tab+2)%3;
            if(pressed&SCE_CTRL_RTRIGGER)tab=(tab+1)%3;
            if(pressed&(SCE_CTRL_TRIANGLE|SCE_CTRL_START))quick=true;
            if(pressed&SCE_CTRL_DOWN && tab==0)connectFocus=true;
            if(pressed&SCE_CTRL_UP)connectFocus=false;
            if(pressed&SCE_CTRL_CROSS && tab==0){
                page=PAGE_CONNECT;parent=PAGE_HOME;
            }
            if(pressed&(SCE_CTRL_CIRCLE|SCE_CTRL_SELECT))tab=0;
        }
        if(tab!=oldTab) {
            tabChanged=current;
            connectFocus=false;
        }
        // The animation runs off monotonic clock time, independently of
        // controller and touch polling. Capped dt avoids suspend jumps.
        quickAmount=fmaxf(0.f,fminf(1.f,quickAmount+
                        (quick?dt/.30f:-dt/.30f)));
        if(quick){
            // Follow Android's scroll-on-focus behavior, keeping the selected
            // item within the 544px Vita front-panel viewport.
            float rx,ry,rw,rh;quickRect(quickSelected,&rx,&ry,&rw,&rh);
            float destination=quickScroll;
            if(ry-quickScroll<68)destination=ry-68;
            if(ry+rh-quickScroll>H-18)destination=ry+rh-(H-18);
            if(destination<0)destination=0;
            if(destination>295)destination=295;
            float delta=destination-quickScroll;
            quickScroll+=delta*fminf(1.f,dt*13.f);
        }else if(!oldQuick){quickScroll=0;}
        if(page!=oldPage){
            if(page==PAGE_HOME || page==parent)relay_audio_play(RELAY_AUDIO_BACK);
            else relay_audio_play(RELAY_AUDIO_CONFIRM);
        }else if(quick && !oldQuick)relay_audio_play(RELAY_AUDIO_OPEN);
        else if(!quick && oldQuick)relay_audio_play(RELAY_AUDIO_CLOSE);
        else if(tab!=oldTab || quickSelected!=oldQuickSelected ||
                connectFocus!=oldConnectFocus || brightness!=oldBrightness)
            relay_audio_play(RELAY_AUDIO_FOCUS);
        vita2d_start_drawing();
        vita2d_clear_screen();
        drawScaled(assets.background,0,0,960,544,RGBA8(255,255,255,255));
        drawOrb(&assets,(float)(now_sec()-startup));
        drawUI(&assets,tab,(float)(current-tabChanged),quickAmount,
               quickSelected,quickScroll,brightness,page,settingsSelected,
               player,connectFocus);
        vita2d_end_drawing();
        vita2d_swap_buffers();
    }
    vita2d_wait_rendering_done();
    sceTouchSetSamplingState(SCE_TOUCH_PORT_FRONT,SCE_TOUCH_SAMPLING_STATE_STOP);
    relay_audio_shutdown();
    shutdown(&assets);
    vita2d_fini();
    sceKernelExitProcess(0);
    return 0;
}

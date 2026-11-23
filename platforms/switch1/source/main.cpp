// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Relay Nintendo Switch 1 UI renderer: original assets and animation parameters.
// PS Remote Play network session/decode backend is NOT YET PORTED (see README).
#include <switch.h>
#include "pbs_probe.hpp"
#include "pbs_media.hpp"
#include "switch_input.hpp"
#include <SDL2/SDL.h>
#include <SDL2/SDL_image.h>
#include <SDL2/SDL_ttf.h>
#include <SDL2/SDL_mixer.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <cstring>
#include <string>
#include <vector>
#include <arpa/inet.h>

namespace {
constexpr int DESIGN_W=1920, DESIGN_H=1080;
constexpr int PARTICLES=2600, CLUSTERS=19;
constexpr float PI=3.14159265358979323846f;
struct Particle {float x,y,z,phase,strength;};
struct Cluster {float x,y,z,spread;};

// Java Random's fixed seed and Gaussian algorithm are used in the original
// PortalBackdropView.kt; preserving them here avoids synthesizing new patterns.
class JavaRandom {
    uint64_t state;
    bool haveGaussian=false;
    double gaussianCache=0;
    uint32_t next(int bits) {
        state=(state*0x5DEECE66DULL+0xBULL)&((1ULL<<48)-1);
        return static_cast<uint32_t>(state>>(48-bits));
    }
public:
    explicit JavaRandom(uint64_t seed):state((seed^0x5DEECE66DULL)&((1ULL<<48)-1)){}
    float nextFloat(){return next(24)/16777216.f;}
    double nextDouble(){return ((static_cast<uint64_t>(next(26))<<27)|next(27))/9007199254740992.0;}
    double nextGaussian(){
        if(haveGaussian){haveGaussian=false;return gaussianCache;}
        double v1,v2,s;
        do {v1=2*nextDouble()-1;v2=2*nextDouble()-1;s=v1*v1+v2*v2;}
        while(s>=1.0 || s==0);
        double m=std::sqrt(-2*std::log(s)/s);
        gaussianCache=v2*m;haveGaussian=true;return v1*m;
    }
};

Uint8 c8(int v){return static_cast<Uint8>(std::clamp(v,0,255));}
SDL_Color color(int r,int g,int b,int a=255){return {c8(r),c8(g),c8(b),c8(a)};}

struct Assets {
    SDL_Texture *nav[3]={}, *wifi=nullptr,*battery=nullptr,*sliders=nullptr,*orb=nullptr;
    SDL_Texture *hintL=nullptr,*hintR=nullptr;
    TTF_Font *small=nullptr,*body=nullptr,*medium=nullptr,*large=nullptr;
    std::array<Mix_Chunk*,8> sounds{};
    std::array<Uint32,8> lastCueTick{};
    Mix_Chunk *music=nullptr;
    std::vector<Uint8> pcm;
    bool audioReady=false;
    SDL_Texture* load(SDL_Renderer*r,const char*name){
        SDL_Surface*s=IMG_Load(name);
        if(!s){std::printf("asset unavailable %s: %s\n",name,IMG_GetError());return nullptr;}
        SDL_Texture*t=SDL_CreateTextureFromSurface(r,s);
        SDL_FreeSurface(s);return t;
    }
    void init(SDL_Renderer*r){
        const char *n[]={"portal_nav_remote.png","portal_nav_cloud.png","portal_nav_search.png"};
        char path[220];
        for(int i=0;i<3;i++){
            std::snprintf(path,sizeof(path),"romfs:/relay/nav/%s",n[i]);nav[i]=load(r,path);
        }
        wifi=load(r,"romfs:/relay/nav/portal_status_wifi.png");
        battery=load(r,"romfs:/relay/nav/portal_status_battery.png");
        sliders=load(r,"romfs:/relay/nav/portal_status_sliders.png");
        orb=load(r,"romfs:/relay/nav/portal_orb.png");
        hintL=load(r,"romfs:/relay/nav/portal_hint_l1.png");
        hintR=load(r,"romfs:/relay/nav/portal_hint_r1.png");
        small=TTF_OpenFont("romfs:/relay/fonts/inter-variable.ttf",23);
        body=TTF_OpenFont("romfs:/relay/fonts/inter-variable.ttf",28);
        medium=TTF_OpenFont("romfs:/relay/fonts/inter-variable.ttf",34);
        large=TTF_OpenFont("romfs:/relay/fonts/inter-variable.ttf",46);
        // Identical, unmodified OGG cue recordings and 48kHz stereo S16 PCM loop
        // from the Android Relay project. Never synthesize fallback sounds.
        audioReady=Mix_OpenAudio(48000,AUDIO_S16SYS,2,2048)==0;
        if(!audioReady){
            std::printf("Relay menu audio unavailable: %s\n",Mix_GetError());
            return;
        }
        Mix_AllocateChannels(16);
        const char*cue[]={"menu_focus.ogg","menu_confirm.ogg","menu_back.ogg",
            "menu_open.ogg","menu_close.ogg","menu_reveal.ogg",
            "update_start.ogg","update_complete.ogg"};
        for(int i=0;i<8;i++){
            std::snprintf(path,sizeof(path),"romfs:/relay/audio/%s",cue[i]);
            sounds[i]=Mix_LoadWAV(path);
            if(sounds[i])Mix_VolumeChunk(sounds[i],90); // Android SoundPool .7/.7
            else std::printf("Relay cue unavailable %s: %s\n",cue[i],Mix_GetError());
        }
        auto*raw=SDL_RWFromFile("romfs:/relay/audio/portal_home_loop.pcm","rb");
        if(!raw) return;
        const Sint64 size=SDL_RWsize(raw);
        if(size>0 && size<32*1024*1024 && size%4==0){
            pcm.resize(static_cast<size_t>(size));
            if(SDL_RWread(raw,pcm.data(),1,pcm.size())==pcm.size())
                music=Mix_QuickLoad_RAW(pcm.data(),static_cast<Uint32>(pcm.size()));
        }
        SDL_RWclose(raw);
        if(music){Mix_Volume(0,70);Mix_PlayChannel(0,music,-1);}
        else std::printf("Relay menu music PCM could not initialize\n");
    }
    void cue(int i){
        if(!audioReady||i<0||i>=8||!sounds[i])return;
        const Uint32 now=SDL_GetTicks();
        // Match PortalMenuAudio's 70 ms per-cue debounce.
        if(now-lastCueTick[i]<70)return;
        lastCueTick[i]=now;
        Mix_PlayChannel(-1,sounds[i],0);
    }
    void setCueVolume(int percent){
        for(auto* sound:sounds)if(sound)Mix_VolumeChunk(sound,
            std::clamp(percent,0,100)*MIX_MAX_VOLUME/100);
    }
    void setMusicVolume(int percent){
        if(audioReady)Mix_Volume(0,std::clamp(percent,0,100)*MIX_MAX_VOLUME/100);
    }
    void shutdown(){
        if(audioReady){
            Mix_HaltChannel(-1);
            if(music)Mix_FreeChunk(music);
            for(auto*s:sounds)if(s)Mix_FreeChunk(s);
            Mix_CloseAudio();
        }
        for(auto*t:nav)if(t)SDL_DestroyTexture(t);
        for(auto*t:{wifi,battery,sliders,orb,hintL,hintR})if(t)SDL_DestroyTexture(t);
        for(auto*f:{small,body,medium,large})if(f)TTF_CloseFont(f);
    }
};

class Scene {
    SDL_Renderer*r;
    Assets assets;
    std::array<Particle,PARTICLES> particles{};
    std::array<Cluster,CLUSTERS> clusters{};
    SDL_Texture *sphere=nullptr,*halo=nullptr,*dot=nullptr,*cloud=nullptr,*floorGlow=nullptr;
    SDL_Texture *pointBloom=nullptr,*pointCore=nullptr;
    SDL_Texture *videoTexture=nullptr;
    RelayPbsMedia media;
    StreamTouchControls streamTouches;
    SwitchTouchPoint touchStart{};
    SwitchTouchPoint touchLast{};
    bool menuFingerActive=false;
    std::vector<uint8_t> videoPixels;
    int videoWidth=0,videoHeight=0;
    uint32_t stopComboStartMs=0;
    enum class QuickPage {Home,Players,Audio,Network,NetworkStatus,Settings};
    int tab=0,selected=0,pageSelected=0,selectedPlayer=1;
    QuickPage quickPage=QuickPage::Home;
    float quickScroll=0;
    float brightness=.6f;
    int cueVolume=70,musicVolume=55;
    bool brightnessAvailable=false,networkServiceReady=false,batteryServiceReady=false;
    bool reduceMotion=false,showInputHints=true;
    u32 wifiStrength=0,batteryPercent=0;
    std::string switchIp="Unavailable";
    std::string networkState="Unavailable";
    bool switchingBrightness=false;
    bool showingQuick=false,showingSettings=false,showingBackendNotice=false,searchEditing=false;
    bool awaitingRecoveryConfirmation=false,showingVideo=false;
    RelayPbsProbe lastProbe;
    bool probeAttempted=false;
    float tabChange=0,menuChange=0,startTime=0;
    Uint32 timeStart=0,prevTime=0;

    float seconds() const {return static_cast<float>(SDL_GetTicks()-timeStart)/1000.f;}
    void rect(float x,float y,float w,float h,SDL_Color c){
        SDL_SetRenderDrawBlendMode(r,SDL_BLENDMODE_BLEND);
        SDL_SetRenderDrawColor(r,c.r,c.g,c.b,c.a);
        SDL_FRect dst{x,y,w,h};SDL_RenderFillRectF(r,&dst);
    }
    void stroke(float x,float y,float w,float h,SDL_Color c){
        SDL_SetRenderDrawBlendMode(r,SDL_BLENDMODE_BLEND);
        SDL_SetRenderDrawColor(r,c.r,c.g,c.b,c.a);
        SDL_FRect dst{x,y,w,h};SDL_RenderDrawRectF(r,&dst);
    }
    // Convex rounded surfaces match Android's pill, card and sheet geometry.
    // A single geometry draw avoids dozens of SDL rect calls per menu frame.
    void roundRect(float x,float y,float w,float h,float radius,SDL_Color tint){
        radius=std::clamp(radius,0.f,std::min(w,h)*.5f);
        if(radius<1.f){rect(x,y,w,h,tint);return;}
        std::vector<SDL_Vertex> vertices;
        std::vector<int> indices;
        vertices.reserve(46);
        indices.reserve(132);
        vertices.push_back({{x+w*.5f,y+h*.5f},tint,{0,0}});
        constexpr int steps=10;
        const float cx[]={x+w-radius,x+w-radius,x+radius,x+radius};
        const float cy[]={y+radius,y+h-radius,y+h-radius,y+radius};
        for(int corner=0;corner<4;++corner){
            const float from=(-90.f+corner*90.f)*PI/180.f;
            for(int s=0;s<=steps;++s){
                const float a=from+PI*.5f*s/steps;
                vertices.push_back({{cx[corner]+radius*std::cos(a),
                    cy[corner]+radius*std::sin(a)},tint,{0,0}});
            }
        }
        for(int i=1;i<static_cast<int>(vertices.size());++i){
            indices.insert(indices.end(),{0,i,i==static_cast<int>(vertices.size())-1?1:i+1});
        }
        SDL_RenderGeometry(r,nullptr,vertices.data(),static_cast<int>(vertices.size()),
            indices.data(),static_cast<int>(indices.size()));
    }
    void arc(float centerX,float centerY,float radius,float startDegrees,
             float sweepDegrees,float opacity){
        // Android draws three concentric soft rims; a single one-pixel outline
        // made the original native intro nearly invisible at 720p.
        static constexpr float widths[]={.048f,.018f,.005f};
        static constexpr SDL_Color colors[]={{108,50,255,45},
            {151,100,255,120},{223,225,255,245}};
        const int segments=std::clamp(static_cast<int>(std::ceil(sweepDegrees/2.5f)),1,160);
        for(int pass=0;pass<3;++pass){
            std::vector<SDL_Vertex> verts;
            std::vector<int> indices;
            verts.reserve((segments+1)*2);
            indices.reserve(segments*6);
            SDL_Color tint=colors[pass];
            tint.a=c8(static_cast<int>(tint.a*opacity));
            float half=radius*widths[pass]*.5f;
            for(int step=0;step<=segments;++step){
                float a=(startDegrees+sweepDegrees*step/segments)*PI/180.f;
                float ca=std::cos(a),sa=std::sin(a);
                verts.push_back({{centerX+(radius-half)*ca,
                    centerY+(radius-half)*sa},tint,{0,0}});
                verts.push_back({{centerX+(radius+half)*ca,
                    centerY+(radius+half)*sa},tint,{0,0}});
                if(step<segments){int i=step*2;
                    indices.insert(indices.end(),{i,i+1,i+2,i+1,i+3,i+2});}
            }
            SDL_RenderGeometry(r,nullptr,verts.data(),static_cast<int>(verts.size()),
                indices.data(),static_cast<int>(indices.size()));
        }
    }
    void clippedCloud(float centerX,float centerY,float sphereRadius,
                      float x,float y,float halfW,float halfH,Uint8 opacity){
        if(!cloud || sphereRadius<1.f || opacity==0)return;
        // The Android Canvas applies a circular clipPath to every nebula cloud.
        // Clip the textured quad in normalized sphere space against a 64-sided
        // circle; SDL's rectangular scissor cannot reproduce that edge.
        struct Vertex {float x,y,u,v;};
        std::vector<Vertex> polygon={
            {x-halfW,y-halfH,0,0},{x+halfW,y-halfH,1,0},
            {x+halfW,y+halfH,1,1},{x-halfW,y+halfH,0,1}};
        constexpr int sides=64;
        const float edge=std::cos(PI/sides);
        for(int side=0;side<sides && !polygon.empty();++side){
            float a=(side+.5f)*2*PI/sides;
            float nx=std::cos(a),ny=std::sin(a);
            std::vector<Vertex> clipped;
            clipped.reserve(polygon.size()+1);
            auto distance=[&](const Vertex&v){return nx*v.x+ny*v.y-edge;};
            Vertex previous=polygon.back();
            float previousDistance=distance(previous);
            for(const auto&current:polygon){
                float currentDistance=distance(current);
                if((previousDistance<=0)!=(currentDistance<=0)){
                    float weight=previousDistance/(previousDistance-currentDistance);
                    clipped.push_back({previous.x+(current.x-previous.x)*weight,
                        previous.y+(current.y-previous.y)*weight,
                        previous.u+(current.u-previous.u)*weight,
                        previous.v+(current.v-previous.v)*weight});
                }
                if(currentDistance<=0)clipped.push_back(current);
                previous=current;previousDistance=currentDistance;
            }
            polygon.swap(clipped);
        }
        if(polygon.size()<3)return;
        SDL_Color tint=color(255,255,255,opacity);
        std::vector<SDL_Vertex> vertices;
        std::vector<int> indices;
        vertices.reserve(polygon.size());
        indices.reserve((polygon.size()-2)*3);
        for(const auto&v:polygon){
            vertices.push_back({{centerX+v.x*sphereRadius,
                centerY+v.y*sphereRadius},tint,{v.u,v.v}});
        }
        for(int i=1;i+1<static_cast<int>(vertices.size());++i)
            indices.insert(indices.end(),{0,i,i+1});
        SDL_RenderGeometry(r,cloud,vertices.data(),static_cast<int>(vertices.size()),
            indices.data(),static_cast<int>(indices.size()));
    }
    void text(const std::string&value,float x,float y,TTF_Font*font,SDL_Color c, float opacity=1.f){
        if(!font||value.empty())return;
        SDL_Surface*s=TTF_RenderUTF8_Blended(font,value.c_str(),c);
        if(!s)return;
        SDL_Texture*t=SDL_CreateTextureFromSurface(r,s);
        if(t){SDL_SetTextureBlendMode(t,SDL_BLENDMODE_BLEND);
            SDL_SetTextureAlphaMod(t,c8(static_cast<int>(255*opacity)));
            SDL_FRect dst{x,y,static_cast<float>(s->w),static_cast<float>(s->h)};
            SDL_RenderCopyF(r,t,nullptr,&dst);SDL_DestroyTexture(t);
        }
        SDL_FreeSurface(s);
    }
    void texture(SDL_Texture*t,float x,float y,float w,float h,Uint8 alpha=255){
        if(!t)return;
        SDL_SetTextureBlendMode(t,SDL_BLENDMODE_BLEND);
        SDL_SetTextureAlphaMod(t,alpha);
        SDL_FRect dst{x,y,w,h};SDL_RenderCopyF(r,t,nullptr,&dst);
    }
    void line(float x1,float y1,float x2,float y2,SDL_Color tint){
        SDL_SetRenderDrawBlendMode(r,SDL_BLENDMODE_BLEND);
        SDL_SetRenderDrawColor(r,tint.r,tint.g,tint.b,tint.a);
        SDL_RenderDrawLineF(r,x1,y1,x2,y2);
    }
    void circle(float x,float y,float radius,SDL_Color tint,int segments=48){
        for(int i=0;i<segments;i++){
            const float a=i*2.f*PI/segments,b=(i+1)*2.f*PI/segments;
            line(x+radius*std::cos(a),y+radius*std::sin(a),
                x+radius*std::cos(b),y+radius*std::sin(b),tint);
        }
    }
    void outline(float x,float y,float w,float h,float radius,SDL_Color tint){
        // Layered stroke samples the same rounded, lavender focus treatment
        // as PortalQuickMenu.MenuTileDrawable.
        radius=std::min(radius,std::min(w,h)*.5f);
        const float cx[]={x+w-radius,x+w-radius,x+radius,x+radius};
        const float cy[]={y+radius,y+h-radius,y+h-radius,y+radius};
        for(int c=0;c<4;c++){
            for(int i=0;i<10;i++){
                float a=(-90.f+c*90.f+i*9.f)*PI/180.f;
                float b=a+9*PI/180.f;
                line(cx[c]+radius*std::cos(a),cy[c]+radius*std::sin(a),
                    cx[c]+radius*std::cos(b),cy[c]+radius*std::sin(b),tint);
            }
        }
        line(x+radius,y,x+w-radius,y,tint);
        line(x+w,y+radius,x+w,y+h-radius,tint);
        line(x+w-radius,y+h,x+radius,y+h,tint);
        line(x,y+h-radius,x,y+radius,tint);
    }
    void menuGlyph(int type,float x,float y,float alpha){
        const SDL_Color white=color(250,249,255,c8(alpha*255));
        const float cx=x+36,cy=y+34;
        switch(type){
        case 0: // Remote Play (the native source glyph is used on the first card)
            circle(cx,cy,22,white);line(cx-10,cy,cx+10,cy,white);break;
        case 1: // Player
            circle(cx,cy-12,11,white);arc(cx,cy+26,25,208,124,alpha);break;
        case 2: // Brightness
            circle(cx,cy,19,white);
            for(int k=0;k<8;k++){float a=k*PI/4;
                line(cx+25*std::cos(a),cy+25*std::sin(a),
                    cx+33*std::cos(a),cy+33*std::sin(a),white);}
            break;
        case 3: // Audio output
            arc(cx,cy+5,25,186,168,alpha);
            roundRect(cx-31,cy+7,12,30,5,white);
            roundRect(cx+19,cy+7,12,30,5,white);break;
        case 4: // Network / network status
            circle(cx,cy,25,white);circle(cx,cy,10,white);
            line(cx-25,cy,cx+25,cy,white);
            line(cx,cy-25,cx,cy+25,white);break;
        case 5: // Settings
            circle(cx,cy,19,white);circle(cx,cy,9,white);
            for(int k=0;k<8;k++){float a=k*PI/4;
                line(cx+20*std::cos(a),cy+20*std::sin(a),
                    cx+28*std::cos(a),cy+28*std::sin(a),white);}
            break;
        case 6: // Close
            line(cx-20,cy-20,cx+20,cy+20,white);
            line(cx+20,cy-20,cx-20,cy+20,white);break;
        }
    }
    void menuCard(float x,float y,float w,float h,bool focused,float alpha){
        if(focused){
            roundRect(x-3,y-3,w+6,h+6,30,color(236,233,246,c8(50*alpha)));
        }
        // Preserve a translucent, gently warmed glass gradient rather than
        // uniform opaque dark rectangles.
        for(int part=0;part<7;part++){
            const float dy=h*part/7.f;
            const float end=h*(part+1)/7.f;
            rect(x+3,y+dy+2,w-6,end-dy+1,
                color(122+part*2,115+part*2,139+part*2,c8((focused?90:55)*alpha)));
        }
        roundRect(x,y,w,h,27,color(145,138,163,c8((focused?78:43)*alpha)));
        if(focused){
            outline(x+1,y+1,w-2,h-2,26,color(247,245,254,c8(230*alpha)));
            outline(x+2,y+2,w-4,h-4,25,color(198,192,217,c8(115*alpha)));
        }
    }
    static SDL_Texture* gradient(SDL_Renderer*r,int dim,int type){
        SDL_Surface*s=SDL_CreateRGBSurfaceWithFormat(0,dim,dim,32,SDL_PIXELFORMAT_RGBA32);
        if(!s)return nullptr;
        auto*p=static_cast<Uint32*>(s->pixels);
        auto fmt=s->format;
        for(int y=0;y<dim;y++)for(int x=0;x<dim;x++){
            float dx=(x+0.5f-dim/2.f)/(dim/2.f),dy=(y+0.5f-dim/2.f)/(dim/2.f);
            float d=std::sqrt(dx*dx+dy*dy),a=0.f;
            float red=20,green=15,blue=60;
            // Original Relay PortalBackdropView has radial shaders with fixed
            // color stops. Rasterize those stops, rather than using generic
            // Gaussian approximations that flatten the sphere and its haze.
            struct Stop {float pos,r,g,b,a;};
            static constexpr Stop sphereStops[]={{0,23,14,62,255},{.72f,22,14,56,255},
                {.968f,27,18,72,255},{1,27,18,72,0}};
            static constexpr Stop haloStops[]={{0,46,26,145,0},{.49f,51,35,186,116},
                {.70f,39,35,103,74},{1,0,0,0,0}};
            static constexpr Stop cloudStops[]={{0,60,32,255,101},{.36f,36,11,232,41},
                {1,0,0,0,0}};
            static constexpr Stop glowStops[]={{0,236,240,255,255},{.08f,236,240,255,255},
                {.19f,191,202,255,255},{.38f,102,117,255,255},
                {.7f,47,37,255,128},{1,0,0,0,0}};
            static constexpr Stop floorStops[]={{0,77,78,170,52},{1,77,78,170,0}};
            const Stop*stops=nullptr;int count=0;
            switch(type){
                case 0:stops=sphereStops;count=4;break;
                case 1:stops=haloStops;count=4;
                    // Android RadialGradient(center=.12,-.06,radius=1.85)
                    // is painted across a radius-1.85 disk.
                    d=std::sqrt((dx*1.85f-.12f)*(dx*1.85f-.12f)+
                                (dy*1.85f+.06f)*(dy*1.85f+.06f))/1.85f;
                    break;
                case 2:stops=glowStops;count=6;break;
                case 3:stops=cloudStops;count=3;break;
                case 5: {
                    // PointPaint's rounded low-alpha blue widths; texture alpha
                    // falls smoothly to zero, unlike untextured square quads.
                    static constexpr Stop bloomStops[]={{0,30,18,255,255},
                        {.3f,30,18,255,230},{.7f,30,18,255,75},
                        {1,30,18,255,0}};
                    stops=bloomStops;count=4;break;
                }
                case 6: {
                    static constexpr Stop coreStops[]={{0,255,255,255,255},
                        {.40f,255,255,255,255},{.78f,255,255,255,235},
                        {1,255,255,255,0}};
                    stops=coreStops;count=4;break;
                }
                default:stops=floorStops;count=2;break;
            }
            d=std::clamp(d,0.f,1.f);
            int i=0;while(i<count-2 && d>stops[i+1].pos)i++;
            const auto&lo=stops[i];const auto&hi=stops[i+1];
            float u=std::clamp((d-lo.pos)/(hi.pos-lo.pos),0.f,1.f);
            red=lo.r+(hi.r-lo.r)*u;
            green=lo.g+(hi.g-lo.g)*u;
            blue=lo.b+(hi.b-lo.b)*u;
            a=lo.a+(hi.a-lo.a)*u;
            if(type==0 && d>=1)a=0;
            p[y*dim+x]=SDL_MapRGBA(fmt,c8(static_cast<int>(red)),
                c8(static_cast<int>(green)),c8(static_cast<int>(blue)),c8(static_cast<int>(a)));
        }
        SDL_Texture*t=SDL_CreateTextureFromSurface(r,s);SDL_FreeSurface(s);
        if(t)SDL_SetTextureBlendMode(t,SDL_BLENDMODE_BLEND);
        return t;
    }
    void initParticles(){
        JavaRandom rand(0x504f5254414cULL);
        for(int i=0;i<CLUSTERS;i++){
            float latitude=-.91f+i*1.82f/(CLUSTERS-1);
            float angle=i*2.39996f+rand.nextFloat()*.45f;
            float circle=std::sqrt(1-latitude*latitude);
            clusters[i]={std::cos(angle)*circle,latitude,std::sin(angle)*circle,
                .055f+rand.nextFloat()*.105f};
        }
        for(int i=0;i<PARTICLES;i++){
            auto &cl=clusters[i%CLUSTERS];float x,y,z;
            if(i%11==0){float latitude=rand.nextFloat()*2-1;
                float angle=rand.nextFloat()*2*PI;
                float circle=std::sqrt(1-latitude*latitude);
                x=std::cos(angle)*circle;y=latitude;z=std::sin(angle)*circle;
            }else{
                x=cl.x+rand.nextGaussian()*cl.spread;
                y=cl.y+rand.nextGaussian()*cl.spread*1.8f;
                z=cl.z+rand.nextGaussian()*cl.spread;
            }
            float shell=.87f+rand.nextFloat()*.12f;
            float len=std::sqrt(x*x+y*y+z*z);
            particles[i]={x/len*shell,y/len*shell,z/len*shell,
                rand.nextFloat()*2*PI,.42f+rand.nextFloat()*.58f};
        }
    }
    void backdrop(float time){
        // Same source gradient stops as PortalBackdropView.kt, in 1920×1080 design coords.
        for(int y=0;y<DESIGN_H;y+=4){float t=static_cast<float>(y)/DESIGN_H;
            int R,G,B;
            if(t<.67f){float u=t/.67f;R=3+23*u;G=3+23*u;B=7+27*u;}
            else {float u=(t-.67f)/.33f;R=26+9*u;G=26+9*u;B=34+9*u;}
            rect(0,static_cast<float>(y),DESIGN_W,4,color(R,G,B));
        }
        float cx=DESIGN_W*.5f,cy=DESIGN_H*.5f;
        float base=std::min(DESIGN_H*.3315f,DESIGN_W*.40f);
        float growth=std::clamp((time-.1f)/.9f,0.f,1.f);
        growth=1-(1-growth)*(1-growth);
        float radius=base*(.04f+.96f*growth);
        // Original floor is an elliptical 1.4-radius bloom, .085 high.
        texture(floorGlow,cx-base*1.40f,DESIGN_H*.916f-base*.085f,
            base*2.80f,base*.17f);
        // Original halo circle radius=1.85 and translated shader origin.
        texture(halo,cx-radius*1.85f,cy-radius*1.85f,
            radius*3.70f,radius*3.70f,c8((215+std::clamp((2.7f-time)/1.8f,0.f,1.f)*40)*growth));
        texture(sphere,cx-radius,cy-radius,2*radius,2*radius,c8(255*growth));
        // Preserve particle seed, 0.075-radian/sec rotation and twinkle frequency.
        const float motionTime=reduceMotion?12.f:time;
        const float C=std::cos(motionTime*.075f),S=std::sin(motionTime*.075f);
        // Recreate all 19 independent, rotated elliptical nebula clouds from
        // Android's drawClouds() before drawing the binned point passes.
        for(const auto &cl:clusters){
            float x=cl.x*C+cl.z*S;
            float depth=-cl.x*S+cl.z*C;
            float y=cl.y;
            float spread=.12f+cl.spread*1.45f;
            float xx=cx+(x*.974f+y*.225f)*.94f*radius;
            float yy=cy+(y*.974f-x*.225f)*.94f*radius;
            Uint8 alpha=c8(static_cast<int>((40+(depth+1)*34)*growth));
            clippedCloud(cx,cy,radius,(xx-cx)/radius,(yy-cy)/radius,
                spread,spread*1.6f,alpha);
        }
        // Android draws rounded point passes with progressively smaller bloom
        // radii. Batch textured, alpha-tapered quads so no square halos remain
        // and the image can be rendered in a few accelerated GPU calls.
        struct Batch {std::vector<SDL_Vertex> vertices;std::vector<int> indices;};
        Batch bloomBatch,coreBatch;
        struct BrightPoint {float x,y,size;Uint8 alpha;};
        std::array<BrightPoint,88> brightPoints{};
        int brightCount=0;
        auto appendQuad=[&](Batch&batch,float x,float y,float size,SDL_Color tint){
            auto&verts=batch.vertices;auto&indices=batch.indices;
            int offset=static_cast<int>(verts.size());
            float half=size*.5f;
            verts.push_back({{x-half,y-half},tint,{0,0}});
            verts.push_back({{x+half,y-half},tint,{1,0}});
            verts.push_back({{x+half,y+half},tint,{1,1}});
            verts.push_back({{x-half,y+half},tint,{0,1}});
            indices.insert(indices.end(),{offset,offset+1,offset+2,offset,offset+2,offset+3});
        };
        for(int particleIndex=0;particleIndex<PARTICLES;particleIndex++){
            const auto &p=particles[particleIndex];
            float x=p.x*C+p.z*S;
            float depth=-p.x*S+p.z*C;
            float y=p.y+.014f*std::sin(motionTime*.55f+p.phase)*(1-p.y*p.y);
            float xx=cx+(x*.974f+y*.225f)*radius;
            float yy=cy+(y*.974f-x*.225f)*radius;
            float twinkle=.81f+.19f*std::sin(motionTime*1.3f+p.phase);
            float strength=p.strength*(.52f+.48f*(depth+1)*.5f)*twinkle;
            int bin=std::clamp(static_cast<int>(strength*7.99f),0,7);
            // Original glow width is proportional to radius/358, with a
            // larger multi-pixel optical bloom on the strongest light points.
            float scale=radius/358.f;
            if(bin>=2){
                appendQuad(bloomBatch,xx,yy,(10+bin*1.4f)*scale,
                    color(255,255,255,(2+bin*.6f)*growth*.72f));
                appendQuad(bloomBatch,xx,yy,(5.5f+bin*.9f)*scale,
                    color(255,255,255,(5+bin*1.5f)*growth*.72f));
                appendQuad(bloomBatch,xx,yy,(2.6f+bin*.45f)*scale,
                    color(255,255,255,(8+bin*2.f)*growth*.72f));
            }
            appendQuad(coreBatch,xx,yy,(1.05f+bin*.22f)*scale,
                color(20+bin*3,22+bin*3,255,(48+bin*25)*growth*.7f));
            // Android only emits 88 potential glow sprites by sampling every
            // 31st particle, and only when the particle faces the camera.
            if(particleIndex%31==0 && brightCount<static_cast<int>(brightPoints.size()) && depth>-.4f){
                float g=(6.f+strength*11.f)*scale;
                brightPoints[brightCount++]={xx,yy,g,c8((190+65*strength)*growth)};
            }
        }
        for(auto*batch : {&bloomBatch,&coreBatch}){
            SDL_Texture *sprite=batch==&bloomBatch?pointBloom:pointCore;
            if(sprite && !batch->vertices.empty()){
                SDL_RenderGeometry(r,sprite,batch->vertices.data(),
                    static_cast<int>(batch->vertices.size()),batch->indices.data(),
                    static_cast<int>(batch->indices.size()));
            }
        }
        // Android paints these sparse, stronger white-blue highlights last.
        for(int i=0;i<brightCount;++i){
            const auto&b=brightPoints[i];
            texture(dot,b.x-b.size,b.y-b.size,b.size*2,b.size*2,b.alpha);
        }
        if(time<2.7f){float opacity=std::clamp((2.7f-time)/1.8f,0.f,1.f);
            const float start=time*570.f-90.f,sweep=std::min(growth*430.f,360.f);
            arc(cx,cy,radius,start,sweep,opacity);
        }
    }
    void nav(float time){
        const float fade=std::clamp((time-3.1f)/.18f,0.f,1.f);
        texture(assets.nav[tab],77,80,340,97,c8(fade*255));
        texture(assets.wifi,1515,110,32,32,c8(fade*255));
        texture(assets.battery,1612,110,42,34,c8(fade*255));
        char clock[32]={};std::time_t now=std::time(nullptr);
        if(auto*tm=std::localtime(&now))std::strftime(clock,sizeof(clock),"%I:%M %p",tm);
        text(clock,1690,108,assets.medium,color(255,255,255),fade);
        texture(assets.sliders,1840,99,40,40,c8(fade*255));
    }
    void content(float time){
        float fade=std::clamp((time-3.3f)/.2f,0.f,1.f);
        float transition=std::clamp((time-tabChange)/.3f,0.f,1.f);
        fade*=transition;
        if(tab==0){
            text("Connect to your PS5",84,305,assets.large,color(250,250,255),fade);
            text("Remote Play JB  ·  192.0.2.10",84,381,assets.body,
                color(197,193,215),fade);
            roundRect(1512,926,318,82,43,color(0,0,0,c8(50*fade)));
            roundRect(1515,921,312,82,41,color(250,250,255,c8(255*fade)));
            text("Connect",1607,941,assets.medium,color(30,27,49),fade);
        }else if(tab==1){
            text("Cloud Streaming",84,305,assets.large,color(250,250,255),fade);
            text("Connect using PlayStation Plus",84,382,assets.body,color(197,193,215),fade);
        }else{
            text("Search",84,305,assets.large,color(250,250,255),fade);
            roundRect(84,424,620,70,22,color(91,82,122,c8(65*fade)));
            text("Search for a console",106,434,assets.body,color(207,200,221),fade);
        }
        if(showInputHints){
            texture(assets.hintL,84,956,31,26,c8(255*fade));
            text(" / ",125,951,assets.body,color(226,226,241),fade);
            texture(assets.hintR,174,956,31,26,c8(255*fade));
            text("Switch Tabs",220,951,assets.body,color(226,226,241),fade);
        }
    }
    static float itemY(int item){
        if(item==0)return 132.f;
        if(item>=1 && item<=4)return 375.f+(item-1)/2*96.f;
        return 611.f+(item-5)*181.f;
    }
    static float itemHeight(int item){
        return item>=1 && item<=4?80.f:item==0?145.f:137.f;
    }
    void revealSelection(){
        const float y=itemY(selected),h=itemHeight(selected);
        if(y-quickScroll<124)quickScroll=y-130;
        else if(y+h-quickScroll>1030)quickScroll=y+h-1030;
        quickScroll=std::clamp(quickScroll,0.f,635.f);
    }
    void refreshSystemStatus(){
        if(networkServiceReady){
            NifmInternetConnectionType type{};
            NifmInternetConnectionStatus status{};
            u32 bars=0;
            if(R_SUCCEEDED(nifmGetInternetConnectionStatus(&type,&bars,&status))){
                wifiStrength=std::min(bars,3u);
                networkState=status==NifmInternetConnectionStatus_Connected?
                    "Connected": "Connecting / offline";
            }else networkState="Offline / no route";
            u32 address=0;
            if(R_SUCCEEDED(nifmGetCurrentIpAddress(&address))){
                struct in_addr addr{};
                addr.s_addr=address;
                const char *ip=inet_ntoa(addr);
                switchIp=ip?ip:"Unavailable";
            }else switchIp="Unavailable";
        }
        if(batteryServiceReady){
            u32 percent=0;
            if(R_SUCCEEDED(psmGetBatteryChargePercentage(&percent)))
                batteryPercent=std::min(percent,100u);
        }
    }
    void setBrightness(float value){
        const float next=std::clamp(value,.03f,1.f);
        if(brightnessAvailable && R_SUCCEEDED(lblSetCurrentBrightnessSetting(next))){
            lblApplyCurrentBrightnessSettingToBacklight();
            brightness=next;
        }
    }
    void adjustSelected(int direction){
        if(quickPage==QuickPage::Home && selected==5)setBrightness(brightness+direction*.05f);
        else if(quickPage==QuickPage::Audio){
            if(pageSelected==0){cueVolume=std::clamp(cueVolume+direction*10,0,100);
                assets.setCueVolume(cueVolume);}
            else if(pageSelected==1){musicVolume=std::clamp(musicVolume+direction*10,0,100);
                assets.setMusicVolume(musicVolume);}
        }
    }
    void openPage(QuickPage page){
        quickPage=page;pageSelected=0;assets.cue(3);
        if(page==QuickPage::Network || page==QuickPage::NetworkStatus)
            refreshSystemStatus();
    }
    void chooseQuickItem(){
        if(quickPage!=QuickPage::Home){
            switch(quickPage){
            case QuickPage::Players:
                if(pageSelected==0){showingQuick=false;menuChange=seconds();
                    checkNativeConnection();showingBackendNotice=!awaitingRecoveryConfirmation;}
                else quickPage=QuickPage::Home;
                break;
            case QuickPage::Audio:
                if(pageSelected==2){cueVolume=cueVolume?0:70;
                    assets.setCueVolume(cueVolume);}
                else if(pageSelected==3){musicVolume=musicVolume?0:55;
                    assets.setMusicVolume(musicVolume);}
                else if(pageSelected>=4)quickPage=QuickPage::Home;
                break;
            case QuickPage::Network:
            case QuickPage::NetworkStatus:
                if(pageSelected==0){checkNativeConnection();refreshSystemStatus();}
                else quickPage=QuickPage::Home;
                break;
            case QuickPage::Settings:
                if(pageSelected==0)reduceMotion=!reduceMotion;
                else if(pageSelected==1)showInputHints=!showInputHints;
                else if(pageSelected==2){musicVolume=musicVolume?0:55;
                    assets.setMusicVolume(musicVolume);}
                else if(pageSelected==3){cueVolume=cueVolume?0:70;
                    assets.setCueVolume(cueVolume);}
                else quickPage=QuickPage::Home;
                break;
            default:break;
            }
            assets.cue(1);return;
        }
        if(selected==0){
            showingQuick=false;menuChange=seconds();
            checkNativeConnection();showingBackendNotice=!awaitingRecoveryConfirmation;
        }else if(selected>=1 && selected<=4){
            selectedPlayer=selected;openPage(QuickPage::Players);
        }else if(selected==5){
            adjustSelected(1);
        }else if(selected==6)openPage(QuickPage::Audio);
        else if(selected==7)openPage(QuickPage::Network);
        else if(selected==8)openPage(QuickPage::NetworkStatus);
        else if(selected==9)openPage(QuickPage::Settings);
        else if(selected==10){showingQuick=false;menuChange=seconds();}
        assets.cue(1);
    }
    void slider(float x,float y,float width,float value,float alpha){
        roundRect(x,y,width,8,4,color(165,159,183,c8(190*alpha)));
        roundRect(x,y,width*std::clamp(value,0.f,1.f),8,4,
            color(243,242,253,c8(240*alpha)));
        roundRect(x+width*std::clamp(value,0.f,1.f)-14,y-10,28,28,14,
            color(253,252,255,c8(255*alpha)));
    }
    void quickPanel(float time){
        float progress=std::clamp((time-menuChange)/.3f,0.f,1.f);
        if(!showingQuick)progress=1-progress;
        if(progress<=0)return;
        rect(0,0,DESIGN_W,DESIGN_H,color(0,0,0,c8(102*progress)));
        const float x=1216.f+(1-progress)*704.f;
        rect(x,0,704,DESIGN_H,color(35,31,50,c8(250*progress)));
        char clock[32]={};
        std::time_t now=std::time(nullptr);
        if(auto*tm=std::localtime(&now))std::strftime(clock,sizeof(clock),"%I:%M",tm);
        text(clock,x+42,29,assets.medium,color(250,249,255),progress);
        texture(assets.wifi,x+576,42,34,34,c8(255*progress));
        texture(assets.battery,x+636,42,42,34,c8(255*progress));
        if(quickPage!=QuickPage::Home){
            const char *titles[]={"","Players","Audio Devices","Network","Network Status","Settings"};
            text(titles[static_cast<int>(quickPage)],x+40,119,assets.large,
                color(252,252,255),progress);
            line(x+34,194,x+668,194,color(174,170,195,c8(125*progress)));
            auto subRow=[&](int index,const std::string&label,const std::string&detail){
                const float y=225+index*164.f;
                menuCard(x+32,y,640,139,index==pageSelected,progress);
                text(label,x+65,y+24,assets.medium,color(255,254,255),progress);
                if(!detail.empty())text(detail,x+65,y+78,assets.small,
                    color(219,214,231),progress);
            };
            if(quickPage==QuickPage::Players){
                subRow(0,"Connect as Player "+std::to_string(selectedPlayer),
                    "Player assignments are decided by your PS5");
                subRow(1,"Back","No player is assigned before connecting");
            }else if(quickPage==QuickPage::Audio){
                subRow(0,"Menu sound cues",std::to_string(cueVolume)+"%  |  Left / Right");
                slider(x+365,327,260,cueVolume/100.f,progress);
                subRow(1,"Menu music",std::to_string(musicVolume)+"%  |  Left / Right");
                slider(x+365,491,260,musicVolume/100.f,progress);
                subRow(2,"Sound effects",cueVolume?"On":"Off");
                subRow(3,"Background music",musicVolume?"On":"Off");
                subRow(4,"Back","");
            }else if(quickPage==QuickPage::Network){
                subRow(0,"Check PS5 connection","PBS 192.0.2.10:9999");
                subRow(1,"Switch network","Local IP: "+switchIp);
                subRow(2,"Back","");
            }else if(quickPage==QuickPage::NetworkStatus){
                subRow(0,"Refresh PS5 network status",
                    probeAttempted?lastProbe.status:"No service check run yet");
                subRow(1,"Switch connection",networkState+"  |  "+switchIp);
                subRow(2,"Back","");
            }else if(quickPage==QuickPage::Settings){
                subRow(0,"Reduce Motion",reduceMotion?"On":"Off");
                subRow(1,"Controller hints",showInputHints?"On":"Off");
                subRow(2,"Menu music",musicVolume?"On":"Off");
                subRow(3,"Menu sounds",cueVolume?"On":"Off");
                subRow(4,"Back","");
            }
            text("B  Back",x+45,1030,assets.small,color(221,217,235),progress);
            return;
        }
        SDL_Rect viewport{static_cast<int>(x),109,704,915};
        SDL_RenderSetClipRect(r,&viewport);
        const float scroll=quickScroll;
        const float remoteY=itemY(0)-scroll;
        menuCard(x+30,remoteY,644,145,selected==0,progress);
        if(assets.nav[0]){
            SDL_Rect source{38,52,110,96};
            SDL_FRect dst{x+67,remoteY+28,78,78};
            SDL_SetTextureAlphaMod(assets.nav[0],c8(progress*255));
            SDL_RenderCopyF(r,assets.nav[0],&source,&dst);
        }
        text("Remote Play JB",x+182,remoteY+27,assets.medium,color(255,255,255),progress);
        text("Connect",x+182,remoteY+78,assets.body,color(219,214,231),progress);
        const float playersY=295-scroll;
        roundRect(x+30,playersY,644,284,28,color(137,129,159,c8(53*progress)));
        text("Players",x+54,playersY+10,assets.body,color(248,248,253),progress);
        for(int player=1;player<=4;player++){
            const float px=x+47+(player-1)%2*313;
            const float py=itemY(player)-scroll;
            menuCard(px,py,298,80,selected==player,progress);
            roundRect(px+13,py+15,51,51,25,color(106,99,125,c8(247*progress)));
            text(std::to_string(player),px+29,py+20,assets.body,color(255,255,255),progress);
            text("P"+std::to_string(player),px+73,py+12,assets.body,color(252,251,255),progress);
            text("Waiting",px+73,py+48,assets.small,color(211,206,224),progress);
        }
        const char* labels[]={"Brightness","Audio Devices","Network",
                              "Network Status","Settings","Close"};
        const int glyphs[]={2,3,4,4,5,6};
        for(int item=5;item<=10;item++){
            const float y=itemY(item)-scroll;
            if(y>1040 || y+140<106)continue;
            menuCard(x+30,y,644,137,selected==item,progress);
            menuGlyph(glyphs[item-5],x+55,y+34,progress);
            if(item==5){
                slider(x+202,y+62,368,brightness,progress);
                char value[50];
                std::snprintf(value,sizeof(value),"%d%%",static_cast<int>(brightness*100));
                text(value,x+582,y+49,assets.small,color(231,226,245),progress);
            }else text(labels[item-5],x+174,y+46,assets.body,
                color(254,252,255),progress);
        }
        SDL_RenderSetClipRect(r,nullptr);
        if(quickScroll>0)rect(x+30,107,644,4,color(232,229,247,c8(80*progress)));
        if(quickScroll<635)rect(x+30,1042,644,3,color(232,229,247,c8(70*progress)));
    }
    void notice(){
        if(awaitingRecoveryConfirmation){
            roundRect(121,281,1678,523,36,color(152,148,178,230));
            roundRect(125,285,1670,515,32,color(19,22,47,248));
            text("PS5 Capture Recovery Confirmation",174,337,assets.medium,color(255,255,255));
            text("Only continue if the PS5 was fully rebooted and re-jailbroken",174,435,
                assets.body,color(236,233,246));
            text("AFTER the previous failed high-resolution capture.",174,481,
                assets.body,color(236,233,246));
            text("Hold L + R and press A to acknowledge. Press B to cancel.",174,617,
                assets.body,color(235,221,246));
            return;
        }
        if(!showingBackendNotice)return;
        roundRect(131,311,1658,463,36,color(152,148,178,230));
        roundRect(135,315,1650,455,32,color(19,22,47,245));
        text("Remote Play JB  |  Native service check",180,365,assets.medium,color(255,255,255));
        text(probeAttempted?lastProbe.status:"Connect to your PS5 to check the PBS service",180,445,
            assets.body,color(220,220,238));
        if(lastProbe.streamReady) {
            char resolution[100];
            std::snprintf(resolution,sizeof(resolution),"Capture detected: %d x %d, %d fps",
                lastProbe.width,lastProbe.height,lastProbe.fps);
            text(resolution,180,505,assets.body,color(220,220,238));
        }
        text("PS5 video decoding and gamepad streaming are not integrated yet.",180,565,
            assets.body,color(220,220,238));
        text("Press B to return",180,660,assets.body,color(204,202,232));
    }
public:
    explicit Scene(SDL_Renderer*renderer):r(renderer){initParticles();}
    bool init(){
        sphere=gradient(r,512,0);halo=gradient(r,512,1);dot=gradient(r,64,2);
        cloud=gradient(r,128,3);floorGlow=gradient(r,256,4);
        pointBloom=gradient(r,64,5);pointCore=gradient(r,32,6);
        assets.init(r);
        brightnessAvailable=R_SUCCEEDED(lblInitialize());
        if(brightnessAvailable){
            float setting=brightness;
            if(R_SUCCEEDED(lblGetCurrentBrightnessSetting(&setting)))
                brightness=std::clamp(setting,.03f,1.f);
        }
        batteryServiceReady=R_SUCCEEDED(psmInitialize());
        networkServiceReady=R_SUCCEEDED(nifmInitialize(NifmServiceType_User));
        refreshSystemStatus();
        timeStart=SDL_GetTicks();tabChange=seconds();
        return sphere && pointBloom && pointCore;
    }
    void switchTab(int next){
        if(tab==next || showingQuick)return;
        tab=std::clamp(next,0,2);tabChange=seconds();assets.cue(0);
    }
    void tap(float x,float y){
        if(showingVideo)return;
        if(awaitingRecoveryConfirmation){
            // The recovery acknowledgement intentionally requires physical
            // shoulder buttons; touch can cancel the pending dialog.
            input(HidNpadButton_B);return;
        }
        if(showingBackendNotice){input(HidNpadButton_B);return;}
        if(showingQuick){
            if(x<1216){input(HidNpadButton_B);return;}
            if(quickPage!=QuickPage::Home){
                const int count=quickPage==QuickPage::Players?2:
                    (quickPage==QuickPage::Audio || quickPage==QuickPage::Settings?5:3);
                const int i=static_cast<int>((y-225)/164);
                if(y>=225 && i>=0 && i<count){
                    pageSelected=i;chooseQuickItem();
                }else if(y<200){quickPage=QuickPage::Home;assets.cue(2);}
                return;
            }
            const float listY=y+quickScroll;
            for(int i=0;i<=10;i++){
                if(i>=1 && i<=4){
                    const float start=1216+47+(i-1)%2*313;
                    if(x<start || x>=start+298)continue;
                }
                if(listY>=itemY(i) && listY<itemY(i)+itemHeight(i)){
                    selected=i;revealSelection();
                    if(i==5 && x>=1216+202 && x<=1216+570)
                        setBrightness((x-(1216+202))/368.f);
                    else chooseQuickItem();
                    return;
                }
            }
            return;
        }
        // These hitboxes match nav(), content() and quickPanel() exactly.
        if(x>=1810 && y>=70 && y<175){input(HidNpadButton_X);return;}
        if(x>=77 && x<417 && y>=80 && y<185){
            switchTab(std::clamp(static_cast<int>((x-77)/113.34f),0,2));return;
        }
        if(tab==0 && x>=1515 && x<1827 && y>=921 && y<1003){
            input(HidNpadButton_A);return;
        }
        if(tab==1 && x>=84 && x<980 && y>=300 && y<550){
            input(HidNpadButton_A);return;
        }
        if(tab==2 && x>=84 && x<704 && y>=424 && y<494){
            input(HidNpadButton_A);
        }
    }
    void handleTouch(const SwitchTouchFrame&frame){
        if(showingVideo){
            menuFingerActive=false;
            streamTouches.update(frame);
            if(streamTouches.takeExitRequest())stopVideo();
            return;
        }
        streamTouches.clear();
        if(!menuFingerActive){
            if(frame.count>0){
                touchStart=touchLast=frame.points[0];menuFingerActive=true;
            }
            return;
        }
        const SwitchTouchPoint*current=nullptr;
        for(int i=0;i<frame.count;++i)
            if(frame.points[i].id==touchStart.id){current=&frame.points[i];break;}
        if(current){
            touchLast=*current;
            if(showingQuick && quickPage==QuickPage::Home && current->x>=1216){
                // Drag the native quick sheet to reach every Android row.
                if(std::abs(current->y-touchStart.y)>14 &&
                   std::abs(current->y-touchStart.y)>std::abs(current->x-touchStart.x)){
                    quickScroll=std::clamp(quickScroll+(touchLast.y-current->y),0.f,635.f);
                }
                if(selected==5 && switchingBrightness)
                    setBrightness((current->x-(1216+202))/368.f);
            }
            return;
        }
        menuFingerActive=false;
        if(showingQuick && std::abs(touchLast.y-touchStart.y)>38){
            quickScroll=std::clamp(quickScroll-(touchLast.y-touchStart.y),0.f,635.f);
            switchingBrightness=false;
            return;
        }
        switchingBrightness=false;
        // Swiping across the home screen changes tabs without tapping a
        // control unintentionally. Wait for lift to commit menu taps.
        if(std::abs(touchLast.x-touchStart.x)>160 &&
                std::abs(touchLast.y-touchStart.y)<130 && !showingQuick){
            switchTab((tab+(touchLast.x<touchStart.x?1:2))%3);
        }else tap(touchStart.x,touchStart.y);
    }
    void input(u64 pressed){
        if((pressed&HidNpadButton_Plus) && !showingVideo){appExitRequested=true;return;}
        if(showingVideo){
            return;
        }
        if(awaitingRecoveryConfirmation){
            if(pressed&HidNpadButton_B){awaitingRecoveryConfirmation=false;assets.cue(2);}
            return;
        }
        if(showingBackendNotice){if(pressed&HidNpadButton_B){showingBackendNotice=false;assets.cue(2);}return;}
        if(pressed&HidNpadButton_X){
            showingQuick=!showingQuick;menuChange=seconds();
            selected=0;quickPage=QuickPage::Home;quickScroll=0;
            assets.cue(showingQuick?3:4);return;
        }
        if(showingQuick){
            if(pressed&HidNpadButton_B){
                if(quickPage!=QuickPage::Home)quickPage=QuickPage::Home;
                else {showingQuick=false;menuChange=seconds();}
                assets.cue(2);return;
            }
            if(pressed&(HidNpadButton_Left|HidNpadButton_Right)){
                adjustSelected(pressed&HidNpadButton_Left?-1:1);
                assets.cue(0);return;
            }
            const int last=quickPage==QuickPage::Home?10:
                quickPage==QuickPage::Players?1:
                (quickPage==QuickPage::Audio||quickPage==QuickPage::Settings)?4:2;
            int& focus=quickPage==QuickPage::Home?selected:pageSelected;
            if(pressed&HidNpadButton_Down){
                int next=std::min(focus+1,last);
                if(next!=focus){focus=next;assets.cue(0);if(quickPage==QuickPage::Home)revealSelection();}
            }
            if(pressed&HidNpadButton_Up){
                int next=std::max(focus-1,0);
                if(next!=focus){focus=next;assets.cue(0);if(quickPage==QuickPage::Home)revealSelection();}
            }
            if(pressed&HidNpadButton_A)chooseQuickItem();
        }else{
            if(pressed&(HidNpadButton_L|HidNpadButton_Left))switchTab((tab+2)%3);
            if(pressed&(HidNpadButton_R|HidNpadButton_Right))switchTab((tab+1)%3);
            if(pressed&HidNpadButton_B){assets.cue(2);tab=0;tabChange=seconds();}
            if(pressed&HidNpadButton_A){assets.cue(1);showingBackendNotice=true;
                if(tab==0)checkNativeConnection();}
            if(pressed&HidNpadButton_Minus){showingQuick=true;selected=9;
                quickPage=QuickPage::Home;revealSelection();menuChange=seconds();assets.cue(3);}
        }
    }
    void checkNativeConnection(){
        // Read-only native port of Relay's PbsClient.config() readiness probe.
        // No profile changes, ELF injection, controller ownership or retries.
        probeAttempted=true;
        lastProbe=probeRelayPbs("192.0.2.10",9999);
        if(lastProbe.streamReady){
            showingBackendNotice=false;
            awaitingRecoveryConfirmation=true;
        }
    }
    void acknowledgeRecovery(bool userHeldBothShoulders){
        if(!awaitingRecoveryConfirmation || !userHeldBothShoulders)return;
        awaitingRecoveryConfirmation=false;
        if(media.start("192.0.2.10",9999,lastProbe,true)){
            showingVideo=true;
            Mix_Pause(0);
            assets.cue(1);
        }else{
            showingBackendNotice=true;
        }
    }
    void stopVideo(){
        if(!showingVideo)return;
        media.stop();showingVideo=false;stopComboStartMs=0;
        streamTouches.clear();
        if(videoTexture){SDL_DestroyTexture(videoTexture);videoTexture=nullptr;}
        Mix_Resume(0);assets.cue(2);
    }
    void updateGamepad(u64 held,HidAnalogStickState left,HidAnalogStickState right){
        if(!showingVideo)return;
        // Single sideways Joy-Cons cannot emit Plus and Minus together; their
        // SL+SR+Start shoulder combination provides a physical exit path.
        const bool stopping=((held&HidNpadButton_Plus) && (held&HidNpadButton_Minus)) ||
            ((held&HidNpadButton_AnySL) && (held&HidNpadButton_AnySR) &&
             (held&(HidNpadButton_Plus|HidNpadButton_Minus)));
        if(stopping){
            if(!stopComboStartMs)stopComboStartMs=SDL_GetTicks();
            if(SDL_GetTicks()-stopComboStartMs>1400){
                stopVideo();
            }
            return;
        }
        stopComboStartMs=0;
        auto convert=[](int value)->uint8_t{
            long long raw=static_cast<long long>(value)+32768;
            return static_cast<uint8_t>(std::clamp((raw*255+32767)/65535,0LL,255LL));
        };
        RelayPadState p;
        // Position-preserving physical PlayStation button mapping.
        if(held&HidNpadButton_B)p.buttons|=0x4000; // bottom: CROSS
        if(held&HidNpadButton_A)p.buttons|=0x2000; // right: CIRCLE
        if(held&HidNpadButton_Y)p.buttons|=0x8000; // left: SQUARE
        if(held&HidNpadButton_X)p.buttons|=0x1000; // top: TRIANGLE
        if(held&HidNpadButton_Up)p.buttons|=0x10;
        if(held&HidNpadButton_Right)p.buttons|=0x20;
        if(held&HidNpadButton_Down)p.buttons|=0x40;
        if(held&HidNpadButton_Left)p.buttons|=0x80;
        if(held&HidNpadButton_L)p.buttons|=0x400;
        if(held&HidNpadButton_R)p.buttons|=0x800;
        if(held&HidNpadButton_ZL){p.buttons|=0x100;p.l2=255;}
        if(held&HidNpadButton_ZR){p.buttons|=0x200;p.r2=255;}
        if(held&HidNpadButton_StickL)p.buttons|=0x2;
        if(held&HidNpadButton_StickR)p.buttons|=0x4;
        if(held&HidNpadButton_Plus)p.buttons|=0x8;
        if(held&HidNpadButton_Minus)p.buttons|=0x1;
        p.lx=convert(left.x);p.ly=convert(-left.y);
        p.rx=convert(right.x);p.ry=convert(-right.y);
        // Physical controller and simultaneous touchscreen buttons share the
        // same outbound PBS state. A touched virtual stick takes priority only
        // for that axis; disconnected controllers naturally send neutral.
        const auto&touch=streamTouches.pad();
        p.buttons|=touch.buttons;
        p.l2=std::max(p.l2,touch.l2);p.r2=std::max(p.r2,touch.r2);
        if(streamTouches.leftStickActive()){p.lx=touch.lx;p.ly=touch.ly;}
        if(streamTouches.rightStickActive()){p.rx=touch.rx;p.ry=touch.ry;}
        media.setControllerState(p);
    }
    bool appExitRequested=false;
    void frame(){
        float time=seconds();
        SDL_SetRenderDrawColor(r,3,3,7,255);SDL_RenderClear(r);
        if(showingVideo){
            int w=0,h=0;
            if(media.takeVideoFrame(videoPixels,w,h)){
                if(!videoTexture || w!=videoWidth || h!=videoHeight){
                    if(videoTexture)SDL_DestroyTexture(videoTexture);
                    videoTexture=SDL_CreateTexture(r,SDL_PIXELFORMAT_RGBA32,
                        SDL_TEXTUREACCESS_STREAMING,w,h);
                    videoWidth=w;videoHeight=h;
                }
                if(videoTexture)SDL_UpdateTexture(videoTexture,nullptr,videoPixels.data(),w*4);
            }
            if(videoTexture){texture(videoTexture,0,0,1920,1080);}
            else{backdrop(time);text(media.status(),84,410,assets.medium,color(248,246,254));}
            rect(0,0,1920,68,color(11,12,25,170));
            text("RELAY  |  PS5 VIDEO  |  HOLD + & - TO STOP",84,11,assets.small,color(248,246,254));
        }else{
            backdrop(time);nav(time);content(time);quickPanel(time);notice();
        }
        SDL_RenderPresent(r);
    }
    ~Scene(){
        media.stop();
        if(videoTexture)SDL_DestroyTexture(videoTexture);
        assets.shutdown();for(auto*t:{sphere,halo,dot,cloud,floorGlow,pointBloom,pointCore})
            if(t)SDL_DestroyTexture(t);
        if(networkServiceReady)nifmExit();
        if(batteryServiceReady)psmExit();
        if(brightnessAvailable)lblExit();
    }
};
}

int main(int argc,char**argv){
    (void)argc;(void)argv;
    romfsInit();
    const bool networkReady=socketInitializeDefault()==0;
    if(SDL_Init(SDL_INIT_VIDEO|SDL_INIT_AUDIO|SDL_INIT_GAMECONTROLLER)!=0){
        if(networkReady)socketExit();
        romfsExit();
        return 1;
    }
    IMG_Init(IMG_INIT_PNG);
    if(TTF_Init()!=0){IMG_Quit();SDL_Quit();romfsExit();return 2;}
    SDL_Window*window=SDL_CreateWindow("Relay",SDL_WINDOWPOS_CENTERED,SDL_WINDOWPOS_CENTERED,
        1280,720,SDL_WINDOW_SHOWN|SDL_WINDOW_FULLSCREEN_DESKTOP);
    if(!window){TTF_Quit();IMG_Quit();SDL_Quit();romfsExit();return 3;}
    SDL_Renderer*renderer=SDL_CreateRenderer(window,-1,SDL_RENDERER_ACCELERATED|SDL_RENDERER_PRESENTVSYNC);
    if(!renderer)renderer=SDL_CreateRenderer(window,-1,SDL_RENDERER_SOFTWARE);
    if(!renderer){SDL_DestroyWindow(window);TTF_Quit();IMG_Quit();SDL_Quit();romfsExit();return 4;}
    SDL_RenderSetLogicalSize(renderer,DESIGN_W,DESIGN_H);
    {
        SwitchInput input;
        Scene scene(renderer);
        if(!scene.init()){SDL_DestroyRenderer(renderer);SDL_DestroyWindow(window);
            TTF_Quit();IMG_Quit();SDL_Quit();romfsExit();return 5;}
        while(appletMainLoop() && !scene.appExitRequested){
            const auto controller=input.poll();
            scene.input(controller.pressed);
            scene.handleTouch(input.pollTouch(renderer,window));
            const u64 buttons=controller.held;
            scene.updateGamepad(buttons,controller.left,controller.right);
            if((buttons&HidNpadButton_A) && (buttons&HidNpadButton_L) &&
               (buttons&HidNpadButton_R))scene.acknowledgeRecovery(true);
            SDL_Event e;
            while(SDL_PollEvent(&e)){
                if(e.type==SDL_QUIT){scene.appExitRequested=true;break;}
                if(e.type==SDL_KEYDOWN){
                    u64 key=0;
                    switch(e.key.keysym.sym){
                    case SDLK_LEFT:case SDLK_q:key=HidNpadButton_L;break;
                    case SDLK_RIGHT:case SDLK_e:key=HidNpadButton_R;break;
                    case SDLK_ESCAPE:case SDLK_BACKSPACE:key=HidNpadButton_B;break;
                    case SDLK_RETURN:case SDLK_SPACE:key=HidNpadButton_A;break;
                    case SDLK_x:case SDLK_m:key=HidNpadButton_X;break;
                    case SDLK_TAB:key=HidNpadButton_Minus;break;
                    case SDLK_UP:key=HidNpadButton_Up;break;
                    case SDLK_DOWN:key=HidNpadButton_Down;break;
                    default:break;
                    }
                    if(key)scene.input(key);
                }
            }
            scene.frame();
            SDL_Delay(1);
        }
    }
    SDL_DestroyRenderer(renderer);SDL_DestroyWindow(window);
    TTF_Quit();IMG_Quit();SDL_Quit();
    if(networkReady)socketExit();
    romfsExit();return 0;
}

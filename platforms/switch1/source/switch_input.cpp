// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
#include "switch_input.hpp"
#include <algorithm>
#include <cmath>

namespace {
constexpr u64 kDirections=HidNpadButton_Up|HidNpadButton_Down|
    HidNpadButton_Left|HidNpadButton_Right;
constexpr int kStickThreshold=16000;
constexpr int kTouchWidth=1280,kTouchHeight=720; // Native Switch LCD sensor.
constexpr u64 kJoySl=HidNpadButton_LeftSL|HidNpadButton_RightSL;
constexpr u64 kJoySr=HidNpadButton_LeftSR|HidNpadButton_RightSR;

HidNpadIdType padId(int index){
    return index==8?HidNpadIdType_Handheld:static_cast<HidNpadIdType>(index);
}

bool significantStick(HidAnalogStickState stick){
    return std::abs(stick.x)>kStickThreshold || std::abs(stick.y)>kStickThreshold;
}

// Single Joy-Cons are held sideways. libnx reports the physical button bits,
// so rotate their face cluster and stick into standard controller orientation.
void normalizeSingleJoyCon(u32 style,u64&buttons,
                           HidAnalogStickState&left,HidAnalogStickState&right){
    if(style&HidNpadStyleTag_NpadJoyLeft){
        const u64 raw=buttons;
        buttons&=~kDirections;
        if(raw&HidNpadButton_Down)buttons|=HidNpadButton_A;
        if(raw&HidNpadButton_Left)buttons|=HidNpadButton_B;
        if(raw&HidNpadButton_Right)buttons|=HidNpadButton_X;
        if(raw&HidNpadButton_Up)buttons|=HidNpadButton_Y;
        const auto stick=left;
        left={-stick.y,stick.x};
        right={};
    }else if(style&HidNpadStyleTag_NpadJoyRight){
        const u64 raw=buttons;
        buttons&=~(HidNpadButton_A|HidNpadButton_B|
                   HidNpadButton_X|HidNpadButton_Y);
        if(raw&HidNpadButton_X)buttons|=HidNpadButton_A;
        if(raw&HidNpadButton_A)buttons|=HidNpadButton_B;
        if(raw&HidNpadButton_Y)buttons|=HidNpadButton_X;
        if(raw&HidNpadButton_B)buttons|=HidNpadButton_Y;
        const auto stick=right;
        left={stick.y,-stick.x};
        right={};
    }
    if(buttons&kJoySl)buttons|=HidNpadButton_L;
    if(buttons&kJoySr)buttons|=HidNpadButton_R;
}

struct TouchCircle {
    StreamTouchControl control;
    float x,y,r;
};
constexpr TouchCircle kCircles[]={
    {StreamTouchControl::LeftStick,225,845,145},
    {StreamTouchControl::RightStick,1750,845,145},
    {StreamTouchControl::Up,535,745,57},
    {StreamTouchControl::Down,535,940,57},
    {StreamTouchControl::Left,438,842,57},
    {StreamTouchControl::Right,632,842,57},
    {StreamTouchControl::Cross,1460,940,57},
    {StreamTouchControl::Circle,1557,842,57},
    {StreamTouchControl::Square,1363,842,57},
    {StreamTouchControl::Triangle,1460,745,57},
};

bool inRect(float x,float y,float x0,float y0,float x1,float y1){
    return x>=x0 && x<x1 && y>=y0 && y<y1;
}

StreamTouchControl findControl(float x,float y){
    if(inRect(x,y,1775,0,1920,75))return StreamTouchControl::Exit;
    if(inRect(x,y,85,105,230,188))return StreamTouchControl::L1;
    if(inRect(x,y,255,105,400,188))return StreamTouchControl::L2;
    if(inRect(x,y,1520,105,1665,188))return StreamTouchControl::R1;
    if(inRect(x,y,1690,105,1835,188))return StreamTouchControl::R2;
    if(inRect(x,y,810,105,947,183))return StreamTouchControl::Share;
    if(inRect(x,y,973,105,1110,183))return StreamTouchControl::Options;
    for(const auto&circle:kCircles){
        const float dx=x-circle.x,dy=y-circle.y;
        if(dx*dx+dy*dy<=circle.r*circle.r)return circle.control;
    }
    return StreamTouchControl::None;
}

void addButton(RelayPadState&pad,StreamTouchControl control){
    switch(control){
    case StreamTouchControl::Up:pad.buttons|=0x10;break;
    case StreamTouchControl::Right:pad.buttons|=0x20;break;
    case StreamTouchControl::Down:pad.buttons|=0x40;break;
    case StreamTouchControl::Left:pad.buttons|=0x80;break;
    case StreamTouchControl::L1:pad.buttons|=0x400;break;
    case StreamTouchControl::R1:pad.buttons|=0x800;break;
    case StreamTouchControl::L2:pad.buttons|=0x100;pad.l2=255;break;
    case StreamTouchControl::R2:pad.buttons|=0x200;pad.r2=255;break;
    case StreamTouchControl::Cross:pad.buttons|=0x4000;break;
    case StreamTouchControl::Circle:pad.buttons|=0x2000;break;
    case StreamTouchControl::Square:pad.buttons|=0x8000;break;
    case StreamTouchControl::Triangle:pad.buttons|=0x1000;break;
    case StreamTouchControl::Share:pad.buttons|=0x1;break;
    case StreamTouchControl::Options:pad.buttons|=0x8;break;
    default:break;
    }
}

uint8_t virtualAxis(float delta){
    return static_cast<uint8_t>(std::clamp(
        std::lround(128.f+std::clamp(delta/115.f,-1.f,1.f)*127.f),0L,255L));
}
}

SwitchInput::SwitchInput(){
    // Up to eight connected controllers, including individually assigned
    // Joy-Cons; handheld is a ninth candidate for automatic dock transitions.
    padConfigureInput(8,HidNpadStyleSet_NpadStandard);
    for(int i=0;i<9;++i)padInitialize(&pads_[i],padId(i));
    hidInitializeTouchScreen();
}

SwitchControllerFrame SwitchInput::poll(){
    for(auto&pad:pads_)padUpdate(&pad);
    bool available[9]{};
    int candidate=-1;
    for(int i=0;i<9;++i){
        const auto&pad=pads_[i];
        available[i]=padIsConnected(&pad);
        if(!available[i])continue;
        const bool activity=padGetButtonsDown(&pad)!=0 ||
            significantStick(padGetStickPos(&pad,0)) ||
            significantStick(padGetStickPos(&pad,1));
        if(activity && i!=active_ && candidate<0)candidate=i;
    }
    if(candidate>=0)active_=candidate;
    else if(active_<0 || !available[active_]){
        active_=-1;
        // Keep the player-one slot preferred on dock and the actual attached
        // Joy-Cons preferred when switching to handheld mode.
        if(available[8])active_=8;
        else for(int i=0;i<8;++i)if(available[i]){active_=i;break;}
    }

    SwitchControllerFrame frame{};
    if(active_>=0){
        const auto&pad=pads_[active_];
        frame.connected=true;
        frame.source=padId(active_);
        frame.style=padGetStyleSet(&pad);
        frame.held=padGetButtons(&pad);
        frame.left=padGetStickPos(&pad,0);
        frame.right=padGetStickPos(&pad,1);
        normalizeSingleJoyCon(frame.style,frame.held,frame.left,frame.right);
    }
    frame.pressed=frame.held & ~previousButtons_;
    previousButtons_=frame.held;

    u64 directions=frame.held&kDirections;
    if(frame.left.x<-kStickThreshold)directions|=HidNpadButton_Left;
    if(frame.left.x> kStickThreshold)directions|=HidNpadButton_Right;
    if(frame.left.y> kStickThreshold)directions|=HidNpadButton_Up;
    if(frame.left.y<-kStickThreshold)directions|=HidNpadButton_Down;
    const u32 now=SDL_GetTicks();
    frame.pressed|=directions&~previousDirections_;
    if(directions!=previousDirections_)nextRepeatMs_=now+380;
    else if(directions && SDL_TICKS_PASSED(now,nextRepeatMs_)){
        frame.pressed|=directions;
        nextRepeatMs_=now+105;
    }
    previousDirections_=directions;
    return frame;
}

SwitchTouchFrame SwitchInput::pollTouch(SDL_Renderer*renderer,SDL_Window*window)const{
    SwitchTouchFrame frame{};
    if(!renderer || !window)return frame;
    HidTouchScreenState state{};
    if(hidGetTouchScreenStates(&state,1)==0)return frame;
    int w=0,h=0;
    SDL_GetWindowSize(window,&w,&h);
    if(w<=0 || h<=0)return frame;
    const int count=std::clamp(state.count,0,16);
    for(int i=0;i<count;++i){
        const auto&touch=state.touches[i];
        if(touch.attributes&HidTouchAttribute_End)continue;
        // Sensor coordinates are always 1280x720 in handheld mode; SDL handles
        // any aspect-fit letterboxing when converting window to logical UI.
        const int x=static_cast<int>(touch.x*static_cast<float>(w)/kTouchWidth);
        const int y=static_cast<int>(touch.y*static_cast<float>(h)/kTouchHeight);
        float logicalX=0,logicalY=0;
        SDL_RenderWindowToLogical(renderer,x,y,&logicalX,&logicalY);
        if(logicalX<0 || logicalY<0 || logicalX>=1920 || logicalY>=1080)continue;
        frame.points[frame.count++]={touch.finger_id,logicalX,logicalY};
    }
    return frame;
}

void StreamTouchControls::clear(){
    captures_={};
    pad_={};
    pressed_={};
    leftStickActive_=false;
    rightStickActive_=false;
    exitRequested_=false;
}

void StreamTouchControls::update(const SwitchTouchFrame&frame){
    // Keep each finger attached to its initial control, even while dragging
    // a virtual stick through another button. Release absent fingers at once.
    for(auto&capture:captures_){
        if(!capture.active)continue;
        bool present=false;
        for(int i=0;i<frame.count;++i)
            if(frame.points[i].id==capture.id){present=true;break;}
        if(!present)capture.active=false;
    }
    for(int i=0;i<frame.count;++i){
        const auto&touch=frame.points[i];
        bool found=false;
        for(const auto&capture:captures_)
            if(capture.active && capture.id==touch.id){found=true;break;}
        if(found)continue;
        for(auto&capture:captures_)if(!capture.active){
            capture={true,touch.id,findControl(touch.x,touch.y),touch.x,touch.y};
            if(capture.control==StreamTouchControl::Exit)exitRequested_=true;
            break;
        }
    }
    pad_={};pressed_={};
    leftStickActive_=false;rightStickActive_=false;
    for(const auto&capture:captures_){
        if(!capture.active)continue;
        const SwitchTouchPoint*point=nullptr;
        for(int i=0;i<frame.count;++i)
            if(frame.points[i].id==capture.id){point=&frame.points[i];break;}
        if(!point)continue;
        pressed_[static_cast<unsigned>(capture.control)]=true;
        if(capture.control==StreamTouchControl::LeftStick){
            pad_.lx=virtualAxis(point->x-capture.startX);
            pad_.ly=virtualAxis(point->y-capture.startY);
            leftStickActive_=true;
        }else if(capture.control==StreamTouchControl::RightStick){
            pad_.rx=virtualAxis(point->x-capture.startX);
            pad_.ry=virtualAxis(point->y-capture.startY);
            rightStickActive_=true;
        }else addButton(pad_,capture.control);
    }
}

bool StreamTouchControls::pressed(StreamTouchControl control)const{
    return pressed_[static_cast<unsigned>(control)];
}

bool StreamTouchControls::takeExitRequest(){
    const bool wasRequested=exitRequested_;
    exitRequested_=false;
    return wasRequested;
}

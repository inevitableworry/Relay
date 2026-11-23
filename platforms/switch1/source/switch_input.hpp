// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
#pragma once

#include "pbs_controller.hpp"
#include <SDL2/SDL.h>
#include <array>
#include <switch.h>

// Native libnx input for the Relay menu and PBS stream. SDL handles rendering,
// but HID owns controller/touch polling so controls also work without SDL
// game-controller mappings in Switch homebrew environments.
struct SwitchControllerFrame {
    bool connected=false;
    HidNpadIdType source=HidNpadIdType_No1;
    u32 style=0;
    u64 held=0;
    u64 pressed=0;  // Includes repeated D-pad / left-stick UI navigation.
    HidAnalogStickState left{};
    HidAnalogStickState right{};
};

struct SwitchTouchPoint {
    u32 id=0;
    float x=0;
    float y=0;
};

struct SwitchTouchFrame {
    std::array<SwitchTouchPoint,16> points{};
    int count=0;
};

class SwitchInput {
public:
    SwitchInput();
    SwitchControllerFrame poll();
    SwitchTouchFrame pollTouch(SDL_Renderer*renderer,SDL_Window*window) const;

private:
    std::array<PadState,9> pads_{}; // No1..No8 and Handheld, independently.
    int active_=-1;
    u64 previousButtons_=0;
    u64 previousDirections_=0;
    u32 nextRepeatMs_=0;
};

enum class StreamTouchControl {
    None,Exit,LeftStick,RightStick,Up,Down,Left,Right,
    Cross,Circle,Square,Triangle,L1,R1,L2,R2,Share,Options
};

// Multi-finger virtual control surface for streamed games; contains only
// input state and hit testing, leaving all PS Portal presentation in Scene.
class StreamTouchControls {
public:
    void update(const SwitchTouchFrame&frame);
    void clear();
    const RelayPadState& pad() const{return pad_;}
    bool leftStickActive() const{return leftStickActive_;}
    bool rightStickActive() const{return rightStickActive_;}
    bool pressed(StreamTouchControl control) const;
    bool takeExitRequest();

private:
    struct Capture {
        bool active=false;
        u32 id=0;
        StreamTouchControl control=StreamTouchControl::None;
        float startX=0,startY=0;
    };
    std::array<Capture,16> captures_{};
    RelayPadState pad_{};
    bool leftStickActive_=false;
    bool rightStickActive_=false;
    bool exitRequested_=false;
    std::array<bool,18> pressed_{};
};

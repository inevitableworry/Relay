// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
#pragma once

#include "pbs_probe.hpp"
#include "pbs_controller.hpp"
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <SDL2/SDL.h>

// Independent adaptation of Relay's own PbsProtocol and PbsStreamSession
// *media* pipeline. No other PS Remote Play app's transport/decoder is used.
// The caller must explicitly confirm PS5 capture recovery before start().
// This session does not change the encoder profile or take controller ownership.
class RelayPbsMedia {
public:
    RelayPbsMedia() = default;
    ~RelayPbsMedia();
    RelayPbsMedia(const RelayPbsMedia&) = delete;
    RelayPbsMedia& operator=(const RelayPbsMedia&) = delete;

    bool start(const std::string& host, uint16_t port, const RelayPbsProbe& config,
               bool explicitRecoveryConfirmation);
    void stop();
    bool running() const {return active_.load();}
    uint64_t decodedFrames() const {return decodedFrames_.load();}
    void setControllerState(const RelayPadState&state);
    // Only call on the UI/SDL main thread. Copies a single newest frame.
    bool takeVideoFrame(std::vector<uint8_t>&rgba, int&width, int&height);
    std::string status() const;

private:
    void videoThreadMain();
    void audioThreadMain();
    void inputThreadMain();
    void setStatus(const std::string&status);
    std::atomic<bool> active_{false};
    std::atomic<uint64_t> decodedFrames_{0};
    std::thread videoThread_;
    std::thread audioThread_;
    std::thread inputThread_;
    RelayPbsController controller_;
    RelayPadState input_;
    std::string host_;
    uint16_t port_=0;
    RelayPbsProbe config_;
    mutable std::mutex mutex_;
    std::string status_="Media idle";
    std::vector<uint8_t> latestFrame_;
    int frameWidth_=0, frameHeight_=0;
    bool framePending_=false;
    SDL_AudioDeviceID audioDevice_=0;
};

#pragma once
#include <string>

// Independent native probe of Relay's own PS5 PBS /config.json endpoint.
// This diagnostic does not acquire a controller, reconfigure video or inject
// an ELF; those operations are intentionally excluded until the complete
// game-stream ownership/recovery state machine has been ported.
struct RelayPbsProbe {
    bool reachable = false;
    bool compatible = false;
    bool streamReady = false;
    int width = 0;
    int height = 0;
    int fps = 0;
    std::string codec;
    std::string sps;
    std::string pps;
    std::string audioCodec;
    int audioRate = 0;
    int audioChannels = 0;
    int audioBits = 0;
    std::string status;
};

RelayPbsProbe probeRelayPbs(const char*ipv4, unsigned short port);

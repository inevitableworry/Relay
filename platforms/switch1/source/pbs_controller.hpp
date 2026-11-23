// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
#pragma once
#include <cstdint>
#include <mutex>
#include <string>

// Native Relay PbsProtocol.PadState; field order and bit masks match the
// existing Android app and PS5 PBS protocol. Sequence numbers are uint32.
struct RelayPadState {
    uint32_t buttons=0;
    uint8_t lx=128,ly=128,rx=128,ry=128,l2=0,r2=0;
    std::string encode(uint32_t sequence) const;
};

class RelayPbsController {
public:
    RelayPbsController()=default;
    ~RelayPbsController();
    RelayPbsController(const RelayPbsController&)=delete;
    RelayPbsController& operator=(const RelayPbsController&)=delete;

    // Connects once and never retries an uncertain ownership-creating POST.
    bool acquire(const std::string&host,uint16_t port);
    bool send(const RelayPadState&pad);
    bool release();
    bool owns() const {return !token_.empty();}
    bool uncertain() const {return ownershipUncertain_;}
    const std::string& error() const {return lastError_;}
    uint32_t allowedButtons()const{return allowedButtons_;}

private:
    bool request(const char*method,const char*path,const std::string&body,
                 std::string&reply,long&code,bool isControllerRequest);
    std::string host_;
    uint16_t port_=0;
    std::string token_;
    uint32_t sequence_=1;
    uint32_t allowedButtons_=0;
    bool ownershipUncertain_=false;
    std::string lastError_;
};

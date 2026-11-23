// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
#include "pbs_controller.hpp"
#include <curl/curl.h>
#include <algorithm>
#include <cctype>
#include <cstdio>

namespace {
constexpr uint32_t LEGACY_BUTTON_MASK=0x0013fffe;
constexpr uint32_t NATIVE_BUTTON_MASK=0x0011ffff;
size_t collectResponse(char*buffer,size_t n,size_t count,void*opaque){
    auto&destination=*static_cast<std::string*>(opaque);
    size_t bytes=n*count;
    if(bytes>8192 || destination.size()+bytes>8192)return 0;
    destination.append(buffer,bytes);return bytes;
}
size_t locateField(const std::string&data,const char*name){
    const std::string pattern="\""+std::string(name)+"\"";
    auto key=data.find(pattern);
    if(key==std::string::npos)return key;
    auto colon=data.find(':',key+pattern.size());
    if(colon==std::string::npos)return colon;
    return data.find_first_not_of(" \t\r\n",colon+1);
}
bool boolField(const std::string&data,const char*name,bool fallback){
    auto where=locateField(data,name);
    if(where==std::string::npos)return fallback;
    if(data.compare(where,4,"true")==0)return true;
    if(data.compare(where,5,"false")==0)return false;
    return fallback;
}
uint32_t uintField(const std::string&data,const char*name,uint32_t fallback){
    auto where=locateField(data,name);
    if(where==std::string::npos || !std::isdigit(static_cast<unsigned char>(data[where])))
        return fallback;
    uint64_t value=0;
    for(size_t i=where;i<data.size() && i<where+10 &&
            std::isdigit(static_cast<unsigned char>(data[i]));i++){
        value=value*10+(data[i]-'0');
        if(value>UINT32_MAX)return fallback;
    }
    return static_cast<uint32_t>(value);
}
std::string stringField(const std::string&data,const char*name){
    auto where=locateField(data,name);
    if(where==std::string::npos || data[where]!='"')return {};
    auto end=data.find('"',where+1);
    if(end==std::string::npos || end-where-1>256)return {};
    return data.substr(where+1,end-where-1);
}
bool validToken(const std::string&token){
    if(token.size()!=32)return false;
    return std::all_of(token.begin(),token.end(),[](unsigned char ch){
        return std::isxdigit(ch)!=0;
    });
}
}

std::string RelayPadState::encode(uint32_t sequence)const{
    return std::to_string(sequence)+","+std::to_string(buttons)+","+
           std::to_string(lx)+","+std::to_string(ly)+","+
           std::to_string(rx)+","+std::to_string(ry)+","+
           std::to_string(l2)+","+std::to_string(r2);
}
RelayPbsController::~RelayPbsController(){release();}
bool RelayPbsController::request(const char*method,const char*path,
    const std::string&body,std::string&reply,long&code,bool controllerRequest){
    if(host_.empty() || port_==0)return false;
    CURL*curl=curl_easy_init();
    if(!curl)return false;
    std::string url="http://"+host_+":"+std::to_string(port_)+path;
    curl_easy_setopt(curl,CURLOPT_URL,url.c_str());
    curl_easy_setopt(curl,CURLOPT_NOSIGNAL,1L);
    curl_easy_setopt(curl,CURLOPT_CONNECTTIMEOUT_MS,2500L);
    curl_easy_setopt(curl,CURLOPT_TIMEOUT_MS,3500L);
    curl_easy_setopt(curl,CURLOPT_FOLLOWLOCATION,0L);
    curl_easy_setopt(curl,CURLOPT_WRITEFUNCTION,collectResponse);
    curl_easy_setopt(curl,CURLOPT_WRITEDATA,&reply);
    curl_easy_setopt(curl,CURLOPT_HTTP_VERSION,CURL_HTTP_VERSION_1_1);
    struct curl_slist*headers=nullptr;
    headers=curl_slist_append(headers,"Accept: application/json");
    headers=curl_slist_append(headers,"Connection: close");
    if(controllerRequest){
        headers=curl_slist_append(headers,"X-PBS-Input: 1");
        if(!token_.empty()){
            std::string value="X-PBS-Controller: "+token_;
            headers=curl_slist_append(headers,value.c_str());
        }
    }
    if(std::string(method)=="POST"){
        curl_easy_setopt(curl,CURLOPT_POST,1L);
        curl_easy_setopt(curl,CURLOPT_POSTFIELDS,body.c_str());
        curl_easy_setopt(curl,CURLOPT_POSTFIELDSIZE,static_cast<long>(body.size()));
        headers=curl_slist_append(headers,"Content-Type: text/plain; charset=us-ascii");
    }
    curl_easy_setopt(curl,CURLOPT_HTTPHEADER,headers);
    CURLcode rc=curl_easy_perform(curl);
    curl_easy_getinfo(curl,CURLINFO_RESPONSE_CODE,&code);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    return rc==CURLE_OK && code>=200 && code<300;
}
bool RelayPbsController::acquire(const std::string&host,uint16_t port){
    if(ownershipUncertain_ || !token_.empty()){
        lastError_="Earlier PBS input ownership is unresolved";return false;
    }
    host_=host;port_=port;sequence_=1;
    std::string reply;long code=0;
    if(!request("GET","/input/status","",reply,code,false)){
        lastError_="Cannot read PS5 controller status";return false;
    }
    if(!boolField(reply,"supported",boolField(reply,"available",false)) ||
        boolField(reply,"cleanup_required",false)){
        lastError_="PS5 controller service unavailable or requires cleanup";return false;
    }
    if(boolField(reply,"connected",false)){
        lastError_="A PS5 input session is already connected";return false;
    }
    allowedButtons_=uintField(reply,"buttons_mask",LEGACY_BUTTON_MASK)&NATIVE_BUTTON_MASK;
    if(uintField(reply,"create_mask",0)!=1)allowedButtons_&=~uint32_t(1);
    if(!allowedButtons_){lastError_="PS5 advertised no controller buttons";return false;}
    reply.clear();code=0;
    // A lost HTTP response can still mean PS5 ownership was acquired.
    // Never retry this POST automatically after an ambiguous failure.
    ownershipUncertain_=true;
    if(!request("POST","/input/connect","",reply,code,true)){
        if(code>=400 && code<500)ownershipUncertain_=false;
        lastError_="PS5 controller admission was not confirmed";
        return false;
    }
    std::string issued=stringField(reply,"token");
    if(!validToken(issued)){
        lastError_="PS5 returned an invalid controller ownership token";return false;
    }
    token_=issued;
    ownershipUncertain_=false;
    return true;
}
bool RelayPbsController::send(const RelayPadState&pad){
    if(token_.empty())return false;
    RelayPadState filtered=pad;
    filtered.buttons&=allowedButtons_;
    std::string reply;long code=0;
    if(!request("POST","/input/state",filtered.encode(sequence_++),reply,code,true)){
        lastError_="PS5 controller state transmission failed";return false;
    }
    return true;
}
bool RelayPbsController::release(){
    if(token_.empty())return !ownershipUncertain_;
    std::string response;long code=0;
    // Send a neutral state and release only our own retained token.
    request("POST","/input/state",RelayPadState{}.encode(sequence_++),response,code,true);
    response.clear();code=0;
    bool released=request("POST","/input/disconnect","",response,code,true);
    if(released){token_.clear();ownershipUncertain_=false;return true;}
    // HTTP response may be lost after release; do not discard the token and
    // silently acquire a second one. Report ownership as uncertain.
    ownershipUncertain_=true;
    lastError_="PS5 input release could not be verified";
    return false;
}

#include "pbs_probe.hpp"
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <string>
#include <algorithm>

namespace {
std::string stringValue(const std::string&json,const char*key){
    const std::string pattern="\""+std::string(key)+"\"";
    const auto pos=json.find(pattern);
    if(pos==std::string::npos)return {};
    const auto colon=json.find(':',pos+pattern.size());
    if(colon==std::string::npos)return {};
    auto quote=json.find('"',colon+1);
    if(quote==std::string::npos)return {};
    auto end=json.find('"',quote+1);
    if(end==std::string::npos)return {};
    return json.substr(quote+1,std::min<size_t>(end-quote-1,4096));
}
int intValue(const std::string&json,const char*key){
    const std::string pattern="\""+std::string(key)+"\"";
    const auto pos=json.find(pattern);
    if(pos==std::string::npos)return 0;
    const auto colon=json.find(':',pos+pattern.size());
    if(colon==std::string::npos)return 0;
    const char*begin=json.c_str()+colon+1;
    char*end=nullptr;
    long value=std::strtol(begin,&end,10);
    if(end==begin || value<0 || value>10000)return 0;
    return static_cast<int>(value);
}
}

RelayPbsProbe probeRelayPbs(const char*ipv4,unsigned short port){
    RelayPbsProbe result;
    if(!ipv4 || port==0){result.status="Invalid PS5 address";return result;}
    int fd=::socket(AF_INET,SOCK_STREAM,0);
    if(fd<0){result.status="Switch network socket unavailable";return result;}
    timeval timeout{};timeout.tv_sec=2;
    ::setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    ::setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    sockaddr_in address{};
    address.sin_family=AF_INET;
    address.sin_port=htons(port);
    if(::inet_pton(AF_INET,ipv4,&address.sin_addr)!=1){
        result.status="PS5 address must be IPv4";::close(fd);return result;
    }
    if(::connect(fd,reinterpret_cast<sockaddr*>(&address),sizeof(address))!=0){
        result.status="PS5 PBS streaming service is not reachable";
        ::close(fd);return result;
    }
    result.reachable=true;
    const char*request="GET /config.json HTTP/1.1\r\nHost: ps5\r\nAccept: application/json\r\nConnection: close\r\n\r\n";
    size_t sent=0,sz=std::strlen(request);
    while(sent<sz){
        int n=::send(fd,request+sent,sz-sent,0);
        if(n<=0){result.status="Could not read PS5 capture configuration";::close(fd);return result;}
        sent+=static_cast<size_t>(n);
    }
    std::string data;
    char buf[1024];
    for(int n;data.size()<16384 && (n=::recv(fd,buf,sizeof(buf),0))>0;)
        data.append(buf,static_cast<size_t>(n));
    ::close(fd);
    if(data.rfind("HTTP/1.1 200",0)!=0 && data.rfind("HTTP/1.0 200",0)!=0){
        result.status="PBS service returned an HTTP error";return result;
    }
    const auto body=data.find("\r\n\r\n");
    if(body==std::string::npos){result.status="Incomplete PS5 capture response";return result;}
    const auto json=data.substr(body+4);
    result.compatible=(stringValue(json,"platform")=="PS5");
    result.width=intValue(json,"width");
    result.height=intValue(json,"height");
    result.fps=intValue(json,"fps");
    result.codec=stringValue(json,"codec");
    result.sps=stringValue(json,"sps");
    result.pps=stringValue(json,"pps");
    if(auto audio=json.find("\"audio\""); audio!=std::string::npos){
        const auto audioBody=json.substr(audio);
        result.audioCodec=stringValue(audioBody,"codec");
        result.audioRate=intValue(audioBody,"rate");
        result.audioChannels=intValue(audioBody,"channels");
        result.audioBits=intValue(audioBody,"bits");
    }
    result.streamReady=result.compatible && result.width>0 && result.height>0 &&
        result.width<=8192 && result.height<=4320 && !stringValue(json,"sps").empty() &&
        !stringValue(json,"pps").empty() && result.codec.rfind("avc1.",0)==0;
    result.status=!result.compatible?"Endpoint is not Relay's PS5 PBS service":
        !result.streamReady?"PS5 capture is not ready yet":"PS5 capture is available";
    return result;
}

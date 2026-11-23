// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
#include "pbs_media.hpp"
#include <curl/curl.h>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}
#include <algorithm>
#include <array>
#include <cstring>
#include <functional>
#include <memory>
#include <chrono>

namespace {
constexpr size_t MAX_VIDEO_PACKET=16*1024*1024;
constexpr size_t MAX_AUDIO_PACKET=65536;
uint32_t be32(const uint8_t*x){
    return (uint32_t(x[0])<<24)|(uint32_t(x[1])<<16)|(uint32_t(x[2])<<8)|uint32_t(x[3]);
}
using PacketCallback=std::function<bool(uint32_t,const uint8_t*,size_t)>;
// Compatible with PbsProtocol.readPacket(): big-endian 32-bit length, PTS90K.
struct PacketAssembler {
    std::vector<uint8_t> bytes;
    size_t limit;
    PacketCallback callback;
    bool failed=false;
    PacketAssembler(size_t max,PacketCallback fn):limit(max),callback(std::move(fn)){}
    bool add(const uint8_t*data,size_t size){
        if(failed || size>limit+65536 || bytes.size()+size>limit+65536){failed=true;return false;}
        bytes.insert(bytes.end(),data,data+size);
        size_t cursor=0;
        while(bytes.size()-cursor>=8){
            auto const*p=bytes.data()+cursor;
            uint32_t length=be32(p),pts=be32(p+4);
            if(length==0 || length>limit){failed=true;return false;}
            if(bytes.size()-cursor-8<length)break;
            if(!callback(pts,p+8,length)){failed=true;return false;}
            cursor+=8+length;
        }
        if(cursor)bytes.erase(bytes.begin(),bytes.begin()+cursor);
        return true;
    }
};
struct Download {
    std::atomic<bool>*active=nullptr;
    PacketAssembler*assembler=nullptr;
};
size_t onMediaData(char*ptr,size_t size,size_t count,void*opaque){
    auto& state=*static_cast<Download*>(opaque);
    const size_t total=size*count;
    if(!state.active->load() || !state.assembler->add(reinterpret_cast<const uint8_t*>(ptr),total))
        return 0;
    return total;
}
bool downloadStream(const std::string&url,Download&state){
    CURL*curl=curl_easy_init();
    if(!curl)return false;
    curl_easy_setopt(curl,CURLOPT_URL,url.c_str());
    curl_easy_setopt(curl,CURLOPT_NOSIGNAL,1L);
    curl_easy_setopt(curl,CURLOPT_CONNECTTIMEOUT_MS,3000L);
    curl_easy_setopt(curl,CURLOPT_FOLLOWLOCATION,0L);
    curl_easy_setopt(curl,CURLOPT_LOW_SPEED_LIMIT,1L);
    curl_easy_setopt(curl,CURLOPT_LOW_SPEED_TIME,7L);
    curl_easy_setopt(curl,CURLOPT_WRITEFUNCTION,onMediaData);
    curl_easy_setopt(curl,CURLOPT_WRITEDATA,&state);
    auto rc=curl_easy_perform(curl);
    long status=0;
    curl_easy_getinfo(curl,CURLINFO_RESPONSE_CODE,&status);
    curl_easy_cleanup(curl);
    return (rc==CURLE_OK && status==200);
}
int hexNibble(char c){
    if(c>='0' && c<='9')return c-'0';
    if(c>='a' && c<='f')return c-'a'+10;
    if(c>='A' && c<='F')return c-'A'+10;
    return -1;
}
bool nalFromHex(std::vector<uint8_t>&out,const std::string&hex,uint8_t expectedType){
    if(hex.size()<2 || hex.size()>4096 || hex.size()%2)return false;
    std::vector<uint8_t> nal{0,0,0,1};
    for(size_t i=0;i<hex.size();i+=2){
        int a=hexNibble(hex[i]),b=hexNibble(hex[i+1]);
        if(a<0 || b<0)return false;
        nal.push_back(uint8_t((a<<4)|b));
    }
    if((nal[4]&31)!=expectedType)return false;
    out.insert(out.end(),nal.begin(),nal.end());
    return true;
}
AVPixelFormat selectFormat(AVCodecContext*,const AVPixelFormat*fmts){
    for(const AVPixelFormat*p=fmts;*p!=AV_PIX_FMT_NONE;p++)
        if(*p==AV_PIX_FMT_NVTEGRA)return *p;
    for(const AVPixelFormat*p=fmts;*p!=AV_PIX_FMT_NONE;p++)
        if(*p==AV_PIX_FMT_YUV420P)return *p;
    return fmts[0];
}
std::string url(const std::string&host,uint16_t port,const char*path){
    return "http://"+host+":"+std::to_string(port)+path;
}
}

RelayPbsMedia::~RelayPbsMedia(){stop();}
void RelayPbsMedia::setStatus(const std::string&value){
    std::lock_guard<std::mutex>lock(mutex_);status_=value;
}
std::string RelayPbsMedia::status()const{
    std::lock_guard<std::mutex>lock(mutex_);return status_;
}
bool RelayPbsMedia::start(const std::string&host,uint16_t port,
                           const RelayPbsProbe&config,bool confirmation){
    // A previous failed 2160p capture on the user's PS5 is quarantined.
    // Never bypass it through a new client. Only the explicit recovery flow
    // on the console/tablet should clear it; the Switch does not override it.
    if(!confirmation){setStatus("PS5 reboot/jailbreak confirmation required");return false;}
    if(active_ || !config.streamReady || config.width>1920 || config.height>1080 ||
       config.width<160 || config.height<90 || config.codec.rfind("avc1.",0)!=0){
        setStatus("Only a ready H.264 stream up to 1080p is allowed");return false;
    }
    static std::once_flag curlInit;
    std::call_once(curlInit,[]{curl_global_init(CURL_GLOBAL_DEFAULT);});
    if(!controller_.acquire(host,port)){
        setStatus(controller_.error());
        return false;
    }
    host_=host;port_=port;config_=config;
    decodedFrames_=0;
    active_=true;setStatus("Connecting native H.264 decoder...");
    videoThread_=std::thread(&RelayPbsMedia::videoThreadMain,this);
    inputThread_=std::thread(&RelayPbsMedia::inputThreadMain,this);
    if(config.audioCodec=="pcm_f32le" && config.audioBits==32 &&
       config.audioChannels>=1 && config.audioChannels<=2 &&
       config.audioRate>=8000 && config.audioRate<=192000){
        SDL_AudioSpec desired{};
        desired.freq=config.audioRate;
        desired.format=AUDIO_F32LSB;
        desired.channels=static_cast<uint8_t>(config.audioChannels);
        desired.samples=1024;
        audioDevice_=SDL_OpenAudioDevice(nullptr,0,&desired,nullptr,0);
        if(audioDevice_){SDL_PauseAudioDevice(audioDevice_,0);
            audioThread_=std::thread(&RelayPbsMedia::audioThreadMain,this);}
    }
    return true;
}
void RelayPbsMedia::stop(){
    active_=false;
    if(videoThread_.joinable())videoThread_.join();
    if(audioThread_.joinable())audioThread_.join();
    if(inputThread_.joinable())inputThread_.join();
    if(audioDevice_){SDL_ClearQueuedAudio(audioDevice_);
        SDL_CloseAudioDevice(audioDevice_);audioDevice_=0;}
    const bool released=controller_.release();
    std::lock_guard<std::mutex>lock(mutex_);
    latestFrame_.clear();framePending_=false;status_="Media stopped";
    if(!released)status_="PS5 input cleanup unverified: do not reconnect";
}
void RelayPbsMedia::setControllerState(const RelayPadState&state){
    std::lock_guard<std::mutex>lock(mutex_);input_=state;
}
void RelayPbsMedia::inputThreadMain(){
    while(active_){
        RelayPadState current;
        if(decodedFrames_.load()>0){
            std::lock_guard<std::mutex>lock(mutex_);
            current=input_;
        }
        // No gameplay inputs until the first decoded frame is displayed.
        if(!controller_.send(current)){
            setStatus(controller_.error());
            active_=false;
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(decodedFrames_.load()?32:100));
    }
}
bool RelayPbsMedia::takeVideoFrame(std::vector<uint8_t>&rgba,int&width,int&height){
    std::lock_guard<std::mutex>lock(mutex_);
    if(!framePending_)return false;
    rgba.swap(latestFrame_);width=frameWidth_;height=frameHeight_;
    framePending_=false;return true;
}
void RelayPbsMedia::videoThreadMain(){
    const AVCodec*codec=avcodec_find_decoder(AV_CODEC_ID_H264);
    if(!codec){setStatus("FFmpeg H.264 decoder unavailable");active_=false;return;}
    AVCodecContext*ctx=avcodec_alloc_context3(codec);
    if(!ctx){setStatus("Cannot allocate H.264 decoder");active_=false;return;}
    ctx->flags |= AV_CODEC_FLAG_LOW_DELAY;
    ctx->thread_count=2;
    AVBufferRef*hwDevice=nullptr;
    if(av_hwdevice_ctx_create(&hwDevice,AV_HWDEVICE_TYPE_NVTEGRA,nullptr,nullptr,0)==0){
        ctx->hw_device_ctx=av_buffer_ref(hwDevice);
        ctx->get_format=selectFormat;
    }
    av_buffer_unref(&hwDevice);
    if(avcodec_open2(ctx,codec,nullptr)<0){
        setStatus("Cannot open H.264 decoder");avcodec_free_context(&ctx);active_=false;return;
    }
    AVFrame*frame=av_frame_alloc(),*download=av_frame_alloc();
    AVPacket*packet=av_packet_alloc();
    SwsContext*scale=nullptr;
    if(!frame || !download || !packet){
        setStatus("Cannot allocate video frame buffers");active_=false;
    }else{
        std::vector<uint8_t>headers;
        if(!nalFromHex(headers,config_.sps,7)||!nalFromHex(headers,config_.pps,8)){
            setStatus("Invalid PS5 H.264 SPS/PPS headers");active_=false;
        }else {
            av_new_packet(packet,static_cast<int>(headers.size()));
            std::memcpy(packet->data,headers.data(),headers.size());
            avcodec_send_packet(ctx,packet);
            av_packet_unref(packet);
        }
    }
    auto decode=[&](uint32_t pts,const uint8_t*data,size_t size)->bool{
        if(!active_)return false;
        // Relay's PS5 PBS sends Annex-B access units. Normalize only if the
        // packet contains a single bare NAL (no start-code prefix).
        const bool annexB=size>=4 && data[0]==0 && data[1]==0 &&
            ((data[2]==0 && data[3]==1)||data[2]==1);
        const size_t prefix=annexB?0:4;
        if(size+prefix>MAX_VIDEO_PACKET)return false;
        if(av_new_packet(packet,static_cast<int>(size+prefix))<0)return false;
        if(prefix){packet->data[0]=0;packet->data[1]=0;packet->data[2]=0;packet->data[3]=1;}
        std::memcpy(packet->data+prefix,data,size);
        packet->pts=pts;
        int rc=avcodec_send_packet(ctx,packet);
        av_packet_unref(packet);
        if(rc<0 && rc!=AVERROR(EAGAIN))return true;
        while(active_ && avcodec_receive_frame(ctx,frame)==0){
            AVFrame*output=frame;
            if(frame->format==AV_PIX_FMT_NVTEGRA){
                av_frame_unref(download);
                if(av_hwframe_transfer_data(download,frame,0)<0){
                    av_frame_unref(frame);continue;
                }
                output=download;
            }
            int w=output->width,h=output->height;
            if(w>0 && w<=1920 && h>0 && h<=1080){
                scale=sws_getCachedContext(scale,w,h,static_cast<AVPixelFormat>(output->format),
                                           w,h,AV_PIX_FMT_RGBA,SWS_FAST_BILINEAR,nullptr,nullptr,nullptr);
                if(scale){
                    std::vector<uint8_t>rgba(static_cast<size_t>(w)*h*4);
                    uint8_t*planes[]={rgba.data()};int strides[]={w*4};
                    if(sws_scale(scale,output->data,output->linesize,0,h,planes,strides)>0){
                        std::lock_guard<std::mutex>lock(mutex_);
                        latestFrame_.swap(rgba);
                        frameWidth_=w;frameHeight_=h;framePending_=true;
                        ++decodedFrames_;
                        status_="PS5 H.264 video decoded";
                    }
                }
            }
            av_frame_unref(frame);
        }
        return true;
    };
    if(active_){
        PacketAssembler parser(MAX_VIDEO_PACKET,decode);
        Download transfer{&active_,&parser};
        bool ok=downloadStream(url(host_,port_,"/frames"),transfer);
        if(active_)setStatus(ok?"PS5 video stream ended":"PS5 video stream failed or stalled");
    }
    active_=false;
    sws_freeContext(scale);
    av_frame_free(&frame);av_frame_free(&download);av_packet_free(&packet);
    avcodec_free_context(&ctx);
}
void RelayPbsMedia::audioThreadMain(){
    if(!audioDevice_)return;
    auto emit=[&](uint32_t,const uint8_t*data,size_t bytes)->bool{
        if(!active_)return false;
        if(bytes%(config_.audioChannels*4)!=0)return false;
        // The Android source uses float32 LE. The Switch's native SDL audio
        // device has the exact same sample format; no lossy resampling.
        if(SDL_GetQueuedAudioSize(audioDevice_)>
           static_cast<Uint32>(config_.audioRate*config_.audioChannels*4/4))
            SDL_ClearQueuedAudio(audioDevice_);
        return SDL_QueueAudio(audioDevice_,data,static_cast<Uint32>(bytes))==0;
    };
    PacketAssembler parser(MAX_AUDIO_PACKET,emit);
    Download transfer{&active_,&parser};
    downloadStream(url(host_,port_,"/audio"),transfer);
}

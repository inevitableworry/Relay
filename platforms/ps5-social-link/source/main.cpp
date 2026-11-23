// SPDX-License-Identifier: GPL-3.0-or-later
// Relay Social Link: independent PS5-native UI and consent-based pairing.
// No PSN passwords, no game capture, no unauthorized profile enumeration.
#define SDL_MAIN_HANDLED
#include <SDL.h>
#include <SDL_ttf.h>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include "qrcodegen.hpp"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>

using json = nlohmann::json;
using namespace std::chrono;

#if defined(RELAY_PS5)
extern "C" int sceUserServiceGetForegroundUser(int* userId);
extern "C" int sceUserServiceGetUserName(int userId, char* name, size_t bytes);
#endif

namespace {
constexpr char kHost[] = "https://social.example.invalid"; // Configure a valid approved host for deployment.
constexpr char kLocalRoot[] = "/data/relay-social-link";
constexpr char kDefaultAssets[] = "/data/relay-social-link/assets";
constexpr int W=1920, H=1080;
constexpr int kUnlinked=-1;

struct User {
    int id=kUnlinked;
    std::string name;
    bool trusted=false;
};
User foregroundUser() {
#if defined(RELAY_PS5)
    int id=-1;
    if(sceUserServiceGetForegroundUser(&id)!=0||id<0)
        return {-1,"No active PS5 profile",false};
    char name[128]{};
    const int result=sceUserServiceGetUserName(id,name,sizeof(name));
    if(result!=0||!name[0])
        return {id,"Profile name unavailable",false};
    // Trim control characters before displaying or using a URL/JSON field.
    std::string value{name};
    value.erase(std::remove_if(value.begin(),value.end(),[](unsigned char c){
        return c<32||c==127;
    }),value.end());
    if(value.size()>40)value.resize(40);
    return {id,value,!value.empty()};
#else
    // Desktop preview never impersonates a real PS5 or requests pairing.
    return {-1,"Desktop preview (no PS5 user-service)",false};
#endif
}

std::string assetRoot;
std::string tokenPath(int userId){
    return std::string(kLocalRoot)+"/profile-"+std::to_string(userId)+".json";
}
bool validToken(const std::string& token) {
    if(token.size()<40||token.size()>80)return false;
    return std::all_of(token.begin(),token.end(),[](char c){
        return(c>='A'&&c<='Z')||(c>='a'&&c<='z')||
              (c>='0'&&c<='9')||c=='-'||c=='_';
    });
}
std::string loadToken(int userId){
    if(userId<0)return {};
    FILE* file=fopen(tokenPath(userId).c_str(),"rb");
    if(!file)return {};
    char text[256]{};
    size_t n=fread(text,1,sizeof(text)-1,file);
    fclose(file);
    if(n==0||n>=sizeof(text)-1)return {};
    try {
        auto data=json::parse(text,text+n);
        std::string token=data.value("deviceToken",std::string{});
        return validToken(token)?token:std::string{};
    }catch(...){return {};}
}
bool saveToken(int userId,const std::string& token){
    if(userId<0||!validToken(token))return false;
    mkdir(kLocalRoot,0700);
    const std::string path=tokenPath(userId);
    int fd=open(path.c_str(),O_CREAT|O_TRUNC|O_WRONLY,0600);
    if(fd<0)return false;
    const std::string data=json({{"deviceToken",token}}).dump();
    const bool ok=write(fd,data.data(),data.size())==static_cast<ssize_t>(data.size());
    fsync(fd);
    close(fd);
    if(!ok)unlink(path.c_str());
    return ok;
}

struct HttpResponse {int status=0;std::string body;std::string error;};
size_t receive(char*ptr,size_t size,size_t count,void*user){
    auto* out=reinterpret_cast<std::string*>(user);
    const size_t n=size*count;
    if(out->size()+n>32768)return 0;
    out->append(ptr,n);
    return n;
}
HttpResponse request(const std::string& route,const json& data,
                     const std::string& bearer={},bool patch=false){
    HttpResponse result;
    if(route.rfind("/api/",0)!=0)return {0,"","Invalid route"};
    CURL* curl=curl_easy_init();
    if(!curl)return {0,"","TLS client unavailable"};
    const std::string url=std::string(kHost)+route;
    const std::string body=data.dump();
    const std::string ca=assetRoot+"/ca-bundle.pem";
    curl_slist* headers=nullptr;
    headers=curl_slist_append(headers,"Content-Type: application/json");
    headers=curl_slist_append(headers,"Accept: application/json");
    if(!bearer.empty()&&validToken(bearer))
        headers=curl_slist_append(headers,("Authorization: Bearer "+bearer).c_str());
    curl_easy_setopt(curl,CURLOPT_URL,url.c_str());
    curl_easy_setopt(curl,CURLOPT_HTTPHEADER,headers);
    curl_easy_setopt(curl,CURLOPT_POSTFIELDS,body.c_str());
    curl_easy_setopt(curl,CURLOPT_POSTFIELDSIZE,static_cast<long>(body.size()));
    if(patch)curl_easy_setopt(curl,CURLOPT_CUSTOMREQUEST,"PATCH");
    curl_easy_setopt(curl,CURLOPT_FOLLOWLOCATION,0L);
    curl_easy_setopt(curl,CURLOPT_CONNECTTIMEOUT,8L);
    curl_easy_setopt(curl,CURLOPT_TIMEOUT,14L);
    curl_easy_setopt(curl,CURLOPT_SSL_VERIFYPEER,1L);
    curl_easy_setopt(curl,CURLOPT_SSL_VERIFYHOST,2L);
    curl_easy_setopt(curl,CURLOPT_CAINFO,ca.c_str());
    curl_easy_setopt(curl,CURLOPT_WRITEFUNCTION,receive);
    curl_easy_setopt(curl,CURLOPT_WRITEDATA,&result.body);
    curl_easy_setopt(curl,CURLOPT_USERAGENT,"RelayPS5Link/0.1");
    const CURLcode code=curl_easy_perform(curl);
    if(code==CURLE_OK)curl_easy_getinfo(curl,CURLINFO_RESPONSE_CODE,&result.status);
    else result.error="Secure connection failed (TLS/network)";
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    return result;
}
std::string percent(const std::string& value){
    CURL* c=curl_easy_init();if(!c)return {};
    char* escaped=curl_easy_escape(c,value.c_str(),static_cast<int>(value.size()));
    std::string output=escaped?escaped:"";
    if(escaped)curl_free(escaped);
    curl_easy_cleanup(c);return output;
}

struct Shared {
    std::mutex mutex;
    std::atomic<bool> running{true};
    User active;
    std::string token,code,pollToken,message="Start with Link this PS5 or Create account";
    int selected=0;
    bool requestLink=false,linked=false,pending=false,showSignup=false;
    steady_clock::time_point expires{},nextPoll{},nextSync{};
};
void networkLoop(Shared& s) {
    curl_global_init(CURL_GLOBAL_DEFAULT);
    int lastUser=-9999;
    while(s.running.load()) {
        User user;
        bool start=false,needPoll=false,needSync=false;
        std::string token,pollToken;
        {
            std::lock_guard<std::mutex> guard(s.mutex);
            user=s.active;
            if(user.id!=lastUser) {
                lastUser=user.id;s.code.clear();s.pollToken.clear();s.pending=false;
                s.token=loadToken(user.id);s.linked=!s.token.empty();
                s.message=s.linked?"Linked profile. Checking console name sync..."
                    :"Select Link this PS5 to create a one-time code";
                s.nextSync=steady_clock::now();
            }
            start=s.requestLink&&user.trusted&&user.id>=0;
            if(start) s.requestLink=false;
            const auto t=steady_clock::now();
            needPoll=s.pending&&t>=s.nextPoll;
            needSync=s.linked&&user.trusted&&t>=s.nextSync;
            token=s.token;pollToken=s.pollToken;
        }
        if(start) {
            const HttpResponse result=request("/api/pairing/start",
                {{"name","PS5 "+user.name},{"platform","ps5"}});
            std::lock_guard<std::mutex> guard(s.mutex);
            if(user.id!=s.active.id)continue;
            if(result.status==201)try {
                auto data=json::parse(result.body);
                s.code=data.at("code").get<std::string>();
                s.pollToken=data.at("pollToken").get<std::string>();
                s.pending=true;s.expires=steady_clock::now()+minutes(10);
                s.nextPoll=steady_clock::now()+seconds(3);
                s.message="Open Relay Social on another device and approve the code";
            }catch(...){s.message="Server returned invalid linking data";}
            else s.message=result.error.empty()?"Link request failed (HTTP "+
                std::to_string(result.status)+")":result.error;
        }else if(needPoll&&!pollToken.empty()){
            const HttpResponse result=request("/api/pairing/status",
                {{"pollToken",pollToken}});
            std::lock_guard<std::mutex> guard(s.mutex);
            if(user.id!=s.active.id||pollToken!=s.pollToken)continue;
            s.nextPoll=steady_clock::now()+seconds(5);
            if(result.status==200)try {
                const auto data=json::parse(result.body);
                if(data.value("status",std::string{})=="paired") {
                    std::string received=data.at("deviceToken").get<std::string>();
                    if(saveToken(user.id,received)) {
                        s.token=received;s.linked=true;s.pending=false;s.code.clear();
                        s.pollToken.clear();s.message="Console linked. Syncing active PS5 profile...";
                        s.nextSync=steady_clock::now();
                    }else s.message="Could not store private device credential";
                }else if(steady_clock::now()>s.expires){
                    s.pending=false;s.message="Link code expired. Create another.";
                }
            }catch(...){s.message="Could not interpret link status";}
            else if(result.status==410||result.status==404){
                s.pending=false;s.message="Link code expired or rejected.";
            }
        }else if(needSync&&!token.empty()) {
            const HttpResponse result=request("/api/device/profile",
                {{"name",user.name}},token,true);
            std::lock_guard<std::mutex> guard(s.mutex);
            if(user.id!=s.active.id||token!=s.token)continue;
            s.nextSync=steady_clock::now()+seconds(60);
            if(result.status==200)s.message="Linked to Relay Social. Profile name synchronized.";
            else if(result.status==401){
                s.linked=false;s.token.clear();s.message="Link revoked. Link this PS5 again.";
            }else s.message=result.error.empty()?"Profile sync not available yet":result.error;
        }
        std::this_thread::sleep_for(milliseconds(250));
    }
    curl_global_cleanup();
}

SDL_Color rgb(int r,int g,int b,int a=255){
    return {static_cast<Uint8>(r),static_cast<Uint8>(g),
            static_cast<Uint8>(b),static_cast<Uint8>(a)};
}
void box(SDL_Renderer*r,int x,int y,int w,int h,SDL_Color c){
    SDL_SetRenderDrawBlendMode(r,SDL_BLENDMODE_BLEND);
    SDL_SetRenderDrawColor(r,c.r,c.g,c.b,c.a);
    SDL_Rect rect{x,y,w,h};SDL_RenderFillRect(r,&rect);
}
void label(SDL_Renderer*r,TTF_Font*font,const std::string& str,
           int x,int y,SDL_Color c,int limit=1200){
    if(!font||str.empty())return;
    SDL_Surface* surface=TTF_RenderUTF8_Blended(font,str.c_str(),c);
    if(!surface)return;
    SDL_Texture* texture=SDL_CreateTextureFromSurface(r,surface);
    if(texture) {
        const float scale=std::min(1.0f,limit/static_cast<float>(surface->w));
        SDL_Rect dst{x,y,static_cast<int>(surface->w*scale),
            static_cast<int>(surface->h*scale)};
        SDL_RenderCopy(r,texture,nullptr,&dst);
        SDL_DestroyTexture(texture);
    }
    SDL_FreeSurface(surface);
}
void qr(SDL_Renderer*r,const std::string& url,int left,int top,int dimension){
    try {
        const auto code=qrcodegen::QrCode::encodeText(url.c_str(),qrcodegen::QrCode::Ecc::MEDIUM);
        int modules=code.getSize();
        int scale=std::max(2,dimension/(modules+8));
        int offset=4*scale;
        box(r,left,top,(modules+8)*scale,(modules+8)*scale,rgb(255,255,255));
        for(int y=0;y<modules;++y)for(int x=0;x<modules;++x)if(code.getModule(x,y))
            box(r,left+offset+x*scale,top+offset+y*scale,scale,scale,rgb(12,30,60));
    }catch(...){}
}
void draw(SDL_Renderer*r,TTF_Font*title,TTF_Font*body,TTF_Font*small,Shared&s){
    User user;std::string code,message;
    bool pending,linked,showSignup;
    {
        std::lock_guard<std::mutex> guard(s.mutex);
        user=s.active;code=s.code;message=s.message;
        pending=s.pending;linked=s.linked;showSignup=s.showSignup;
    }
    SDL_SetRenderDrawColor(r,5,11,28,255);SDL_RenderClear(r);
    for(int i=0;i<32;++i) {
        int cx=1220,cy=470;
        float angle=(i*2.399963f)+static_cast<float>(SDL_GetTicks())*.00005f;
        int rad=80+(i*21)%310;
        int x=cx+static_cast<int>(rad*std::cos(angle));
        int y=cy+static_cast<int>(rad*std::sin(angle));
        box(r,x,y,3+(i%3),3+(i%3),rgb(40+i*2,98+i*2,232,65+i%4*35));
    }
    box(r,0,0,W,5,rgb(40,134,255));
    label(r,title,"RELAY  SOCIAL LINK",88,80,rgb(240,247,255),1120);
    label(r,small,"PS5 local profile and secure Relay linking",90,165,rgb(144,185,233));
    box(r,90,270,865,525,rgb(17,35,65,225));
    label(r,body,"Active PS5 profile",130,310,rgb(145,191,247));
    label(r,title,user.name,130,365,rgb(244,249,255),770);
    label(r,small,user.trusted?"Profile read from PS5 user service":
        "The active PS5 profile is not accessible",130,435,
        user.trusted?rgb(114,221,189):rgb(255,184,129),710);
    box(r,130,520,775,72,linked?rgb(21,100,100):
        pending?rgb(24,75,127):rgb(23,54,100));
    label(r,body,linked?"LINKED":pending?"AWAITING APPROVAL":
        "LINKING IS OPTIONAL",158,535,rgb(235,246,255));
    label(r,small,message,130,626,rgb(220,232,252),735);
    label(r,small,"X: Link this PS5     Triangle: Create account QR",130,735,rgb(194,212,238));
    label(r,small,"Circle: Close app     R1/L1: Change view",130,772,rgb(158,179,209));
    if(showSignup&&user.trusted) {
        const std::string url=std::string(kHost)+"/?signup=1&ps5ProfileName="+percent(user.name);
        label(r,body,"CREATE RELAY ACCOUNT",1070,248,rgb(236,246,255));
        qr(r,url,1130,328,420);
        label(r,small,"Scan to open secure registration.",1070,825,rgb(211,229,246));
        label(r,small,"Profile name prefilled; console linking optional.",1070,860,rgb(145,174,214));
    }else if(pending&&!code.empty()) {
        label(r,body,"LINK YOUR EXISTING ACCOUNT",1048,230,rgb(237,246,255));
        qr(r,std::string(kHost)+"/pair?code="+code,1130,318,425);
        label(r,title,code,1123,785,rgb(240,247,255),560);
        label(r,small,"Approve code from your signed-in Relay Social account.",1036,864,rgb(199,219,243),785);
    }else {
        label(r,body,linked?"RELAY SOCIAL CONNECTED":"LINK YOUR PS5",1115,282,rgb(222,240,255));
        label(r,small,linked?"Your local profile is synchronized every 60 seconds.":
            "Open Relay Social on your phone to approve a one-time code.",1045,354,rgb(178,203,240),800);
        label(r,small,"No PSN password is stored or requested.",1060,405,rgb(118,195,193));
    }
    label(r,small,"Relay is independent of Sony Interactive Entertainment.",88,1006,rgb(129,157,194),1550);
    SDL_RenderPresent(r);
}
}

int main(int argc,char** argv){
    assetRoot=kDefaultAssets;
    if(argc==3&&std::strcmp(argv[1],"--assets")==0)assetRoot=argv[2];
    if(SDL_Init(SDL_INIT_VIDEO|SDL_INIT_GAMECONTROLLER|SDL_INIT_EVENTS)!=0)return 2;
    if(TTF_Init()!=0){SDL_Quit();return 3;}
    SDL_Window* window=SDL_CreateWindow("Relay Social Link",
        SDL_WINDOWPOS_CENTERED,SDL_WINDOWPOS_CENTERED,W,H,SDL_WINDOW_SHOWN);
    if(!window){TTF_Quit();SDL_Quit();return 4;}
    SDL_Renderer* renderer=SDL_CreateRenderer(window,-1,SDL_RENDERER_ACCELERATED|
        SDL_RENDERER_PRESENTVSYNC);
    if(!renderer)renderer=SDL_CreateRenderer(window,-1,SDL_RENDERER_SOFTWARE);
    if(!renderer){SDL_DestroyWindow(window);TTF_Quit();SDL_Quit();return 5;}
    SDL_RenderSetLogicalSize(renderer,W,H);
    // Opening a controller is required before SDL sends mapped button
    // events. The PS5 homebrew SDL layer exposes the active DualSense here.
    SDL_GameController* gamepad=nullptr;
    for(int i=0;i<SDL_NumJoysticks();++i){
        if(SDL_IsGameController(i)){
            gamepad=SDL_GameControllerOpen(i);
            if(gamepad)break;
        }
    }
    const std::string fontPath=assetRoot+"/font.ttf";
    TTF_Font* title=TTF_OpenFont(fontPath.c_str(),46);
    TTF_Font* body=TTF_OpenFont(fontPath.c_str(),28);
    TTF_Font* small=TTF_OpenFont(fontPath.c_str(),22);
    if(!title||!body||!small){
        if(title)TTF_CloseFont(title);if(body)TTF_CloseFont(body);
        if(small)TTF_CloseFont(small);
        SDL_DestroyRenderer(renderer);SDL_DestroyWindow(window);
        TTF_Quit();SDL_Quit();return 6;
    }
    Shared s;
    {
        std::lock_guard<std::mutex> guard(s.mutex);
        s.active=foregroundUser();
    }
    std::thread network([&]{networkLoop(s);});
    Uint32 lastProfileCheck=SDL_GetTicks();
    bool running=true;
    while(running){
        SDL_Event event{};
        while(SDL_PollEvent(&event)){
            if(event.type==SDL_QUIT){running=false;break;}
            if(event.type==SDL_CONTROLLERDEVICEADDED&&!gamepad&&
                SDL_IsGameController(event.cdevice.which)){
                gamepad=SDL_GameControllerOpen(event.cdevice.which);
            }
            if(event.type==SDL_CONTROLLERDEVICEREMOVED&&gamepad&&
                SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(gamepad))==event.cdevice.which){
                SDL_GameControllerClose(gamepad);
                gamepad=nullptr;
            }
            if(event.type==SDL_KEYDOWN||event.type==SDL_CONTROLLERBUTTONDOWN){
                const bool isKey=event.type==SDL_KEYDOWN;
                const int control=isKey?event.key.keysym.sym:event.cbutton.button;
                if(control==(isKey?SDLK_ESCAPE:SDL_CONTROLLER_BUTTON_B)){
                    running=false;break;
                }else if(control==(isKey?SDLK_RETURN:SDL_CONTROLLER_BUTTON_A)){
                    std::lock_guard<std::mutex> guard(s.mutex);
                    if(s.active.trusted&&!s.pending&&!s.linked) {
                        s.requestLink=true;s.showSignup=false;
                        s.message="Starting owner-approved console linking...";
                    }
                }else if(control==(isKey?SDLK_c:SDL_CONTROLLER_BUTTON_Y)){
                    std::lock_guard<std::mutex> guard(s.mutex);
                    s.showSignup=true;
                }else if(control==(isKey?SDLK_l:SDL_CONTROLLER_BUTTON_RIGHTSHOULDER)||
                         control==(isKey?SDLK_TAB:SDL_CONTROLLER_BUTTON_LEFTSHOULDER)){
                    std::lock_guard<std::mutex> guard(s.mutex);
                    s.showSignup=!s.showSignup;
                }
            }
        }
        if(SDL_GetTicks()-lastProfileCheck>2000){
            lastProfileCheck=SDL_GetTicks();
            User current=foregroundUser();
            std::lock_guard<std::mutex> guard(s.mutex);
            if(current.id!=s.active.id||current.name!=s.active.name)s.active=current;
        }
        draw(renderer,title,body,small,s);
        SDL_Delay(30);
    }
    s.running.store(false);
    network.join();
    if(gamepad)SDL_GameControllerClose(gamepad);
    TTF_CloseFont(title);TTF_CloseFont(body);TTF_CloseFont(small);
    SDL_DestroyRenderer(renderer);SDL_DestroyWindow(window);
    TTF_Quit();SDL_Quit();
    return 0;
}

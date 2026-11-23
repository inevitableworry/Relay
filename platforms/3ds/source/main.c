// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Relay 3DS family: independent top presentation and bottom menu / touchscreen
// keyboard. Experimental UI-only build; no console streaming transport yet.
#include <3ds.h>
#include <citro2d.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#define TOP_W 400
#define BOT_W 320
#define TEXT_MAX 31
static C2D_TextBuf textbuf;
static C3D_RenderTarget *top, *bottom;
static int page = 0, selected = 0, key_col = 0, key_row = 0;
static bool keyboard = false, shift = false, symbols = false;
static bool panel = false;
static char search[TEXT_MAX+1] = "";
static const char *menu_labels[] = {"REMOTE PLAY", "CLOUD", "SEARCH", "SETTINGS"};
// PS5-inspired, not a claim of an exact PS Portal firmware keyboard dump.
static const char *key_rows[] = {"1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"};
static const char *symbol_rows[] = {"1234567890", "!@#$%^&*()", "-_=+[]{};", ".,?/\\:~"};
static int row_length(int row) { return row == 4 ? 5 : (int)strlen(symbols?symbol_rows[row]:key_rows[row]); }

static u32 rgba(int r,int g,int b,int a) { return C2D_Color32(r,g,b,a); }
static void label(const char *str,float x,float y,float s,u32 color) {
    C2D_Text text;
    C2D_TextParse(&text,textbuf,str);
    C2D_TextOptimize(&text);
    C2D_DrawText(&text,C2D_WithColor,x,y,.5f,s,s,color);
}
static void box(float x,float y,float w,float h,u32 color) {
    C2D_DrawRectSolid(x,y,.05f,w,h,color);
}
static void keypress(char c) {
    size_t len=strlen(search);
    if(c=='\b') {
        if(len) search[len-1]=0;
    } else if(c=='\n') {
        keyboard=false;
        page=2;
    } else if(len<TEXT_MAX) {
        search[len]=c;
        search[len+1]=0;
    }
}
static void activate_menu(int item) {
    if(item<0 || item>3) return;
    page=item;
    selected=item;
    if(item==2) {keyboard=true;key_col=0;key_row=0;}
    else {keyboard=false; panel=item==3;}
}
static void keyboard_select(int col,int row) {
    if(row==4) {
        switch(col) {
            case 0: shift=!shift;break;
            case 1: symbols=!symbols;break;
            case 2: keypress(' ');break;
            case 3: keypress('\b');break;
            case 4: keypress('\n');break;
        }
        return;
    }
    if(row<0 || row>3 || col<0 || col>=row_length(row)) return;
    char c=(symbols?symbol_rows[row]:key_rows[row])[col];
    if(!symbols && !shift && c>='A' && c<='Z') c+='a'-'A';
    keypress(c);
}

// Full top screen remains free of the menu/keyboard. Until a decoder exists,
// show the canonical Relay visual scene and truthful no-stream placeholder.
static void draw_top(float time,bool new3ds) {
    C2D_TargetClear(top,rgba(7,8,22,255));
    C2D_SceneBegin(top);
    for(int i=0;i<20;++i) {
        u32 c=rgba(8+i/3,9+i/5,23+i,255);
        box(0,i*12,TOP_W,12,c);
    }
    const float cx=206, cy=116;
    for(int i=14;i>=0;--i) {
        float radius=37+i*4.5f;
        C2D_DrawCircleSolid(cx,cy,.12f,radius,rgba(64,30,149,7+i*2));
    }
    for(int i=0;i<11;++i) {
        float radius=44-i*2.8f;
        float phase=sin(time*.55f+i*.3f)*2.0f;
        C2D_DrawCircle(cx-2,cy+phase,.18f,radius,
            rgba(50,57,146,235),rgba(91,71,177,245),
            rgba(41,27,117,241),rgba(19,20,67,250));
    }
    for(int i=0;i<68;++i) {
        float angle=i*2.39996f+time*.22f;
        float r=25+(i%9)*9;
        float x=cx+cosf(angle)*r;
        float y=cy+sinf(angle)*r*.52f;
        C2D_DrawCircleSolid(x,y,.3f,(i%6==0)?1.8f:.9f,
            rgba(179,158,248,(i%4)*18+85));
    }
    label("RELAY",13,13,.82f,rgba(242,237,255,255));
    label(new3ds?"NEW 3DS":"3DS",347,17,.38f,rgba(199,180,238,255));
    if(page==0) {
        label("Connect to your PlayStation",13,169,.59f,rgba(240,236,255,255));
        label("No active stream - experimental client",14,191,.39f,rgba(192,184,215,255));
    } else if(page==1) {
        label("Cloud",15,174,.68f,rgba(240,236,255,255));
        label("Not connected",15,198,.47f,rgba(191,183,214,255));
    } else if(page==2) {
        label("Search",14,162,.64f,rgba(238,230,255,255));
        label(search[0]?search:"Type on the lower touchscreen",14,190,.45f,rgba(206,197,240,255));
    } else {
        label("System settings",14,174,.63f,rgba(238,230,255,255));
        label("Native 3DS test build",14,198,.43f,rgba(191,183,214,255));
    }
    box(12,224,376,1,rgba(89,70,151,160));
}

static void draw_menu(void) {
    label(panel?"QUICK SETTINGS":"RELAY MENU",12,12,.60f,rgba(243,236,255,255));
    for(int i=0;i<4;++i) {
        float y=45+i*41;
        box(12,y,296,35,(i==selected)?rgba(79,60,147,255):rgba(37,31,74,255));
        if(i==selected) box(12,y,4,35,rgba(178,150,249,255));
        label(menu_labels[i],25,y+8,.48f,rgba(249,245,255,255));
    }
    label("A Select  X Keyboard  START Exit",13,219,.37f,rgba(187,174,218,255));
}

static void draw_keyboard(void) {
    label("SEARCH",10,7,.55f,rgba(242,234,255,255));
    label("L/R cursor    A type",137,11,.35f,rgba(192,186,216,255));
    box(10,31,300,30,rgba(31,28,65,255));
    char displayed[TEXT_MAX+4];
    snprintf(displayed,sizeof(displayed),"%s_",search);
    label(displayed,18,40,.54f,rgba(245,239,255,255));
    for(int row=0;row<4;++row) {
        int length=row_length(row);
        float start=(320-length*29)/2.0f;
        for(int col=0;col<length;++col) {
            float x=start+col*29, y=67+row*30;
            bool focus=key_row==row && key_col==col;
            box(x,y,26,27,focus?rgba(106,88,173,255):rgba(36,38,55,255));
            if(focus) box(x,y,26,2,rgba(218,206,255,255));
            char s[2]={(symbols?symbol_rows[row]:key_rows[row])[col],0};
            if(!symbols && !shift && s[0]>='A' && s[0]<='Z') s[0]+='a'-'A';
            label(s,x+7,y+5,.49f,rgba(250,249,255,255));
        }
    }
    const char *bottomkeys[]={"a/A","@#","SPACE","DEL","DONE"};
    int widths[]={48,43,97,45,61};
    int start=5;
    for(int col=0;col<5;++col) {
        int w=widths[col];
        box(start,191,w,30,(key_row==4 && key_col==col)?rgba(104,81,185,255):rgba(43,39,70,255));
        label(bottomkeys[col],start+5,199,.39f,rgba(247,240,255,255));
        start+=w+4;
    }
    label("D-pad select  A type  B close",11,224,.37f,rgba(184,171,216,255));
}

static void draw_bottom(void) {
    C2D_TargetClear(bottom,rgba(11,11,28,255));
    C2D_SceneBegin(bottom);
    box(0,0,BOT_W,36,rgba(23,18,55,255));
    if(keyboard) draw_keyboard();
    else draw_menu();
}

static void handle_touch(int x,int y) {
    if(keyboard) {
        if(y>=67 && y<187) {
            int row=(y-67)/30;
            if(row<4) {
                int length=row_length(row);
                int start=(BOT_W-length*29)/2;
                int col=(x-start)/29;
                if(x>=start && col>=0 && col<length) {
                    key_col=col;key_row=row;keyboard_select(col,row);
                }
            }
        } else if(y>=191 && y<221) {
            int starts[]={5,57,104,205,254};
            int widths[]={48,43,97,45,61};
            for(int i=0;i<5;++i) if(x>=starts[i] && x<starts[i]+widths[i]) {
                key_col=i;key_row=4;keyboard_select(i,4);break;
            }
        }
    } else if(y>=45 && y<209 && x>=12 && x<308) {
        activate_menu((y-45)/41);
    }
}

int main(void) {
    gfxInitDefault();
    C3D_Init(C3D_DEFAULT_CMDBUF_SIZE);
    if(!C2D_Init(C2D_DEFAULT_MAX_OBJECTS)){C3D_Fini();gfxExit();return 1;}
    C2D_Prepare();
    textbuf=C2D_TextBufNew(8192);
    top=C2D_CreateScreenTarget(GFX_TOP,GFX_LEFT);
    bottom=C2D_CreateScreenTarget(GFX_BOTTOM,GFX_LEFT);
    bool n3ds=false;
    if(R_FAILED(APT_CheckNew3DS(&n3ds))) n3ds=false;
    float tick=0.0f;
    while(aptMainLoop()) {
        hidScanInput();
        u32 keys=hidKeysDown();
        if(keys&KEY_START) break;
        if(keys&KEY_TOUCH) {
            touchPosition pos;hidTouchRead(&pos);
            handle_touch(pos.px,pos.py);
        }
        if(keyboard) {
            if(keys&KEY_B){keyboard=false;}
            if(keys&KEY_X){keyboard=false;}
            if(keys&KEY_UP) {key_row=(key_row+4)%5;if(key_col>=row_length(key_row))key_col=row_length(key_row)-1;}
            if(keys&KEY_DOWN){key_row=(key_row+1)%5;if(key_col>=row_length(key_row))key_col=row_length(key_row)-1;}
            if(keys&KEY_LEFT){int n=row_length(key_row);key_col=(key_col+n-1)%n;}
            if(keys&KEY_RIGHT){int n=row_length(key_row);key_col=(key_col+1)%n;}
            if(keys&KEY_A)keyboard_select(key_col,key_row);
            if(keys&KEY_Y)keypress('\b');
        } else {
            if(keys&KEY_UP)selected=(selected+3)%4;
            if(keys&KEY_DOWN)selected=(selected+1)%4;
            if(keys&KEY_A)activate_menu(selected);
            if(keys&KEY_X){keyboard=true;page=2;}
            if(keys&KEY_B){selected=0;page=0;panel=false;}
        }
        C2D_TextBufClear(textbuf);
        C3D_FrameBegin(C3D_FRAME_SYNCDRAW);
        draw_top(tick,n3ds);
        draw_bottom();
        C3D_FrameEnd(0);
        tick+=.016667f;
    }
    C2D_TextBufDelete(textbuf);
    C2D_Fini(); C3D_Fini(); gfxExit();
    return 0;
}

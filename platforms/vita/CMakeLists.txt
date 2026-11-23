cmake_minimum_required(VERSION 3.12)

if(NOT DEFINED ENV{VITASDK})
  message(FATAL_ERROR "VITASDK environment variable is not set")
endif()
if(NOT DEFINED CMAKE_TOOLCHAIN_FILE)
  set(CMAKE_TOOLCHAIN_FILE "$ENV{VITASDK}/share/vita.toolchain.cmake" CACHE PATH "Vita toolchain")
endif()
project(relay_vita C)
include("$ENV{VITASDK}/share/vita.cmake" REQUIRED)

set(VITA_APP_NAME "Relay for PS Vita")
set(VITA_TITLEID "RLYV00001")
set(VITA_VERSION "00.10")
set(CMAKE_C_STANDARD 11)

add_executable(relay_vita source/main.c source/relay_audio.c)
target_include_directories(relay_vita PRIVATE generated)
target_compile_options(relay_vita PRIVATE -Wall -Wextra -O2)
target_link_libraries(relay_vita
  vita2d
  freetype bz2 png jpeg z m
  SceDisplay_stub SceGxm_stub SceCtrl_stub SceTouch_stub SceAudio_stub ScePgf_stub ScePvf_stub
  SceCommonDialog_stub SceLibKernel_stub SceSysmodule_stub SceAppMgr_stub
)
vita_create_self(eboot.bin relay_vita)

# Bundle original navigation artwork (same PNGs as Relay Android XML-derived)
# and exact-source sphere gradients. Device compatibility depends on Vita3K.
vita_create_vpk(relay_vita.vpk ${VITA_TITLEID} eboot.bin
  VERSION ${VITA_VERSION}
  NAME ${VITA_APP_NAME}
  FILE assets/icon0.png sce_sys/icon0.png
  FILE assets/portal_nav_remote.png assets/portal_nav_remote.png
  FILE assets/portal_nav_cloud.png assets/portal_nav_cloud.png
  FILE assets/portal_nav_search.png assets/portal_nav_search.png
  FILE assets/portal_status_wifi.png assets/portal_status_wifi.png
  FILE assets/portal_status_battery.png assets/portal_status_battery.png
  FILE assets/portal_status_sliders.png assets/portal_status_sliders.png
  FILE assets/sphere.png assets/sphere.png
  FILE assets/halo.png assets/halo.png
  FILE assets/cloud.png assets/cloud.png
  FILE assets/dot.png assets/dot.png
  FILE assets/floor.png assets/floor.png
  FILE assets/background.png assets/background.png
  FILE assets/sphere_shadow.png assets/sphere_shadow.png
  FILE assets/sphere_gloss.png assets/sphere_gloss.png
  FILE assets/quick_panel.png assets/quick_panel.png
  FILE assets/dialog_panel.png assets/dialog_panel.png
  FILE assets/focus_row.png assets/focus_row.png
  FILE assets/connect_button.png assets/connect_button.png
  FILE assets/menu_focus.ogg assets/menu_focus.ogg
  FILE assets/menu_confirm.ogg assets/menu_confirm.ogg
  FILE assets/menu_back.ogg assets/menu_back.ogg
  FILE assets/menu_open.ogg assets/menu_open.ogg
  FILE assets/menu_close.ogg assets/menu_close.ogg
  FILE assets/menu_reveal.ogg assets/menu_reveal.ogg
  FILE assets/menu_focus.pcm assets/menu_focus.pcm
  FILE assets/menu_confirm.pcm assets/menu_confirm.pcm
  FILE assets/menu_back.pcm assets/menu_back.pcm
  FILE assets/menu_open.pcm assets/menu_open.pcm
  FILE assets/menu_close.pcm assets/menu_close.pcm
  FILE assets/menu_reveal.pcm assets/menu_reveal.pcm
  FILE assets/inter-variable.ttf assets/inter-variable.ttf
)

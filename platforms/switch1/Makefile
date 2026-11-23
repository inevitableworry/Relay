# Switch 1 native SDL2 Relay UI with original vector-derived art and audio.
# PS5 network streaming backend is still missing; see README.
TARGET := relay-switch1
BUILD := build
SOURCES := source
DATA :=
INCLUDES := include
APP_TITLE := Relay Switch 1 UI (Preview)
APP_AUTHOR := Relay Project
APP_VERSION := 0.1.0
ROMFS := romfs
ARCH := -march=armv8-a+crc+crypto -mtune=cortex-a57 -mtp=soft -fPIE
CFLAGS := -g -Wall -O2 -ffunction-sections $(ARCH) $(DEFINES)
CFLAGS += $(INCLUDE) -D__SWITCH__
CXXFLAGS := $(CFLAGS) -std=gnu++17 -fno-rtti -fno-exceptions
ASFLAGS := -g $(ARCH)
LDFLAGS := -specs=$(DEVKITPRO)/libnx/switch.specs -g $(ARCH) -Wl,-Map,$(notdir $*.map)
LIBS := -lSDL2_mixer -lSDL2_ttf -lSDL2_image -lSDL2 -lswscale -lavcodec -lswresample -lavutil -ldav1d -lcurl -lmbedtls -lmbedx509 -lmbedcrypto -lvorbisidec -lopusfile -lopus -logg -lmodplug -lmpg123 -lFLAC -lfreetype -lharfbuzz -lfreetype -lwebp -lpng -ljpeg -lbz2 -lz -lEGL -lglapi -ldrm_nouveau -lnx -lm
LIBDIRS := $(PORTLIBS) $(LIBNX)

ifeq ($(strip $(DEVKITPRO)),)
$(error DEVKITPRO must point to the installed devkitPro root)
endif
TOPDIR ?= $(CURDIR)
include $(DEVKITPRO)/libnx/switch_rules

ifneq ($(BUILD),$(notdir $(CURDIR)))
export OUTPUT := $(CURDIR)/$(TARGET)
export TOPDIR := $(CURDIR)
export VPATH := $(foreach dir,$(SOURCES),$(CURDIR)/$(dir))
export DEPSDIR := $(CURDIR)/$(BUILD)
CPPFILES := $(foreach dir,$(SOURCES),$(notdir $(wildcard $(dir)/*.cpp)))
export LD := $(CXX)
export OFILES := $(addsuffix .o,$(basename $(CPPFILES)))
export INCLUDE := $(foreach dir,$(INCLUDES),-I$(CURDIR)/$(dir)) -I$(LIBNX)/include
export LIBPATHS := -L$(LIBNX)/lib
export INCLUDE += -I$(PORTLIBS)/include -I$(PORTLIBS)/include/SDL2 -I$(LIBNX)/include
export LIBPATHS += -L$(PORTLIBS)/lib
export NROFLAGS += --romfsdir=$(CURDIR)/$(ROMFS)
export NROFLAGS += --icon=$(TOPDIR)/icon.jpg
.PHONY: all clean $(BUILD)
all: $(BUILD)
$(BUILD):
	@[ -d $@ ] || mkdir -p $@
	@$(MAKE) --no-print-directory -C $(BUILD) -f $(CURDIR)/Makefile
clean:
	@echo clean...
	@rm -rf $(BUILD) $(TARGET).nro $(TARGET).elf $(TARGET).nacp
else
DEPENDS := $(OFILES:.o=.d)
all: $(OUTPUT).nro
$(OUTPUT).nro: $(OUTPUT).elf $(OUTPUT).nacp
$(OUTPUT).elf: $(OFILES)
$(OUTPUT).nacp:
	@nacptool --create "$(APP_TITLE)" "$(APP_AUTHOR)" "$(APP_VERSION)" $@
-include $(DEPENDS)
endif

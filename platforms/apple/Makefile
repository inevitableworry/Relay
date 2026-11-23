# Experimental shared UI shell, not yet a functional Remote Play client.
# Requires TheosWin with Apple SDKs and an iOS/tvOS linker installed.
export ARCHS = arm64
TARGET ?= iphone:clang:16.5:7.0
include $(THEOS)/makefiles/common.mk
APPLICATION_NAME = Relay
Relay_FILES = RelayApp/RelayApp.m
Relay_FRAMEWORKS = UIKit Foundation QuartzCore
Relay_CFLAGS = -fobjc-arc
Relay_RESOURCE_FILES = RelayApp/Info-iOS.plist
include $(THEOS_MAKE_PATH)/application.mk

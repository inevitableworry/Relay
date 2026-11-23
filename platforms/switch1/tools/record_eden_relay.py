"""Record actual visible frames from BEN-PC's Eden window via PrintWindow.

Windows GDI's ordinary window-capture often misses Vulkan backbuffers. PrintWindow
is verified to capture Relay's UI in this Eden version. Frames are sent to the
existing FFmpeg for H.264 output. Reject an all-black/unchanged failure video.
"""
import argparse
import ctypes
from ctypes import wintypes
from pathlib import Path
import subprocess
import time

import numpy as np
import cv2

user32 = ctypes.WinDLL("user32", use_last_error=True)
gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)


class RECT(ctypes.Structure):
    _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long),
                ("right", ctypes.c_long), ("bottom", ctypes.c_long)]


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [("biSize", wintypes.DWORD), ("biWidth", ctypes.c_long),
                ("biHeight", ctypes.c_long), ("biPlanes", wintypes.WORD),
                ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
                ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", ctypes.c_long),
                ("biYPelsPerMeter", ctypes.c_long), ("biClrUsed", wintypes.DWORD),
                ("biClrImportant", wintypes.DWORD)]


class BITMAPINFO(ctypes.Structure):
    _fields_ = [("bmiHeader", BITMAPINFOHEADER), ("bmiColors", wintypes.DWORD * 3)]


user32.GetWindowRect.argtypes = (wintypes.HWND, ctypes.POINTER(RECT))
user32.GetWindowRect.restype = wintypes.BOOL
user32.PrintWindow.argtypes = (wintypes.HWND, wintypes.HDC, wintypes.UINT)
user32.PrintWindow.restype = wintypes.BOOL
user32.GetDC.argtypes = (wintypes.HWND,)
user32.GetDC.restype = wintypes.HDC
user32.ReleaseDC.argtypes = (wintypes.HWND, wintypes.HDC)
user32.ReleaseDC.restype = ctypes.c_int
gdi32.CreateCompatibleDC.argtypes = (wintypes.HDC,)
gdi32.CreateCompatibleDC.restype = wintypes.HDC
gdi32.CreateCompatibleBitmap.argtypes = (wintypes.HDC, ctypes.c_int, ctypes.c_int)
gdi32.CreateCompatibleBitmap.restype = wintypes.HBITMAP
gdi32.SelectObject.argtypes = (wintypes.HDC, wintypes.HGDIOBJ)
gdi32.SelectObject.restype = wintypes.HGDIOBJ
gdi32.GetDIBits.argtypes = (wintypes.HDC, wintypes.HBITMAP, wintypes.UINT, wintypes.UINT,
                           ctypes.c_void_p, ctypes.POINTER(BITMAPINFO), wintypes.UINT)
gdi32.GetDIBits.restype = ctypes.c_int
gdi32.DeleteObject.argtypes = (wintypes.HGDIOBJ,)
gdi32.DeleteDC.argtypes = (wintypes.HDC,)


def record(hwnd: int, output: Path, duration: int = 14, fps: int = 12):
    rect = RECT()
    if not user32.GetWindowRect(hwnd, ctypes.byref(rect)):
        raise RuntimeError("Eden window no longer exists")
    w, h = rect.right - rect.left, rect.bottom - rect.top
    if not 600 <= w <= 3840 or not 400 <= h <= 2400:
        raise RuntimeError(f"Unexpected Eden dimensions: {w}x{h}")
    # Even target dimensions needed for H.264 yuv420p.
    w -= w % 2
    h -= h % 2
    screen_dc = user32.GetDC(None)
    dc = gdi32.CreateCompatibleDC(screen_dc)
    bmp = gdi32.CreateCompatibleBitmap(screen_dc, w, h)
    user32.ReleaseDC(None, screen_dc)
    old = gdi32.SelectObject(dc, bmp)
    info = BITMAPINFO()
    info.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    info.bmiHeader.biWidth = w
    info.bmiHeader.biHeight = -h  # Top-down BGRA pixels.
    info.bmiHeader.biPlanes = 1
    info.bmiHeader.biBitCount = 32
    info.bmiHeader.biCompression = 0
    array = np.zeros((h, w, 4), dtype=np.uint8)
    output.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f", "rawvideo",
           "-pixel_format", "bgr24", "-video_size", f"{w}x{h}", "-framerate", str(fps),
           "-i", "pipe:0", "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
           "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(output)]
    writer = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    valid = 0
    checksums = set()
    errors = []
    t0 = time.monotonic()
    interval = 1 / fps
    try:
        for i in range(duration * fps):
            due = t0 + i * interval
            if due > time.monotonic():
                time.sleep(due - time.monotonic())
            if not user32.PrintWindow(hwnd, dc, 2):
                errors.append(f"PrintWindow failed at frame {i}")
                break
            if gdi32.GetDIBits(dc, bmp, 0, h, array.ctypes.data,
                                ctypes.byref(info), 0) != h:
                errors.append(f"GetDIBits failed at frame {i}")
                break
            rgb = cv2.cvtColor(array, cv2.COLOR_BGRA2BGR)
            # Non-black content beyond the titlebar is required.
            sample = rgb[h // 5:h * 4 // 5, w // 5:w * 4 // 5]
            if sample.mean() > 9:
                valid += 1
            if i % max(1, fps // 2) == 0:
                checksums.add(hash(sample[::13, ::13].tobytes()))
            writer.stdin.write(rgb.tobytes())
    finally:
        writer.stdin.close()
        error_text = writer.stderr.read().decode("utf8", "replace")
        writer.wait()
        gdi32.SelectObject(dc, old)
        gdi32.DeleteObject(bmp)
        gdi32.DeleteDC(dc)
    if errors or writer.returncode != 0 or valid < fps * 2:
        output.unlink(missing_ok=True)
        raise RuntimeError(f"Capture invalid. errors={errors} valid_frames={valid} ffmpeg={error_text}")
    print(f"RELAY_EDEN_VIDEO={output} SIZE={output.stat().st_size} FRAMES={valid} UNIQUE_SAMPLES={len(checksums)}")
    if len(checksums) < 2:
        print("WARNING: No motion detected; this is a recording of a rendered UI, not animation proof.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--hwnd", required=True, type=lambda s: int(s, 0))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--duration", type=int, default=14)
    parser.add_argument("--fps", type=int, default=12)
    options = parser.parse_args()
    record(options.hwnd, options.output, options.duration, options.fps)

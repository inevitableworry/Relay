# Relay Xbox 360 — native PowerPC homebrew developer build

The source here is an **independent native Relay Xbox 360 port**, intended for JTAG/RGH consoles and tested in Xenia when an executable can be built. It is not the Windows 11 Xbox Ally or Xbox Series UWP binary and does not import another PlayStation remote-play frontend.

The Xbox 360 development platform runs a PowerPC CPU and Xenos GPU. The current code uses the open-source xenosworks/xsdk GPU/display/text/input interfaces to implement the Relay home menu, L/R tab navigation, procedural sphere using the original 2,600 seeded Android particle coordinates, and a basic informational panel.

**This is not yet an exact visual/audio parity port**: the SDK's current text renderer uses a bitmap font, the particle shader is approximate, and neither original menu music nor PS5 H.264 Remote Play, streamed audio and controller transport have been integrated. The selected native code avoids changing the PS5 or claiming a working connection.

## Build on BEN-PC

The `xsdk` dependency sources are under `toolchain/xsdk` for research. Build the `powerpc-xsdk-elf` toolchain in WSL2 Ubuntu from that project's `tools/toolchain/build.sh`. The build downloads and verifies official GCC 15.3.0, binutils 2.47 and newlib 4.6 source archives and uses `libxsdk.a`. The older toolchain download to ftp.gnu.org had a TLS issue; the current sources can be prefetched from `mirrors.kernel.org` before running the script. Do not switch to the generic `powerpc-linux-gnu` compiler; its ABI is incompatible.

After the toolchain and the `libxsdk.a` SDK have been successfully built:

```sh
python3 scripts/prepare_particles.py
cmake -S . -B build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=<xsdk>/cmake/xsdk.cmake \
  -DXSDK_TOOLCHAIN=/root/xsdk-gcc
cmake --build build
```

The expected native artifact is `build/relay360.xex`. Test it with Xenia Canary before trying it on any RGH/JTAG Xbox 360. An unmodified retail Xbox 360 cannot launch unsigned XEX homebrew.

Do not claim Xenia or physical hardware verification before the artifact is compiled, launched and inspected. No user-installed Xbox 360 system has been touched.

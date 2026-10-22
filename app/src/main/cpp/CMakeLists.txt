cmake_minimum_required(VERSION 3.22.1)
project(aircast C CXX)

set(UXPLAY ${CMAKE_CURRENT_SOURCE_DIR}/uxplay)
set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# --- OpenSSL libcrypto (prebuilt per ABI, see scripts/build_openssl.sh)
add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES
        IMPORTED_LOCATION ${THIRD_PARTY}/openssl/${ANDROID_ABI}/lib/libcrypto.a
        INTERFACE_INCLUDE_DIRECTORIES ${THIRD_PARTY}/openssl/${ANDROID_ABI}/include)

# --- libplist (binary/XML property lists used by the AirPlay control channel)
file(GLOB PLIST_SOURCES ${THIRD_PARTY}/libplist/src/*.c ${THIRD_PARTY}/libplist/libcnary/*.c)
add_library(plist STATIC ${PLIST_SOURCES})
target_include_directories(plist
        PUBLIC ${THIRD_PARTY}/libplist/include
        PRIVATE ${THIRD_PARTY}/libplist/src ${THIRD_PARTY}/libplist/libcnary/include)
target_compile_definitions(plist
        PUBLIC LIBPLIST_STATIC
        PRIVATE PACKAGE_VERSION="2.7.0" HAVE_GMTIME_R HAVE_LOCALTIME_R HAVE_TM_TM_GMTOFF
        HAVE_TM_TM_ZONE HAVE_STRPTIME HAVE_MEMMEM HAVE_STRNDUP)
target_compile_options(plist PRIVATE -O2 -w)

# --- Apple ALAC decoder (AirPlay music streams, ct=2)
file(GLOB ALAC_SOURCES ${THIRD_PARTY}/alac/*.c ${THIRD_PARTY}/alac/*.cpp)
add_library(alac STATIC ${ALAC_SOURCES})
target_include_directories(alac PUBLIC ${THIRD_PARTY}/alac)
target_compile_definitions(alac PRIVATE TARGET_RT_LITTLE_ENDIAN=1)
target_compile_options(alac PRIVATE -O2 -w)

# --- UxPlay AirPlay core: RAOP/AirPlay server, pairing, FairPlay (playfair), RTP, mDNS
file(GLOB UXPLAY_SOURCES
        ${UXPLAY}/*.c
        ${UXPLAY}/llhttp/*.c
        ${UXPLAY}/playfair/*.c
        ${UXPLAY}/mdnsd/*.c)
add_library(airplay STATIC ${UXPLAY_SOURCES})
target_include_directories(airplay PUBLIC ${UXPLAY} ${UXPLAY}/llhttp ${UXPLAY}/playfair ${UXPLAY}/mdnsd)
target_compile_definitions(airplay PRIVATE
        NOHOLD PLIST_210 PLIST_230 OPENSSL_API_COMPAT=0x10101000L
        STANDALONE TARGET_POSIX _REENTRANT __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS)
target_compile_options(airplay PRIVATE -O2 -Wno-unused-parameter -Wno-deprecated-declarations)
target_link_libraries(airplay PUBLIC plist crypto)

# --- JNI bridge
add_library(aircast SHARED android/aircast_jni.c android/alac_bridge.cpp)
target_compile_options(aircast PRIVATE -O2 -Wall)
target_link_options(aircast PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
target_link_libraries(aircast airplay alac log m)

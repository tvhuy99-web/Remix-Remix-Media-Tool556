/*
 * Copyright 2017-2023 Valve Corporation.
 * Licensed under the Apache License, Version 2.0.
 */

#ifndef IPL_PHONON_VERSION_H
#define IPL_PHONON_VERSION_H

/* Kept self-contained because phonon.h includes this file before defining IPLuint32. */
typedef unsigned int IPLuint32;

#define STEAMAUDIO_VERSION_MAJOR 4
#define STEAMAUDIO_VERSION_MINOR 8
#define STEAMAUDIO_VERSION_PATCH 1
#define STEAMAUDIO_VERSION ((IPLuint32) (((IPLuint32) STEAMAUDIO_VERSION_MAJOR << 16) | \
                                         ((IPLuint32) STEAMAUDIO_VERSION_MINOR << 8) | \
                                         ((IPLuint32) STEAMAUDIO_VERSION_PATCH)))

#endif

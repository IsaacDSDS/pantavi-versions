package com.isaacbenavides.aircast.core.media

import android.view.Surface
import com.isaacbenavides.aircast.features.receiver.domain.repository.RenderSurface

/** Wraps an Android [Surface] so the presentation layer can hand it to the domain as a [RenderSurface]. */
class AndroidRenderSurface(val surface: Surface) : RenderSurface

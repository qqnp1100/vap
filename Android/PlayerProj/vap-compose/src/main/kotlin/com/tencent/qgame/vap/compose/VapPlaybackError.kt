package com.tencent.qgame.vap.compose

import com.tencent.qgame.vap.core.spi.SurfacePlaybackErrorKind
import com.tencent.qgame.vap.core.spi.SurfacePlaybackFailure
import com.tencent.qgame.vap.core.spi.SurfacePlaybackStage

enum class VapErrorKind {
    InvalidRequest, SourceAccess, SourceNotSeekable, InvalidVapConfig, UnsupportedCodec,
    ResourceTimeout, ResourceLoad, DecoderFailure, RenderFailure, FrameSyncTimeout, AudioFailure
}

enum class VapErrorStage {
    Request, Source, Config, Resource, Decode, Render, Audio, Release
}

data class VapPlaybackError(
    val kind: VapErrorKind,
    val stage: VapErrorStage,
    val cause: Throwable,
)

internal fun SurfacePlaybackFailure.toPublicError() = VapPlaybackError(
    kind = when (kind) {
        SurfacePlaybackErrorKind.InvalidRequest -> VapErrorKind.InvalidRequest
        SurfacePlaybackErrorKind.SourceAccess -> VapErrorKind.SourceAccess
        SurfacePlaybackErrorKind.SourceNotSeekable -> VapErrorKind.SourceNotSeekable
        SurfacePlaybackErrorKind.InvalidVapConfig -> VapErrorKind.InvalidVapConfig
        SurfacePlaybackErrorKind.UnsupportedCodec -> VapErrorKind.UnsupportedCodec
        SurfacePlaybackErrorKind.ResourceTimeout -> VapErrorKind.ResourceTimeout
        SurfacePlaybackErrorKind.ResourceLoad -> VapErrorKind.ResourceLoad
        SurfacePlaybackErrorKind.DecoderFailure -> VapErrorKind.DecoderFailure
        SurfacePlaybackErrorKind.RenderFailure -> VapErrorKind.RenderFailure
        SurfacePlaybackErrorKind.FrameSyncTimeout -> VapErrorKind.FrameSyncTimeout
        SurfacePlaybackErrorKind.AudioFailure -> VapErrorKind.AudioFailure
    },
    stage = when (stage) {
        SurfacePlaybackStage.Request -> VapErrorStage.Request
        SurfacePlaybackStage.Source -> VapErrorStage.Source
        SurfacePlaybackStage.Config -> VapErrorStage.Config
        SurfacePlaybackStage.Resource -> VapErrorStage.Resource
        SurfacePlaybackStage.Decode -> VapErrorStage.Decode
        SurfacePlaybackStage.Render -> VapErrorStage.Render
        SurfacePlaybackStage.Audio -> VapErrorStage.Audio
        SurfacePlaybackStage.Release -> VapErrorStage.Release
    },
    cause = cause,
)

internal fun invalidRequest(cause: Throwable) =
    VapPlaybackError(VapErrorKind.InvalidRequest, VapErrorStage.Request, cause)

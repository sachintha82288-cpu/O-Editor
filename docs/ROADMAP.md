# Roadmap

Ordered by risk removed first, not by visibility.

1. **Codec-based preview pipeline** — replace retriever frame pulling with a MediaCodec +
   SurfaceTexture ring buffer per layer, feeding the existing compositor textures. The one
   change that makes playback real-time.
2. **Instrumented export tests** — encode a synthetic 3-second timeline on device and
   assert stream count, duration and keyframes with MediaExtractor.
3. **Effect presets** — save and re-apply a stack from the existing catalogue.
4. **Keyframe curve editor** — drag bezier handles the engine already evaluates.
5. **Audio chain UI** — wire the biquad EQ into a per-clip chain, then compressor, limiter
   and ducking on the mix bus.
6. **Waveform rendering** with a cached peak file per asset.
7. **Background export** through WorkManager with resume after process death, on top of the
   existing frame-indexed renderer.
8. **Bezier masks** in the renderer (control points already stored), then motion paths.
9. **Nested sequences / compound clips**, then layer parenting.
10. **Proxy generation** for high-bitrate sources, plus thermal-aware quality scaling.

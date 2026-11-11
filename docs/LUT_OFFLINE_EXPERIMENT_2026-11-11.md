# Fixed-pair LUT experiment (2026-11-11)

The current 33-cube and 65-cube approximations are **not acceptable as a default replacement for the real Pipeline**. Their small average errors hide large signed-input failures. This is an offline experiment on the existing pinned engine plus patches 1..48; production defaults, parameters, versions and resources are unchanged.

## Reproduction and scope

New tools are `tools/native/lut_offline.cpp`, `run_lut_offline.sh`, and `lut_metrics.py`. Point `UP` at the complete patched upstream source, `B` at its matching fully rebuilt engine, `OUT` at an isolated directory, then run:

```sh
MODE=smoke bash tools/native/run_lut_offline.sh
MODE=full bash tools/native/run_lut_offline.sh
python3 tools/native/lut_metrics.py "$OUT/samples.csv" "$OUT/metrics.json"
```

All samples are synthetic. No photographs or private report content were uploaded. Actual `Pipeline::run_film` followed by `run_print` generates both reference values and LUT vertices; this is not a separate reimplementation of the film model. The engine archive SHA256 was `1fc39c94b4e27b1fc81e94366d54d0264cfd80ff7dbeec4e9b7847aab740d373`.

The fixed pair is Kodak Portra 400 and default Kodak Portra Endura, with its original neutral print filters. Input is **linear ProPhoto RGB**, output **linear Display P3**, with both input/output transfer functions disabled. Fusion and the opt-in bounded print tail are off. AE, baseline exposure, scene latitude A/B, scene curve and halation boost are disabled before either reference configuration is made. Thus this does not approximate the application's complete default render, nor its other output spaces.

The point configuration additionally disables halation/headroom, grain, film and print glare, overscan/date, contrast mask, camera/enlarger diffusion and lens blur, scanner blur/unsharp, geometry/crop/upscale. DIR correction stays active, with its spatial diffusion size set to zero. The exported `read_params` JSON omits some internal fields; `contract.json` and the C++ assignments record those too. Film spectral upsampling, exposure/log/development curves, point DIR correction, enlarger spectrum, paper curves, scanner spectral integration and gamut compression remain real engine stages.

Measured checks pass: 6,148 reference samples rendered twice produce identical bytes; reversing their order and changing the image from 1-by-N to N-by-1 also produces identical bytes after reversal. Stage timing confirms no forbidden spatial/global/random stage executes. This establishes the tested fixed configuration's point behavior on this backend, not a theorem for arbitrary parameters or hardware.

## Domain and interpolation

Each finite input channel is restricted to [-0.25, 16]. The separable signed shaper has pivot p=0.01 and allocates 25% of coordinates to negatives. For x<0, s(x)=0.25[1-log(1-x/p)/log(1+0.25/p)]; for x>=0, s(x)=0.25+0.75 log(1+x/p)/log(1+16/p). Its inverse generates the grid. Zero lies exactly on both grid sizes. Values outside this domain are rejected by the sampler and sent through a separate actual Pipeline batch; four such samples are verified against the reference and excluded from approximation metrics. No boundary clamping substitutes for fallback.

Both methods are independently implemented: trilinear interpolation uses eight corner weights; tetrahedral interpolation sorts the three fractional coordinates and uses four barycentric vertices. The LUT stores blue-fast RGB float32, 33^3*3*4=431,244 bytes or 65^3*3*4=3,295,500 bytes, excluding shaper/metadata/runtime overhead. Affine interpolation and signed-shaper round-trip checks pass. This experiment does not install a LUT runtime or establish an Android speedup.

Primary references actually downloaded and read were OCIO v2.4.2 [baking/shaper documentation](https://opencolorio.readthedocs.io/en/v2.4.2/tutorials/baking_luts.html), its [Lut3DOpCPU.cpp](https://github.com/AcademySoftwareFoundation/OpenColorIO/blob/v2.4.2/src/OpenColorIO/ops/lut3d/Lut3DOpCPU.cpp), and colour-science v0.4.6 [interpolation.py](https://github.com/colour-science/colour/blob/v0.4.6/colour/algebra/interpolation.py) and [delta_e.py](https://github.com/colour-science/colour/blob/v0.4.6/colour/difference/delta_e.py). No source was copied and no dependency was added. Local SHA256 values are recorded in the evidence README. Web tool output was empty; successful retrieval used the local HTTP client, not an inferred search result.

## Results and rejection

There are 1,024 samples each for neutrals, dark values, shaper-uniform random RGB, linear-uniform random RGB, saturated primary axes and a signed/highlight track. The PRNG seed is 0x20261111. These 6,144 held-out samples are not LUT grid vertices. Four additional out-of-domain values exercise actual fallback. This is a deliberately adversarial distribution, not an estimate of photograph frequency.

| Grid/method | Mean DE00 | p95 | p99 | Maximum | Linear RGB RMSE |
|---|---:|---:|---:|---:|---:|
| 33 / trilinear | 0.16141 | 0.67288 | 1.21156 | 12.29666 | 0.002066 |
| 33 / tetrahedral | 0.13408 | 0.44830 | 1.04335 | 18.87038 | 0.001549 |
| 65 / trilinear | 0.05037 | 0.20108 | 0.40197 | 7.93909 | 0.000709 |
| 65 / tetrahedral | 0.04349 | 0.12274 | 0.30987 | 15.43892 | 0.000746 |

DE00 means CIEDE2000 after linear Display P3 to XYZ D65 to Lab. The utility defines clipping to [0,1] for this SDR display metric; none of these reference or approximate output channels actually needed clipping. It separately reports unclipped linear RGB error, negative/superwhite input groups and worst samples. Four published Sharma test pairs validate the metric formula to 0.0001. This metric does **not** validate HDR output, luminance adaptation, HLG/PQ or signed HDR display behavior.

The 65-cube tetrahedral worst sample is linear ProPhoto `[0.06942739338, 0.1331221908, -0.1219912544]`. Actual linear P3 is `[0.06230331212, 0.01098500844, 0.04150106758]`; approximation is `[0.08122291416, 0.01050574053, 0.01099183876]`, DE00=15.438915. Trilinear on that same sample is DE00=7.764389. The large blue loss is retained in raw CSV and the JSON worst-sample list. Tetrahedral's better mean/p95 does not imply a better worst case; at 65 it also has worse linear RMSE. This is an interpolation/model-curvature observation, not evidence that the original engine mishandles negative input.

| 65-cube subgroup | Count | Trilinear mean / p95 / max DE00 | Tetrahedral mean / p95 / max DE00 |
|---|---:|---|---|
| Any negative channel | 1,891 | 0.05695 / 0.16590 / 7.93909 | 0.07287 / 0.14157 / 15.43892 |
| Any channel above 1 | 3,286 | 0.03595 / 0.13166 / 0.67654 | 0.02816 / 0.08776 / 0.57244 |
| Neutral track | 1,024 | 0.08066 / 0.33367 / 0.64027 | 0.03187 / 0.13482 / 0.27779 |
| Dark track | 1,024 | 0.00459 / 0.02300 / 0.13642 | 0.00334 / 0.01707 / 0.11048 |

Negative and superwhite categories can overlap; these numbers do not establish that all nonnegative inputs are safe.

Model deletion is measured separately, using a 64-by-64 synthetic exposure/chroma scene. Comparing the point configuration directly against the same pair with configured spatial effects retained gives mean/p95/max DE00 0.17237/0.40136/1.27343, linear RMSE 0.0008803. Timings show actual halation, grain, DIR diffusion, print glare and scanner unsharp in that reference. This single scene is not an upper bound; it excludes AE/scene/boost by construction and does not test arbitrary noise seeds, image sizes, edge patterns or masks. Its error cannot be hidden inside the interpolation error or claimed equivalent to the complete default.

Reject a default replacement now: signed in-domain maxima exceed even a permissive DE00=2 screen threshold, and full spatial behavior is missing. Any later opt-in preview needs an explicit parameter cache key, defined transfer/color-space contract, independently tested safe subdomain and true-render fallback. Changing the film/paper, exposure, illuminant, filters, development, gamut/output space or omitted effects invalidates this baked transform unless handled outside it and revalidated. Do not silently clamp signed/highlight input or substitute approximate edits for the final render. No numerical sweep, shaper tuning or production integration was performed; these results do not establish a safe approximation domain.

## Resources and evidence

AOIDC used four permitted CPU threads with `LP_NUM_THREADS=4`, software llvmpipe LLVM 15, no discrete GPU. Final complete experiment: wall 1.76s, user 4.12s, sys 0.12s, max RSS 134,752 KiB; grid render timings were about 122ms/766ms. Host caches, tiny sample batches and software Vulkan make these unsuitable as Adreno or app latency measurements. No repeat benchmark matrix was run.

Raw CSV, parameter snapshots, timings, full metrics/worst cases and logs are under `tools/native/validation/lut-offline-20261111/final`. LUT binaries remain experimental cloud outputs, not app assets. Their SHA256 values are `a366d867954347f46841b64b999cbf6a0b9c53f718dd8d8ebf748dc4c539892d` (33) and `8bc2d7896e411d4c74bbf36a1048d571ecaceff02e3d33a03d63f7e399490cd9` (65).

An independent staging check also compiled all 53 shaders with the original glslang and verified byte equality of staged derivative LICENSE/NOTICE to repository roots, plus preservation of upstream LICENSE.txt. It built no APK and changed no production resource.

# Engine scheduling methods worth adopting

Co-created with GPT (OpenAI), 2026-11-08. See NOTICE for project terms.

Scope: primary project documentation and original papers, inspected on
2026-11-08. This is a comparison of methods, not a dependency migration proposal
or a claim that another filter reproduces SpektraLab's spectral materials.

| Source | Useful method | Application to this engine | Why it is not a direct replacement |
| --- | --- | --- | --- |
| libvips, How It Works | Demand-driven rectangular regions, small intermediate regions, file-backed/sequential sources, streaming sinks | Introduce a plane/row store and pull only the source/halo needed for an output region; stream encoded output instead of assembling full GPU planes | Existing whole-image API, RAW decoder, global statistics and IIR states need explicit adapters; region execution alone does not define this engine's float order or materials |
| Halide producer/consumer scheduling | Separate where values are calculated from where their storage lives (`compute_at`, `store_at`); choose recomputation versus intermediate storage | Keep algorithms stable while changing buffer lifetime and loop nesting; current FIR row scratch is a manual instance of this idea | Rewriting kernels into another compiler can alter floating arithmetic, vectorization and GPU ABI; existing exactness tests remain necessary |
| Halide GPU tutorial | Tile mapping to blocks/threads, schedule intermediate producers at a selected loop and memory scope | Choose workgroup and scratch-size variants from device limits, then measure; avoid a universal 256-thread assumption | The inspected example uses CUDA/OpenCL targets and is not proof of current Android Vulkan support or a ready drop-in backend |
| GPUImage / Android GPUImage | Explicit input-filter-output graphs; retain image processing on GPU to avoid repeated CPU round trips | Improve preview graph caching and final display handoff after measuring ownership/interop costs | Its GL/ES filter graph does not supply the 81-sample spectral chain, double-float IIR contract or output-color math of this engine. Similar-looking preset filters do not establish desktop parity |
| Gaussian convolution survey | Compare FIR, frequency-domain and recursive methods together with their boundary conditions | Treat boundary rule and recurrence initial state as part of every blur contract; add edge impulses and small-domain tests | Switching methods changes approximation and roundoff. A faster approximate Gaussian cannot be silently substituted when unchanged pixels are promised |
| FFTW accuracy guidance | Different FFT algorithms/plans can differ numerically | Consider FFT only for sufficiently wide kernels after explicit performance and numerical tradeoff analysis | FFT/overlap-add changes arithmetic and requires padding/normalization/edge semantics. It cannot promise bit equality to the current two-pass FIR or IIR merely because convolution is mathematically equivalent |
| Realistic Film Grain Rendering | Resolution-independent physical grain model, Monte Carlo rendering, alternative execution strategies | Use a global physical-coordinate/seed contract and compare preview/export at matching physical scale; profile alternative sampling schedules | This paper's grain realization and model are not the current material/RNG pipeline. Replacing the sampler changes image bytes and can change grain statistics |

## Specific adaptations

1. Move the tiling boundary before full GPU source upload. `strip_rows` currently
   starts after whole-frame storage exists. Each bound SSBO must fit its legal
   range independently of total RAM, and a final full result can still dominate
   host memory without an incremental sink.
2. Pointwise chains may share a region. Sequential FIR support radii add;
   alternative kernels over the same input use the maximum radius. Preserve
   physical frame coordinates and exact boundary behavior. Current patch 0019
   instead keeps the source/output whole and only bounds the separable FIR
   intermediate, a smaller validated step.
3. Recursive filters require carried state and the original traversal order.
   A finite halo is an approximation, not an exact substitute for a recurrence.
   Keep forward/reverse state and lossless intermediate float values if the
   eventual executor stops materializing the full plane.
4. Global exposure/boost/statistics need a prepass or a deliberately specified
   reduction schedule, followed by region rendering with immutable constants.
   Independently normalizing every tile changes the image.
5. Grain must use one frame seed and globally indexed counters; do not assign
   random streams by tile execution order. Matching preview/export geometry is
   separate from matching the same stochastic realization across resolutions.

## Vulkan constraints that govern all these schedules

A smaller dispatch is not necessarily a smaller storage descriptor. A VkBuffer
may be larger than `maxStorageBufferRange`, but an individual storage binding
must obey the advertised range. A bounded-scratch optimization cannot by itself
make a full-resolution source binding legal.

Queue completion and memory visibility are distinct. Preserve transfer-to-shader,
shader-to-shader and shader-to-host access dependencies; do not remove broad
barriers until the access graph expresses replacements. Descriptors, command
buffers, inline rings and scratch allocations need a completion-based retirement
point. A timed-out fence still owns all pending resources. See the implemented
probe correction and tests in [GPU_PROBE_LIFETIME.md](GPU_PROBE_LIFETIME.md).

`precise`/NoContraction controls arithmetic transformations; supported floating
point execution modes govern rounding and special values. They do not guarantee
identical exp/log/trigonometric implementations across vendors. First require
same-build/device bytes for scheduling-only changes. Cross-GPU byte identity is
a distinct deterministic-math project with a defined performance cost.

## Primary references

- libvips architecture: https://www.libvips.org/API/current/how-it-works.html
- Halide scheduling source tutorial: https://github.com/halide/Halide/blob/main/tutorial/lesson_08_scheduling_2.cpp
- Halide GPU source tutorial: https://github.com/halide/Halide/blob/main/tutorial/lesson_12_using_the_gpu.cpp
- GPUImage 2 official repository: https://github.com/BradLarson/GPUImage2
- Android GPUImage official repository: https://github.com/cats-oss/android-gpuimage
- Pascal Getreuer, A Survey of Gaussian Convolution Algorithms, IPOL 2013: https://www.ipol.im/pub/art/2013/87/
- FFTW FAQ, sections 3.7-3.8: https://www.fftw.org/faq/section3.html
- Newson, Faraj, Galerne and Delon, Realistic Film Grain Rendering, IPOL 2017: https://www.ipol.im/pub/art/2017/192/
- Khronos descriptor requirements: https://raw.githubusercontent.com/KhronosGroup/Vulkan-Docs/main/chapters/descriptorsets.adoc
- Khronos synchronization: https://raw.githubusercontent.com/KhronosGroup/Vulkan-Docs/main/chapters/synchronization.adoc
- Khronos float precision: https://raw.githubusercontent.com/KhronosGroup/Vulkan-Docs/main/appendices/spirvenv.adoc

No external project was added as a runtime dependency and no external algorithm
was substituted for the film or paper model in this investigation.

// Co-created with GPT (OpenAI), 2026-11-10. Synthetic production-JNI coverage.
// Reuse the existing Android allocation shim; no decode or engine API is mocked.
#define SPK_JNI_PLATFORM_SHIM_ONLY
#include "jni_precision.cpp"
#include <chrono>
#include <set>
#include <thread>

namespace {
std::atomic<std::size_t> fail_new_size{0};
std::atomic<bool> failed_wrapper_new{false};
}

extern "C" void* __real__Znwm(std::size_t);
extern "C" void* __wrap__Znwm(std::size_t size) {
    std::size_t expected = size;
    if (size != 0 && fail_new_size.compare_exchange_strong(expected, 0)) {
        failed_wrapper_new.store(true);
        throw std::bad_alloc();
    }
    return __real__Znwm(size);
}

namespace {
std::atomic<bool> path_entered{false}, signal_posted{false};
jstring watched_path = nullptr;
const char* JNICALL cancellable_get_string(JNIEnv* env, jstring value, jboolean* copied) {
    if (value == watched_path) {
        path_entered.store(true, std::memory_order_release);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        while (!signal_posted.load(std::memory_order_acquire)) {
            if (std::chrono::steady_clock::now() > deadline) std::terminate();
            std::this_thread::yield();
        }
    }
    return get_string(env, value, copied);
}

void working_input_tests(JNIEnv* env, jlong engine, char** files) {
    using namespace spk::android;
    int cases = 0;
    for (int argument = 3; argument < 6; ++argument) {
        auto probe = std::make_unique<spk::io::detail::CameraLibRaw>();
        require(probe->open_file(files[argument]) == LIBRAW_SUCCESS, "working source metadata");
        const auto plan = inspect_raw_precision(files[argument], *probe, false);
        spk::io::DecodedRaw full;
        std::string error;
        require(decode_raw_precision(files[argument], plan, nullptr, 0, full, error), "full working reference");
        for (const bool standard : {false, true}) for (const bool tiny : {false, true}) {
            const uint32_t edge = tiny ? 8 : std::max(1u, std::max(full.width, full.height) * 3 / 4);
            const bool binned = !standard && plan.mode != "float_linear_v1" && tiny;
            spk::io::DecodedRaw reference = full;
            if (binned) {
                RawHalfSizeBudget budget(1);
                require(decode_raw_precision(files[argument], plan, nullptr, 0, reference, error), "binned working reference");
            }
            uint32_t w, h;
            fit_rgb_size(reference.width, reference.height, edge, 0, w, h);
            std::vector<float> expected;
            resize_area_rgb(reference.rgb, reference.width, reference.height, w, h, expected);
            const auto params = text(std::string(R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"product_defaults":true,"x_no_film":true,"x_raw_auto_exposure":false,"x_raw_development":")") +
                (standard ? "standard_v1" : "neutral") + R"("})");
            const jlong handle = CALL(openRaw)(env, nullptr, engine, text(files[argument]), edge, 0, params, 0, 0);
            require(handle != 0, "production JNI working RAW open");
            const auto* session = reinterpret_cast<Session*>(handle);
            require(session->width == w && session->height == h && session->source_width == full.width &&
                session->source_height == full.height && session->small_rgb.size() == expected.size() &&
                std::memcmp(session->small_rgb.data(), expected.data(), expected.size() * sizeof(float)) == 0,
                "JNI source provenance and every working float bit");
            require(session->raw_stats_ready == standard && session->raw_stats_binned == binned,
                "JNI full-source statistics readiness and binning policy");
            if (standard) {
                const auto stats = raw_development_stats(full.rgb.data(), full.width, full.width, full.height);
                require(session->raw_stats.histogram == stats.histogram && session->raw_stats.log2_mean == stats.log2_mean &&
                    session->raw_stats.valid_pixels == stats.valid_pixels && session->raw_stats.invalid_pixels == stats.invalid_pixels &&
                    session->raw_stats.dark_pixels == stats.dark_pixels && session->raw_stats.available == stats.available,
                    "standard AE-off statistics still meter complete unbinned source");
            }
            CALL(release)(env, nullptr, handle);
            ++cases;
        }
    }
    std::cout << "PASS production JNI working input: cases=" << cases
              << "; exact_bits; full_source_dimensions; AE_off_full_statistics; integer_binning; float_routes\n";
}
}

int main(int argc, char** argv) {
    try {
        require(argc == 6, "resources, temp directory, synthetic precise/integer/legacy-float DNGs required");
        JNINativeInterface_ table{};
        table.GetStringUTFChars = cancellable_get_string;
        table.ReleaseStringUTFChars = release_string; table.NewStringUTF = new_string;
        table.ExceptionCheck = exception_check; table.ExceptionClear = exception_clear;
        table.FindClass = find_class; table.GetStaticFieldID = get_field;
        table.GetStaticMethodID = get_method; table.GetStaticObjectField = get_object;
        table.CallStaticObjectMethodV = allocate_bitmap;
        JNIEnv env{&table};
        const jlong engine = CALL(create)(&env, nullptr, text(argv[1]), text(argv[2]));
        require(engine != 0, "create production engine");
        // Keep ordinal checkpoints reproducible. LibRaw's parallel progress
        // callback count depends on worker scheduling; the native flag itself
        // is still tested from another thread below.
        configure_raw_threads();
        setenv("SPEKTRALAB_RAW_THREADS", "1", 1);
        const auto path = text(argv[3]);
        const auto params = text(R"({"input_color_space":"ProPhoto RGB","input_cctf_decoding":false,"output_color_space":"ProPhoto RGB","output_cctf_encoding":true,"product_defaults":true,"x_no_film":true,"x_raw_development":"neutral"})");
        const auto* native_engine = reinterpret_cast<spk_engine*>(engine);

        const jlong old = CALL(openRaw)(&env, nullptr, engine, path, 0, 0, params, 0, 0);
        require(old != 0, "old RAW ABI stays usable");
        auto* previous = reinterpret_cast<Session*>(old);
        const auto previous_rgb = previous->small_rgb;
        require(CALL(renderReuse)(&env, nullptr, old, text("live"), nullptr) != nullptr,
                "prepare previous editor cache");
        const auto previous_cache = previous->last_float;
        const int previous_sessions = g_sessions.at(const_cast<spk_engine*>(native_engine));

        fail_new_size.store(sizeof(Session));
        require(CALL(openRaw)(&env, nullptr, engine, path, 0, 0, params, 0, 0) == 0 &&
                failed_wrapper_new.load() && fail_new_size.load() == 0 &&
                t_error.find("out of memory") != std::string::npos && !CALL(lastWasCancelled)(&env, nullptr),
                "wrapper allocation failure is classified as OOM");
        require(g_sessions.at(const_cast<spk_engine*>(native_engine)) == previous_sessions &&
                previous->small_rgb == previous_rgb && previous->last_float == previous_cache,
                "wrapper OOM preserves prior session and cache");
        require(CALL(repack)(&env, nullptr, old, nullptr) != nullptr,
                "prior session remains usable after wrapper OOM");

        const jlong token = CALL(createRawOpenCancellation)(&env, nullptr);
        require(token != 0, "allocate RAW token");
        const jlong fresh = CALL(openRawCancellableV1)(&env, nullptr, engine, path, 0, 0, params, 0, 0, token);
        require(fresh != 0, "uncancelled versioned ABI opens");
        require(reinterpret_cast<Session*>(fresh)->small_rgb == previous_rgb,
                "uncancelled new ABI preserves every legacy source float");
        CALL(release)(&env, nullptr, fresh);
        CALL(cancelRawOpen)(&env, nullptr, token);
        require(CALL(openRawCancellableV1)(&env, nullptr, engine, text("missing-source.dng"),
                    0, 0, params, 0, 0, token) == 0,
                "pre-cancel rejects before reading missing source");
        require(CALL(lastWasCancelled)(&env, nullptr) && t_error == "RAW open cancelled",
                "pre-cancel has explicit cancellation classification");
        CALL(releaseRawOpenCancellation)(&env, nullptr, token);
        require(CALL(openRawCancellableV1)(&env, nullptr, engine, path, 0, 0, params, 0, 0, 0) == 0 &&
                !CALL(lastWasCancelled)(&env, nullptr), "null token is an API error, not cancellation");

        // Measure the actual production callback boundaries, then cancel inside
        // inspect, float decode and after GPU candidate creation deterministically.
        int inspect_polls = 0, decode_polls = 0, total_polls = 0;
        auto metadata = std::make_unique<spk::io::detail::CameraLibRaw>();
        require(metadata->open_file(argv[3]) == LIBRAW_SUCCESS, "metadata probe");
        const auto plan = spk::android::inspect_raw_precision(argv[3], *metadata, false,
            [&] { ++inspect_polls; return false; });
        require(plan.mode == "float_linear_v1", "fixture exercises precision path");
        spk::io::DecodedRaw decoded;
        std::string error;
        require(spk::android::decode_raw_precision(argv[3], plan, nullptr, 0, decoded, error, nullptr,
            [&] { ++decode_polls; return false; }), "measure full precision decode");
        const jlong measured = open_raw(&env, engine, path, 0, 0, params, 0, 0,
            [&] { ++total_polls; return false; });
        require(measured != 0, "measure actual JNI open");
        CALL(release)(&env, nullptr, measured);
        require(total_polls == inspect_polls + decode_polls + 8 && decode_polls > 4,
                "known safe-stage and in-decode callback boundaries");
        const int decode_start = 3 + inspect_polls;
        const std::set<int> stops = {1, 2, 3, 2 + inspect_polls,
            decode_start + 1, decode_start + decode_polls / 2,
            decode_start + decode_polls, total_polls - 3, total_polls - 2,
            total_polls - 1, total_polls};
        for (const int stop : stops) {
            int polls = 0;
            require(open_raw(&env, engine, path, 0, 0, params, 0, 0,
                [&] { return ++polls == stop; }) == 0, "cancelled candidate is never published");
            require(polls == stop && CALL(lastWasCancelled)(&env, nullptr),
                    "exact stage cancellation reaches the formal JNI error boundary");
            require(g_sessions.at(const_cast<spk_engine*>(native_engine)) == previous_sessions &&
                    previous->small_rgb == previous_rgb && previous->last_float == previous_cache,
                    "late cancellation releases candidate and preserves previous cache");
        }

        // Public token entry, concurrent cancellation, and release only after
        // the blocking native call returned. The shim gates a source-path read.
        const jlong concurrent = CALL(createRawOpenCancellation)(&env, nullptr);
        require(concurrent != 0, "allocate concurrent RAW token");
        watched_path = path;
        jlong result = -1;
        bool classified = false;
        std::thread worker([&] {
            result = CALL(openRawCancellableV1)(&env, nullptr, engine, path, 0, 0, params, 0, 0, concurrent);
            classified = CALL(lastWasCancelled)(&env, nullptr);
        });
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        while (!path_entered.load(std::memory_order_acquire)) {
            if (std::chrono::steady_clock::now() > deadline) std::terminate();
            std::this_thread::yield();
        }
        CALL(cancelRawOpen)(&env, nullptr, concurrent);
        signal_posted.store(true, std::memory_order_release);
        worker.join();
        watched_path = nullptr;
        CALL(releaseRawOpenCancellation)(&env, nullptr, concurrent);
        require(result == 0 && classified, "concurrent RAW cancellation returns cancelled zero handle");
        require(g_sessions.at(const_cast<spk_engine*>(native_engine)) == previous_sessions,
                "concurrent cancellation leaked no session");
        require(CALL(repack)(&env, nullptr, old, nullptr) != nullptr && previous->last_float == previous_cache,
                "old editor remains reusable after failed replacement");

        int legacy_stages = 0;
        for (int argument = 4; argument < 6; ++argument) {
            const char* expected_mode = argument == 4 ? "integer_compatible16" : "float_legacy_quantized";
            const auto legacy_path = text(argv[argument]);
            auto probe = std::make_unique<spk::io::detail::CameraLibRaw>();
            require(probe->open_file(argv[argument]) == LIBRAW_SUCCESS, "open compatibility fixture");
            int metadata_polls = 0, processing_polls = 0, open_polls = 0;
            const auto legacy_plan = spk::android::inspect_raw_precision(argv[argument], *probe, false,
                [&] { ++metadata_polls; return false; });
            require(legacy_plan.mode == expected_mode, "fixture exercises intended compatibility policy");
            spk::io::DecodedRaw reference;
            require(spk::android::decode_raw_precision(argv[argument], legacy_plan, nullptr, 0, reference,
                error, nullptr, [&] { ++processing_polls; return false; }), "compatibility progress baseline");
            require(processing_polls > 6, "compatibility branch exposes in-decode progress");
            const jlong compatibility_old = CALL(openRaw)(&env, nullptr, engine, legacy_path, 0, 0, params, 0, 0);
            require(compatibility_old != 0 && reinterpret_cast<Session*>(compatibility_old)->small_rgb == reference.rgb,
                    "old ABI still preserves compatibility decoder source bytes");
            CALL(release)(&env, nullptr, compatibility_old);
            const jlong compatibility_token = CALL(createRawOpenCancellation)(&env, nullptr);
            const jlong compatibility_new = CALL(openRawCancellableV1)(&env, nullptr, engine, legacy_path,
                0, 0, params, 0, 0, compatibility_token);
            require(compatibility_new != 0 && reinterpret_cast<Session*>(compatibility_new)->small_rgb == reference.rgb,
                    "cancellable ABI preserves compatibility decoder source bytes");
            CALL(release)(&env, nullptr, compatibility_new);
            CALL(releaseRawOpenCancellation)(&env, nullptr, compatibility_token);
            const jlong counted = open_raw(&env, engine, legacy_path, 0, 0, params, 0, 0,
                [&] { ++open_polls; return false; });
            require(counted != 0, "measure compatibility JNI progress");
            CALL(release)(&env, nullptr, counted);
            const int start = 3 + metadata_polls;
            const int decode_end = open_polls - 5;
            require(decode_end > start + 6, "compatibility in-decode progress forwarded through JNI");
            const std::set<int> legacy_stops = {start + 2, start + (decode_end - start) / 2,
                decode_end, open_polls - 2, open_polls - 1, open_polls};
            for (const int stop : legacy_stops) {
                int polls = 0;
                require(open_raw(&env, engine, legacy_path, 0, 0, params, 0, 0,
                    [&] { return ++polls == stop; }) == 0, "compatibility cancellation returns no candidate");
                require(polls == stop && CALL(lastWasCancelled)(&env, nullptr) && t_error == "RAW open cancelled",
                        "LibRaw cancellation retains cancelled classification through dispatcher and JNI");
                require(g_sessions.at(const_cast<spk_engine*>(native_engine)) == previous_sessions &&
                        previous->small_rgb == previous_rgb && previous->last_float == previous_cache,
                        "compatibility cancellation preserves previous editor and frees candidate");
            }
            legacy_stages += int(legacy_stops.size());
            if (argument == 4) {
                int polls = 0;
                require(open_raw(&env, engine, legacy_path, 8, 0, params, 0, 0,
                    [&] { return ++polls == start + 2; }) == 0 && CALL(lastWasCancelled)(&env, nullptr),
                        "cancel while the binned RAW budget is active");
                spk::io::DecodedRaw after_cancel;
                require(spk::android::decode_raw_precision(argv[argument], legacy_plan, nullptr, 0, after_cancel, error) &&
                        after_cancel.width == reference.width && after_cancel.height == reference.height &&
                        after_cancel.rgb == reference.rgb, "cancelled JNI resets thread-local half-size budget");
            }
            std::cout << "PASS " << expected_mode << ": source ABI parity; " << legacy_stops.size()
                      << " cancellation stages (workers=1,inspect=" << metadata_polls << ",standalone_decode=" << processing_polls
                      << ",jni_decode=" << decode_end - start
                      << ",total=" << open_polls << ")\n";
        }

        const jlong retry_token = CALL(createRawOpenCancellation)(&env, nullptr);
        const jlong retry = CALL(openRawCancellableV1)(&env, nullptr, engine, path, 0, 0, params, 0, 0, retry_token);
        require(retry != 0 && reinterpret_cast<Session*>(retry)->small_rgb == previous_rgb,
                "retry token is independent and source bytes remain exact");
        CALL(release)(&env, nullptr, retry);
        CALL(releaseRawOpenCancellation)(&env, nullptr, retry_token);
        working_input_tests(&env, engine, argv);
        CALL(release)(&env, nullptr, old);
        require(g_sessions.at(const_cast<spk_engine*>(native_engine)) == 0, "all JNI sessions released");
        CALL(destroy)(&env, nullptr, engine);
        std::cout << "PASS production RAW JNI cancellation: old/new ABI source parity; pre-cancel; "
                  << stops.size() << " deterministic stages (inspect=" << inspect_polls
                  << ",decode=" << decode_polls << ",total=" << total_polls
                  << "); concurrent public token; candidate cleanup; old cache; retry\n";
        require(legacy_stages > 0, "compatibility cancellation stages executed");
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}

// Co-created with GPT (OpenAI), 2026-11-08. See NOTICE for project terms.
// Exercises the production probe's submission and destruction with a fake
// Vulkan function table. No driver, large allocation, or GPU hang is needed.
#include <cstdio>
#include <stdexcept>
#include <string>
#include <type_traits>

#include "../../app/src/main/cpp/storage_probe.cpp"

namespace spk::android {
namespace {

struct ProbeLifetimeTest {
    struct State {
        VkResult submit = VK_SUCCESS, wait = VK_SUCCESS, idle = VK_SUCCESS;
        bool in_flight = false;
        int waits = 0, idles = 0, destroyed = 0, buffers = 0, memories = 0;
        int devices = 0, instances = 0, unmapped = 0;
    };
    static inline State* state = nullptr;

    static void require(bool condition, const char* message) {
        if (!condition) throw std::runtime_error(message);
    }
    template <typename T> static T handle(uintptr_t value) {
        if constexpr (std::is_pointer_v<T>) return reinterpret_cast<T>(value);
        else return static_cast<T>(value);
    }
    static void destroy() {
        require(!state->in_flight, "destroyed an object referenced by pending work");
        ++state->destroyed;
    }
    static VKAPI_ATTR VkResult VKAPI_CALL submit(VkQueue, uint32_t, const VkSubmitInfo*, VkFence) {
        state->in_flight = state->submit == VK_SUCCESS || state->submit == VK_ERROR_DEVICE_LOST;
        return state->submit;
    }
    static VKAPI_ATTR VkResult VKAPI_CALL wait(VkDevice, uint32_t, const VkFence*, VkBool32, uint64_t timeout) {
        require(timeout == kTimeoutNs, "unexpected diagnostic timeout");
        ++state->waits;
        if (state->wait == VK_SUCCESS || state->wait == VK_ERROR_DEVICE_LOST) state->in_flight = false;
        return state->wait;
    }
    static VKAPI_ATTR VkResult VKAPI_CALL idle(VkDevice) {
        ++state->idles;
        if (state->idle == VK_SUCCESS || state->idle == VK_ERROR_DEVICE_LOST) state->in_flight = false;
        return state->idle;
    }
    static VKAPI_ATTR void VKAPI_CALL unmap(VkDevice, VkDeviceMemory) { destroy(); ++state->unmapped; }
    static VKAPI_ATTR void VKAPI_CALL buffer(VkDevice, VkBuffer, const VkAllocationCallbacks*) { destroy(); ++state->buffers; }
    static VKAPI_ATTR void VKAPI_CALL memory(VkDevice, VkDeviceMemory, const VkAllocationCallbacks*) { destroy(); ++state->memories; }
    static VKAPI_ATTR void VKAPI_CALL device(VkDevice, const VkAllocationCallbacks*) { destroy(); ++state->devices; }
    static VKAPI_ATTR void VKAPI_CALL instance(VkInstance, const VkAllocationCallbacks*) { destroy(); ++state->instances; }
    template <typename T> static VKAPI_ATTR void VKAPI_CALL object(VkDevice, T, const VkAllocationCallbacks*) { destroy(); }

    static void seed(Probe& probe) {
        probe.instance_ = handle<VkInstance>(1);
        probe.device_ = handle<VkDevice>(2);
        probe.queue_ = handle<VkQueue>(3);
        probe.fence_ = handle<VkFence>(4);
        probe.pool_ = handle<VkCommandPool>(5);
        probe.command_ = handle<VkCommandBuffer>(6);
        probe.descriptors_ = handle<VkDescriptorPool>(7);
        probe.pipeline_ = handle<VkPipeline>(8);
        probe.layout_ = handle<VkPipelineLayout>(9);
        probe.set_layout_ = handle<VkDescriptorSetLayout>(10);
        int value = 20;
        for (Buffer* b : {&probe.big_, &probe.res_, &probe.host_}) {
            b->buffer = handle<VkBuffer>(value++);
            b->memory = handle<VkDeviceMemory>(value++);
        }
        probe.res_.mapped = reinterpret_cast<void*>(100);
        probe.host_.mapped = reinterpret_cast<void*>(101);
        auto& f = probe.f_;
        f.vkQueueSubmit = submit; f.vkWaitForFences = wait; f.vkDeviceWaitIdle = idle;
        f.vkUnmapMemory = unmap; f.vkDestroyBuffer = buffer; f.vkFreeMemory = memory;
        f.vkDestroyDevice = device; f.vkDestroyInstance = instance;
        f.vkDestroyFence = object<VkFence>; f.vkDestroyCommandPool = object<VkCommandPool>;
        f.vkDestroyDescriptorPool = object<VkDescriptorPool>; f.vkDestroyPipeline = object<VkPipeline>;
        f.vkDestroyPipelineLayout = object<VkPipelineLayout>;
        f.vkDestroyDescriptorSetLayout = object<VkDescriptorSetLayout>;
    }

    static void settled(const char* name, VkResult submit_result, VkResult wait_result, VkResult idle_result) {
        State s;
        s.submit = submit_result; s.wait = wait_result; s.idle = idle_result; state = &s;
        const bool submitted = submit_result == VK_SUCCESS;
        const VkResult observed = submitted ? wait_result : submit_result;
        const bool needs_idle = submit_result == VK_ERROR_DEVICE_LOST ||
            (submitted && wait_result != VK_SUCCESS && wait_result != VK_ERROR_DEVICE_LOST);
        std::string log;
        {
            Probe probe(log);
            seed(probe);
            bool lost = false;
            const bool ok = probe.submit_candidate(4096, lost);
            require(ok == (observed == VK_SUCCESS), "incorrect candidate verdict");
            require(lost == (observed == VK_ERROR_DEVICE_LOST), "timeout/error mislabeled device lost");
            require(probe.pending_ == needs_idle, "incorrect pending state");
            require(s.destroyed == 0, "submission destroyed resources");
            require(probe.close_device(), "settled close failed");
            require(s.waits == int(submitted) && s.idles == int(needs_idle), "incorrect wait sequence");
            require(s.buffers == 3 && s.memories == 3 && s.unmapped == 2 && s.devices == 1, "incomplete cleanup");
            const int destroyed = s.destroyed;
            require(probe.close_device() && s.destroyed == destroyed, "double cleanup");
        }
        require(s.instances == 1 && s.devices == 1, "parent/child cleanup mismatch");
        std::printf("PASS %s\n", name);
    }

    static void unresolved(bool retry) {
        State s;
        s.wait = VK_TIMEOUT; s.idle = VK_ERROR_OUT_OF_HOST_MEMORY; state = &s;
        std::string log;
        {
            Probe probe(log);
            seed(probe);
            bool lost = false;
            require(!probe.submit_candidate(4096, lost) && !lost, "timeout verdict");
            require(!probe.close_device(), "unresolved close reported success");
            require(s.destroyed == 0 && probe.pending_, "unresolved work lost its owners");
            require(!probe.open_device(), "replaced a pending device");
            if (retry) {
                s.idle = VK_SUCCESS;
                require(probe.close_device(), "retry cleanup failed");
            }
        }
        require(retry ? (s.devices == 1 && s.instances == 1) : s.destroyed == 0,
                "destructor destroyed an unresolved graph or leaked a settled graph");
        std::printf("PASS %s\n", retry ? "unresolved-then-complete" : "unresolved-destructor-retains-instance");
    }

    static int run() {
        settled("normal-completion", VK_SUCCESS, VK_SUCCESS, VK_SUCCESS);
        settled("timeout-then-complete", VK_SUCCESS, VK_TIMEOUT, VK_SUCCESS);
        settled("timeout-then-device-lost", VK_SUCCESS, VK_TIMEOUT, VK_ERROR_DEVICE_LOST);
        settled("wait-error-then-complete", VK_SUCCESS, VK_ERROR_OUT_OF_HOST_MEMORY, VK_SUCCESS);
        settled("wait-device-lost", VK_SUCCESS, VK_ERROR_DEVICE_LOST, VK_SUCCESS);
        settled("submit-error", VK_ERROR_OUT_OF_DEVICE_MEMORY, VK_SUCCESS, VK_SUCCESS);
        settled("submit-device-lost", VK_ERROR_DEVICE_LOST, VK_SUCCESS, VK_ERROR_DEVICE_LOST);
        unresolved(true);
        unresolved(false);
        return 0;
    }
};

} // namespace
} // namespace spk::android

int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "--smoke") {
        std::string log;
        const uint64_t verified = spk::android::probe_storage_range(4096, log);
        std::printf("4 KiB Vulkan smoke: verified=%llu; %s\n",
                    static_cast<unsigned long long>(verified), log.c_str());
        return verified == 4096 ? 0 : 1;
    }
    if (argc != 1) return 2;
    try { return spk::android::ProbeLifetimeTest::run(); }
    catch (const std::exception& e) { std::fprintf(stderr, "FAIL %s\n", e.what()); return 1; }
}

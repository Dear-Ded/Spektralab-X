// Co-created with GPT (OpenAI), 2026-11-11. Standalone Android executable, no APK.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <android/api-level.h>
#include <dlfcn.h>
#include <unistd.h>
#include <cstdio>
#include <vector>

int main() {
#if defined(__aarch64__)
    const char* abi = "arm64-v8a";
#elif defined(__x86_64__)
    const char* abi = "x86_64";
#else
    const char* abi = "other";
#endif
    std::printf("abi=%s android_api=%d page_size=%ld\n", abi,
                android_get_device_api_level(), sysconf(_SC_PAGESIZE));
    void* loader = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!loader) { std::printf("vulkan_loader_error=%s\n", dlerror()); return 2; }
    auto get = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(loader, "vkGetInstanceProcAddr"));
    if (!get) { std::puts("vkGetInstanceProcAddr absent"); dlclose(loader); return 3; }
    auto create = reinterpret_cast<PFN_vkCreateInstance>(get(VK_NULL_HANDLE, "vkCreateInstance"));
    if (!create) { dlclose(loader); return 3; }
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "android-cloud-native-probe";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = create(&ci, nullptr, &instance);
    std::printf("vkCreateInstance=%d\n", result);
    if (result != VK_SUCCESS) { dlclose(loader); return 4; }
    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(get(instance, "vkDestroyInstance"));
    auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(get(instance, "vkEnumeratePhysicalDevices"));
    auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(get(instance, "vkGetPhysicalDeviceProperties"));
    if (!destroy || !enumerate || !properties) {
        if (destroy) destroy(instance, nullptr);
        dlclose(loader);
        return 5;
    }
    uint32_t count = 0;
    result = enumerate(instance, &count, nullptr);
    if (result != VK_SUCCESS || count == 0 || count > 64) {
        std::printf("enumerate=%d count=%u\n", result, count);
        destroy(instance, nullptr); dlclose(loader); return 6;
    }
    std::vector<VkPhysicalDevice> devices(count);
    result = enumerate(instance, &count, devices.data());
    if (result != VK_SUCCESS) { destroy(instance, nullptr); dlclose(loader); return 7; }
    for (uint32_t i = 0; i < count; ++i) {
        VkPhysicalDeviceProperties p{};
        properties(devices[i], &p);
        std::printf("device=%s type=%u vendor=%04x device_id=%04x api=%u.%u.%u driver=%u "
                    "maxStorageBufferRange=%u maxComputeWorkGroupInvocations=%u "
                    "maxComputeWorkGroupSizeX=%u perStageSSBO=%u descriptorSetSSBO=%u "
                    "storageOffsetAlignment=%llu nonCoherentAtomSize=%llu\n",
                    p.deviceName, static_cast<unsigned>(p.deviceType), p.vendorID, p.deviceID,
                    VK_VERSION_MAJOR(p.apiVersion), VK_VERSION_MINOR(p.apiVersion), VK_VERSION_PATCH(p.apiVersion),
                    p.driverVersion, p.limits.maxStorageBufferRange,
                    p.limits.maxComputeWorkGroupInvocations, p.limits.maxComputeWorkGroupSize[0],
                    p.limits.maxPerStageDescriptorStorageBuffers, p.limits.maxDescriptorSetStorageBuffers,
                    static_cast<unsigned long long>(p.limits.minStorageBufferOffsetAlignment),
                    static_cast<unsigned long long>(p.limits.nonCoherentAtomSize));
    }
    destroy(instance, nullptr);
    dlclose(loader);
    return 0;
}

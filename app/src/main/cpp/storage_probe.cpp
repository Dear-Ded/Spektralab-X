// storage_probe.cpp -- how large a storage buffer this GPU really addresses.
//
// The engine sizes every plane against maxStorageBufferRange, and so does the
// app (DeviceProfile.maxPixels). Some drivers report a range they do not
// honour: on an Adreno 825 a 2.25 GiB binding "updated" without error while
// every write past 2,149,318,656 B was dropped (SAINTTaiYi/SpektraLab-Android
// fa199b6), and a render that writes out of range ends in VK_ERROR_DEVICE_LOST.
//
// So the range is measured, once per driver, on a VkInstance and VkDevice of
// its own (a probe that loses its device does not take the engine's with it):
// for each candidate size, 32 canaries are written through a storage binding
// of the whole buffer, from its last word down to its first, then read back
// twice -- by the same shader through the binding, and by vkCmdCopyBuffer at
// the true offsets. Only when both agree did the writes land where they were
// addressed; a driver that wraps or clamps addresses passes the first read
// and fails the second. The largest size that passes is the answer.
//
// The canary kernel is storage_probe.comp, compiled into storage_probe_spv.inc.
#include "storage_probe.hpp"

#include <algorithm>
#include <cstring>
#include <vector>

#include "volk.h"
#include "storage_probe_spv.inc"

namespace spk::android {
namespace {

constexpr uint32_t kCanaries = 32;
constexpr uint64_t kTimeoutNs = 2'000'000'000ull;   // under Adreno's 2 s GPU watchdog

// Every entry point through vkGetInstanceProcAddr / vkGetDeviceProcAddr, so
// volk's process-wide table (the engine's) is never touched.
struct Fn {
#define SPK_FN(name) PFN_##name name = nullptr;
    SPK_FN(vkDestroyInstance) SPK_FN(vkEnumeratePhysicalDevices) SPK_FN(vkGetPhysicalDeviceProperties)
    SPK_FN(vkGetPhysicalDeviceQueueFamilyProperties) SPK_FN(vkGetPhysicalDeviceMemoryProperties)
    SPK_FN(vkCreateDevice) SPK_FN(vkGetDeviceProcAddr)
    SPK_FN(vkDestroyDevice) SPK_FN(vkGetDeviceQueue) SPK_FN(vkCreateBuffer) SPK_FN(vkDestroyBuffer)
    SPK_FN(vkGetBufferMemoryRequirements) SPK_FN(vkAllocateMemory) SPK_FN(vkFreeMemory)
    SPK_FN(vkBindBufferMemory) SPK_FN(vkMapMemory) SPK_FN(vkUnmapMemory)
    SPK_FN(vkCreateShaderModule) SPK_FN(vkDestroyShaderModule)
    SPK_FN(vkCreateDescriptorSetLayout) SPK_FN(vkDestroyDescriptorSetLayout)
    SPK_FN(vkCreatePipelineLayout) SPK_FN(vkDestroyPipelineLayout)
    SPK_FN(vkCreateComputePipelines) SPK_FN(vkDestroyPipeline)
    SPK_FN(vkCreateDescriptorPool) SPK_FN(vkDestroyDescriptorPool) SPK_FN(vkResetDescriptorPool)
    SPK_FN(vkAllocateDescriptorSets) SPK_FN(vkUpdateDescriptorSets)
    SPK_FN(vkCreateCommandPool) SPK_FN(vkDestroyCommandPool) SPK_FN(vkAllocateCommandBuffers)
    SPK_FN(vkResetCommandBuffer) SPK_FN(vkBeginCommandBuffer) SPK_FN(vkEndCommandBuffer)
    SPK_FN(vkCmdBindPipeline) SPK_FN(vkCmdBindDescriptorSets) SPK_FN(vkCmdPushConstants)
    SPK_FN(vkCmdDispatch) SPK_FN(vkCmdPipelineBarrier) SPK_FN(vkCmdFillBuffer) SPK_FN(vkCmdCopyBuffer)
    SPK_FN(vkQueueSubmit) SPK_FN(vkCreateFence) SPK_FN(vkDestroyFence) SPK_FN(vkWaitForFences)
    SPK_FN(vkResetFences)
#undef SPK_FN
};

struct Buffer { VkBuffer buffer = VK_NULL_HANDLE; VkDeviceMemory memory = VK_NULL_HANDLE; void* mapped = nullptr; };

class Probe {
public:
    explicit Probe(std::string& log) : log_(log) {}
    ~Probe() { teardown(); }

    bool init() {
        if (volkInitialize() != VK_SUCCESS) return say("no Vulkan loader");
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.apiVersion = VK_API_VERSION_1_0;
        VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ci.pApplicationInfo = &app;
        auto create = reinterpret_cast<PFN_vkCreateInstance>(vkGetInstanceProcAddr(nullptr, "vkCreateInstance"));
        if (!create || create(&ci, nullptr, &instance_) != VK_SUCCESS) return say("vkCreateInstance failed");
#define SPK_I(name) f_.name = reinterpret_cast<PFN_##name>(vkGetInstanceProcAddr(instance_, #name)); if (!f_.name) return say(#name " missing");
        SPK_I(vkDestroyInstance) SPK_I(vkEnumeratePhysicalDevices) SPK_I(vkGetPhysicalDeviceProperties)
        SPK_I(vkGetPhysicalDeviceQueueFamilyProperties) SPK_I(vkGetPhysicalDeviceMemoryProperties)
        SPK_I(vkCreateDevice) SPK_I(vkGetDeviceProcAddr)
#undef SPK_I
        uint32_t n = 0;
        if (f_.vkEnumeratePhysicalDevices(instance_, &n, nullptr) != VK_SUCCESS || !n) return say("no Vulkan device");
        std::vector<VkPhysicalDevice> devices(n);
        f_.vkEnumeratePhysicalDevices(instance_, &n, devices.data());
        // The first device with a compute queue, as the engine picks it.
        for (VkPhysicalDevice d : devices) {
            uint32_t qn = 0;
            f_.vkGetPhysicalDeviceQueueFamilyProperties(d, &qn, nullptr);
            std::vector<VkQueueFamilyProperties> q(qn);
            f_.vkGetPhysicalDeviceQueueFamilyProperties(d, &qn, q.data());
            for (uint32_t i = 0; i < qn; ++i)
                if (q[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { physical_ = d; family_ = i; break; }
            if (physical_) break;
        }
        if (!physical_) return say("no compute queue");
        f_.vkGetPhysicalDeviceProperties(physical_, &props_);
        f_.vkGetPhysicalDeviceMemoryProperties(physical_, &mem_);
        return true;
    }

    VkDeviceSize advertised() const { return props_.limits.maxStorageBufferRange; }

    // A fresh device per candidate: one that a probe lost is not reused.
    bool open_device() {
        const float priority = 1.0f;
        VkDeviceQueueCreateInfo q{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        q.queueFamilyIndex = family_;
        q.queueCount = 1;
        q.pQueuePriorities = &priority;
        VkDeviceCreateInfo ci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        ci.queueCreateInfoCount = 1;
        ci.pQueueCreateInfos = &q;
        if (f_.vkCreateDevice(physical_, &ci, nullptr, &device_) != VK_SUCCESS) return say("vkCreateDevice failed");
#define SPK_D(name) f_.name = reinterpret_cast<PFN_##name>(f_.vkGetDeviceProcAddr(device_, #name)); if (!f_.name) return say(#name " missing");
        SPK_D(vkDestroyDevice) SPK_D(vkGetDeviceQueue) SPK_D(vkCreateBuffer) SPK_D(vkDestroyBuffer)
        SPK_D(vkGetBufferMemoryRequirements) SPK_D(vkAllocateMemory) SPK_D(vkFreeMemory)
        SPK_D(vkBindBufferMemory) SPK_D(vkMapMemory) SPK_D(vkUnmapMemory)
        SPK_D(vkCreateShaderModule) SPK_D(vkDestroyShaderModule)
        SPK_D(vkCreateDescriptorSetLayout) SPK_D(vkDestroyDescriptorSetLayout)
        SPK_D(vkCreatePipelineLayout) SPK_D(vkDestroyPipelineLayout)
        SPK_D(vkCreateComputePipelines) SPK_D(vkDestroyPipeline)
        SPK_D(vkCreateDescriptorPool) SPK_D(vkDestroyDescriptorPool) SPK_D(vkResetDescriptorPool)
        SPK_D(vkAllocateDescriptorSets) SPK_D(vkUpdateDescriptorSets)
        SPK_D(vkCreateCommandPool) SPK_D(vkDestroyCommandPool) SPK_D(vkAllocateCommandBuffers)
        SPK_D(vkResetCommandBuffer) SPK_D(vkBeginCommandBuffer) SPK_D(vkEndCommandBuffer)
        SPK_D(vkCmdBindPipeline) SPK_D(vkCmdBindDescriptorSets) SPK_D(vkCmdPushConstants)
        SPK_D(vkCmdDispatch) SPK_D(vkCmdPipelineBarrier) SPK_D(vkCmdFillBuffer) SPK_D(vkCmdCopyBuffer)
        SPK_D(vkQueueSubmit) SPK_D(vkCreateFence) SPK_D(vkDestroyFence) SPK_D(vkWaitForFences)
        SPK_D(vkResetFences)
#undef SPK_D
        f_.vkGetDeviceQueue(device_, family_, 0, &queue_);
        return build_pipeline();
    }

    // One candidate. False when the canaries did not all land (or anything
    // failed on the way); `lost` when the device must not be used again.
    bool test(VkDeviceSize bytes, bool& lost) {
        lost = false;
        Buffer big, res, host;
        const bool ok = run(bytes, big, res, host, lost);
        release(big); release(res); release(host);
        return ok;
    }

    void close_device() {
        if (!device_) return;
        if (fence_) f_.vkDestroyFence(device_, fence_, nullptr);
        if (pool_) f_.vkDestroyCommandPool(device_, pool_, nullptr);
        if (descriptors_) f_.vkDestroyDescriptorPool(device_, descriptors_, nullptr);
        if (pipeline_) f_.vkDestroyPipeline(device_, pipeline_, nullptr);
        if (layout_) f_.vkDestroyPipelineLayout(device_, layout_, nullptr);
        if (set_layout_) f_.vkDestroyDescriptorSetLayout(device_, set_layout_, nullptr);
        f_.vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE; fence_ = VK_NULL_HANDLE; pool_ = VK_NULL_HANDLE; descriptors_ = VK_NULL_HANDLE;
        pipeline_ = VK_NULL_HANDLE; layout_ = VK_NULL_HANDLE; set_layout_ = VK_NULL_HANDLE; command_ = VK_NULL_HANDLE;
    }

private:
    bool say(const std::string& m) { log_ += (log_.empty() ? "" : "; ") + m; return false; }

    void teardown() {
        close_device();
        if (instance_) f_.vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }

    int memory_type(uint32_t bits, VkMemoryPropertyFlags want) const {
        for (uint32_t i = 0; i < mem_.memoryTypeCount; ++i)
            if ((bits & (1u << i)) && (mem_.memoryTypes[i].propertyFlags & want) == want) return int(i);
        return -1;
    }

    bool make(VkDeviceSize bytes, VkBufferUsageFlags usage, VkMemoryPropertyFlags want, Buffer& b) {
        VkBufferCreateInfo ci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        ci.size = bytes;
        ci.usage = usage;
        ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (f_.vkCreateBuffer(device_, &ci, nullptr, &b.buffer) != VK_SUCCESS) return say("vkCreateBuffer failed");
        VkMemoryRequirements req{};
        f_.vkGetBufferMemoryRequirements(device_, b.buffer, &req);
        const int type = memory_type(req.memoryTypeBits, want);
        if (type < 0) return say("no suitable memory type");
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ai.allocationSize = req.size;
        ai.memoryTypeIndex = uint32_t(type);
        if (f_.vkAllocateMemory(device_, &ai, nullptr, &b.memory) != VK_SUCCESS)
            return say("cannot allocate " + std::to_string(bytes >> 20) + " MiB");
        if (f_.vkBindBufferMemory(device_, b.buffer, b.memory, 0) != VK_SUCCESS) return say("vkBindBufferMemory failed");
        if (want & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) {
            if (f_.vkMapMemory(device_, b.memory, 0, VK_WHOLE_SIZE, 0, &b.mapped) != VK_SUCCESS) return say("vkMapMemory failed");
            std::memset(b.mapped, 0, size_t(bytes));
        }
        return true;
    }

    void release(Buffer& b) {
        if (!device_) return;
        if (b.mapped) f_.vkUnmapMemory(device_, b.memory);
        if (b.buffer) f_.vkDestroyBuffer(device_, b.buffer, nullptr);
        if (b.memory) f_.vkFreeMemory(device_, b.memory, nullptr);
        b = Buffer{};
    }

    bool build_pipeline() {
        VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        sm.codeSize = sizeof spk_storage_probe_spv;
        sm.pCode = spk_storage_probe_spv;
        VkShaderModule module = VK_NULL_HANDLE;
        if (f_.vkCreateShaderModule(device_, &sm, nullptr, &module) != VK_SUCCESS) return say("vkCreateShaderModule failed");
        VkDescriptorSetLayoutBinding bindings[2]{};
        for (uint32_t i = 0; i < 2; ++i) {
            bindings[i].binding = i;
            bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        dl.bindingCount = 2;
        dl.pBindings = bindings;
        VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, 12};
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pl.setLayoutCount = 1;
        pl.pSetLayouts = &set_layout_;
        pl.pushConstantRangeCount = 1;
        pl.pPushConstantRanges = &push;
        bool ok = f_.vkCreateDescriptorSetLayout(device_, &dl, nullptr, &set_layout_) == VK_SUCCESS &&
                  f_.vkCreatePipelineLayout(device_, &pl, nullptr, &layout_) == VK_SUCCESS;
        if (ok) {
            VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
            cp.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            cp.stage.module = module;
            cp.stage.pName = "main";
            cp.layout = layout_;
            ok = f_.vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &cp, nullptr, &pipeline_) == VK_SUCCESS;
        }
        f_.vkDestroyShaderModule(device_, module, nullptr);
        if (!ok) return say("cannot build the probe pipeline");
        VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2};
        VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        dp.maxSets = 1;
        dp.poolSizeCount = 1;
        dp.pPoolSizes = &size;
        VkCommandPoolCreateInfo cpool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cpool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        cpool.queueFamilyIndex = family_;
        VkFenceCreateInfo fc{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        if (f_.vkCreateDescriptorPool(device_, &dp, nullptr, &descriptors_) != VK_SUCCESS ||
            f_.vkCreateCommandPool(device_, &cpool, nullptr, &pool_) != VK_SUCCESS ||
            f_.vkCreateFence(device_, &fc, nullptr, &fence_) != VK_SUCCESS) return say("cannot create probe objects");
        VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ca.commandPool = pool_;
        ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ca.commandBufferCount = 1;
        if (f_.vkAllocateCommandBuffers(device_, &ca, &command_) != VK_SUCCESS) return say("cannot allocate a command buffer");
        return true;
    }

    static void barrier(const Fn& f, VkCommandBuffer cb, VkPipelineStageFlags src, VkAccessFlags sa,
                        VkPipelineStageFlags dst, VkAccessFlags da) {
        VkMemoryBarrier b{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        b.srcAccessMask = sa;
        b.dstAccessMask = da;
        f.vkCmdPipelineBarrier(cb, src, dst, 0, 1, &b, 0, nullptr, 0, nullptr);
    }

    bool run(VkDeviceSize bytes, Buffer& big, Buffer& res, Buffer& host, bool& lost) {
        const uint32_t words = uint32_t(bytes / 4);
        const VkMemoryPropertyFlags visible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        if (!make(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                  VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, big) ||
            !make(kCanaries * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, visible, res) ||
            !make(kCanaries * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, visible, host)) return false;

        f_.vkResetDescriptorPool(device_, descriptors_, 0);
        VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        da.descriptorPool = descriptors_;
        da.descriptorSetCount = 1;
        da.pSetLayouts = &set_layout_;
        VkDescriptorSet set = VK_NULL_HANDLE;
        if (f_.vkAllocateDescriptorSets(device_, &da, &set) != VK_SUCCESS) return say("cannot allocate descriptors");
        VkDescriptorBufferInfo infos[2] = {{big.buffer, 0, VK_WHOLE_SIZE}, {res.buffer, 0, VK_WHOLE_SIZE}};
        VkWriteDescriptorSet writes[2]{};
        for (uint32_t i = 0; i < 2; ++i) {
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[i].dstSet = set;
            writes[i].dstBinding = i;
            writes[i].descriptorCount = 1;
            writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[i].pBufferInfo = &infos[i];
        }
        f_.vkUpdateDescriptorSets(device_, 2, writes, 0, nullptr);

        const uint32_t salt = 0x5A17C0DEu ^ words;
        VkCommandBuffer cb = command_;
        f_.vkResetCommandBuffer(cb, 0);
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        f_.vkBeginCommandBuffer(cb, &begin);
        f_.vkCmdFillBuffer(cb, big.buffer, 0, VK_WHOLE_SIZE, 0);
        barrier(f_, cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        f_.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
        f_.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set, 0, nullptr);
        uint32_t push[3] = {words, 0u, salt};
        f_.vkCmdPushConstants(cb, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof push, push);
        f_.vkCmdDispatch(cb, 1, 1, 1);
        barrier(f_, cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);
        push[1] = 1u;
        f_.vkCmdPushConstants(cb, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof push, push);
        f_.vkCmdDispatch(cb, 1, 1, 1);
        // The second read: the copy engine at each canary's true byte offset.
        VkBufferCopy regions[kCanaries];
        for (uint32_t k = 0; k < kCanaries; ++k) {
            const uint64_t idx = uint64_t(words) - 1 - uint64_t(k) * (words / kCanaries);
            regions[k] = {VkDeviceSize(idx * 4), VkDeviceSize(k * 4), 4};
        }
        f_.vkCmdCopyBuffer(cb, big.buffer, host.buffer, kCanaries, regions);
        barrier(f_, cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
        f_.vkEndCommandBuffer(cb);

        f_.vkResetFences(device_, 1, &fence_);
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &cb;
        VkResult r = f_.vkQueueSubmit(queue_, 1, &submit, fence_);
        if (r == VK_SUCCESS) r = f_.vkWaitForFences(device_, 1, &fence_, VK_TRUE, kTimeoutNs);
        if (r != VK_SUCCESS) {
            lost = true;
            return say(std::to_string(bytes >> 20) + " MiB: submit failed (VkResult " + std::to_string(int(r)) + ")");
        }
        const auto* shader = static_cast<const uint32_t*>(res.mapped);
        const auto* copied = static_cast<const uint32_t*>(host.mapped);
        for (uint32_t k = 0; k < kCanaries; ++k) {
            const uint32_t want = (k * 2654435761u) ^ salt;
            if (shader[k] != want || copied[k] != want) {
                const uint64_t idx = uint64_t(words) - 1 - uint64_t(k) * (words / kCanaries);
                return say(std::to_string(bytes >> 20) + " MiB: write at byte " + std::to_string(idx * 4) +
                           (shader[k] == want ? " aliased" : " lost"));
            }
        }
        return true;
    }

    std::string& log_;
    Fn f_;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physical_ = VK_NULL_HANDLE;
    uint32_t family_ = 0;
    VkPhysicalDeviceProperties props_{};
    VkPhysicalDeviceMemoryProperties mem_{};
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout set_layout_ = VK_NULL_HANDLE;
    VkPipelineLayout layout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptors_ = VK_NULL_HANDLE;
    VkCommandPool pool_ = VK_NULL_HANDLE;
    VkCommandBuffer command_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
};

}  // namespace

uint64_t probe_storage_range(uint64_t ceiling, std::string& log) {
    log.clear();
    Probe probe(log);
    if (!probe.init()) return 0;
    const uint64_t advertised = probe.advertised();
    const uint64_t top = std::min(advertised, ceiling) & ~uint64_t(3);
    // Doubling from 128 MiB (the spec minimum, which every device must at
    // least claim), then the top itself.
    std::vector<uint64_t> sizes;
    for (uint64_t s = uint64_t(128) << 20; s < top; s *= 2) sizes.push_back(s);
    if (top >= 4096) sizes.push_back(top);
    uint64_t verified = 0;
    for (uint64_t s : sizes) {
        if (!probe.open_device()) break;
        bool lost = false;
        const bool ok = probe.test(s, lost);
        probe.close_device();
        if (!ok) break;   // a larger binding will not do better
        verified = s;
    }
    log += (log.empty() ? "" : "; ") + std::string("advertised ") + std::to_string(advertised >> 20) +
           " MiB, verified " + std::to_string(verified >> 20) + " MiB";
    return verified;
}

}  // namespace spk::android

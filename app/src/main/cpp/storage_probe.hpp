#pragma once

#include <cstdint>
#include <string>

namespace spk::android {

// The largest storage buffer, in bytes, up to min(maxStorageBufferRange,
// `ceiling`), whose far writes are measured to land (storage_probe.cpp).
// 0 when even 128 MiB failed or Vulkan could not be set up; `log` says why.
// Runs on its own VkInstance and VkDevice. Slow (it allocates each size
// tested): call once per driver and keep the answer.
uint64_t probe_storage_range(uint64_t ceiling, std::string& log);

}  // namespace spk::android

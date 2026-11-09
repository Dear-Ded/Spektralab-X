#include "spektrafilm/spk_engine.h"
#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <sys/resource.h>

static void memory(const char* stage) {
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    std::ifstream status("/proc/self/status");
    std::string line;
    std::cout << stage << " ru_maxrss_kib=" << usage.ru_maxrss;
    while (std::getline(status, line)) {
        if (line.rfind("VmRSS:", 0) == 0 || line.rfind("VmHWM:", 0) == 0)
            std::cout << " " << line;
    }
    std::cout << '\n';
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    memory("before_engine");
    std::unique_ptr<spk_engine, decltype(&spk_engine_destroy)> engine(
        spk_engine_create(argv[1], nullptr), spk_engine_destroy);
    memory("after_engine");
    if (!engine) {
        std::cerr << "engine_failed " << spk_last_error(nullptr) << '\n';
        return 1;
    }
    std::cout << spk_capabilities(engine.get()) << '\n';
    engine.reset();
    memory("after_destroy");
}

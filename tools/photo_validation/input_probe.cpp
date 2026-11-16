// Real-photo input conversion evidence; cloud only. Co-created with GPT (OpenAI).
#include "input_transform.hpp"
#include <cstdint>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>

int main(int argc,char** argv) try {
    if (argc!=3) throw std::runtime_error("input P6, output little-endian float RGB required");
    std::ifstream input(argv[1],std::ios::binary);
    std::string magic; uint32_t w=0,h=0,max=0;
    input >> magic >> w >> h >> max;
    if (magic!="P6" || !w || !h || w>1024 || h>1024 || max!=255 || input.get()!='\n')
        throw std::runtime_error("bounded P6 source required");
    std::ofstream output(argv[2],std::ios::binary);
    const spk::android::InputTransform transform("sRGB");
    for (uint64_t p=0;p<uint64_t(w)*h;++p) {
        uint8_t rgb[3]; input.read(reinterpret_cast<char*>(rgb),3);
        if (!input) throw std::runtime_error("short photograph");
        float linear[3];
        for (int c=0;c<3;++c) linear[c]=transform.decode(rgb[c],c);
        transform.to_prophoto(linear);
        output.write(reinterpret_cast<const char*>(linear),sizeof linear);
    }
    if (!output || input.peek()!=EOF) throw std::runtime_error("RGB evidence IO");
    std::cout << "PASS " << w << 'x' << h << " sRGB8 -> production linear ProPhoto\n";
} catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }

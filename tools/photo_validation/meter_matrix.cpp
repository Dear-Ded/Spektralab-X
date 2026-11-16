// Read the pinned resource's meter colour contract. Co-created with GPT (OpenAI).
#include "core/colour.hpp"
#include <iomanip>
#include <iostream>

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    spk::Blob blob;
    spk::Colour colour;
    spk::Mat3 matrix;
    std::string error;
    if (!blob.open(argv[1], error) || !colour.init(blob, error) ||
        !colour.matrix_RGB_to_XYZ("ProPhoto RGB", nullptr, "CAT02", matrix, error)) {
        std::cerr << error << '\n';
        return 1;
    }
    std::cout << std::setprecision(17) << "{\"ProPhoto_RGB_Y\":[" << matrix.m[1][0] << ','
              << matrix.m[1][1] << ',' << matrix.m[1][2] << "]}\n";
}

#include "libraw/libraw.h"
#include <cstdio>
#include <cstdlib>
#include <memory>

static void check(int rc, const char *stage)
{
  if (rc) { std::fprintf(stderr, "%s: %s\n", stage, libraw_strerror(rc)); std::exit(1); }
}

static void save(const char *path, const void *data, size_t bytes)
{
  FILE *out = std::fopen(path, "wb");
  if (!out || std::fwrite(data, 1, bytes, out) != bytes || std::fclose(out))
    std::exit(2);
}

int main(int argc, char **argv)
{
  if (argc != 4) return 2;
  auto raw = std::make_unique<LibRaw>();
  raw->imgdata.params.use_camera_wb = 1;
  raw->imgdata.params.output_bps = 16;
  raw->imgdata.params.no_auto_bright = 1;
  raw->imgdata.params.half_size = 1;
  check(raw->open_file(argv[1]), "open");
  std::printf("%s %s decoder=%s raw=%ux%u\n", raw->imgdata.idata.make,
              raw->imgdata.idata.model, raw->unpack_function_name(),
              raw->imgdata.sizes.raw_width, raw->imgdata.sizes.raw_height);
  check(raw->unpack(), "unpack");
  if (!raw->imgdata.rawdata.raw_image) return 3;
  save(argv[2], raw->imgdata.rawdata.raw_image,
       size_t(raw->imgdata.sizes.raw_pitch) * raw->imgdata.sizes.raw_height);
  check(raw->dcraw_process(), "process");
  int err = 0;
  auto *image = raw->dcraw_make_mem_image(&err);
  check(err, "memory image");
  if (!image) return 3;
  save(argv[3], image->data, image->data_size);
  std::printf("RGB=%ux%u colors=%u bits=%u bytes=%u\n", image->width,
              image->height, image->colors, image->bits, image->data_size);
  LibRaw::dcraw_clear_mem(image);
}

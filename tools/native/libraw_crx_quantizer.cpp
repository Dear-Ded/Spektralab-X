// Compile the real decoder; do not replace its quantizer with a test copy.
// GPT (OpenAI), AI assistant, participated in this regression.
#include "src/decoders/crx.cpp"
#include <cstdio>
#include <cstdlib>
#include <cstring>

static void require(bool ok, const char *message)
{
  if (!ok) { std::fprintf(stderr, "FAIL: %s\n", message); std::exit(1); }
}

static unsigned reference(int q)
{
  static const unsigned table[] = {40, 45, 51, 57, 64, 72};
  if (q >= 36) return unsigned(uint64_t(table[q % 6]) * (uint64_t(1) << (q / 6 - 6)));
  // Negative remainder follows the upstream maintenance policy.
  const int divisor = 6 - q / 6;
  return divisor >= 32 ? 0 : table[q < 0 ? 0 : q % 6] / (uint64_t(1) << divisor);
}

static void legacy(int q, bool accepted = true)
{
  CrxBandParam param{};
  int32_t scratch[6] = {}, previous[3] = {}, pixel = 99;
  param.subbandWidth = param.subbandHeight = 1;
  param.paramData = scratch;
  param.nonProgrData = previous;
  param.bitStream.bitsLeft = 32;
  param.bitStream.bitData = 0x20000000; // unary 001 -> signed residual +1
  CrxSubband band{};
  band.bandParam = &param;
  band.bandBuf = reinterpret_cast<uint8_t *>(&pixel);
  band.width = band.height = 1;
  band.dataSize = 1;
  band.qParam = q;
  const int rc = crxDecodeLineWithIQuantization(&band, nullptr);
  if ((rc == 0) != accepted) std::fprintf(stderr, "legacy QP=%d rc=%d expected=%d\n", q, rc, int(accepted));
  require((rc == 0) == accepted, "legacy decode acceptance");
  if (accepted) require(pixel == static_cast<int>(reference(q)), "legacy scale");
}

static void qsteps(int q, int levels, bool accepted = true)
{
  CrxImage img{};
  CrxTile tile{};
  img.levels = levels;
  tile.width = tile.height = 8;
  int32_t qp[] = {q, q, q, q};
  const int rc = crxMakeQStep(&img, &tile, qp, 4);
  require((rc == 0) == accepted, "QStep acceptance");
  for (int level = 0; accepted && level < levels; ++level)
    for (int row = 0; row < tile.qStep[level].height; ++row)
      require(tile.qStep[level].qStepTbl[row] == reference(q), "QStep scale");
#ifdef LIBRAW_CR3_MEMPOOL
  img.memmgr.free(tile.qStep);
#else
  std::free(tile.qStep);
#endif
}

int main(int argc, char **argv)
{
  // Establish that signed values come from real bitstream helpers.
  CrxBandParam signedParam{};
  signedParam.bitStream.bitsLeft = 32;
  signedParam.bitStream.bitData = 0x40000000; // unary 01 -> delta -1
  CrxSubband signedBand{};
  signedBand.bandParam = &signedParam;
  require(crxUpdateQparam(&signedBand) == 0 && signedBand.qParam == -1,
          "signed per-line QP delta");
  CrxBitstream signedStream{};
  signedStream.bitsLeft = 32;
  signedStream.bitData = 0x00400000; // unary 0000000001 -> signed -5
  int32_t qpLine[3] = {}, k = 0;
  crxDecodeGolombTop(&signedStream, 1, qpLine, &k);
  require(qpLine[1] + 4 == -1, "signed QP table input after decoder offset");
  const char *mode = argc > 1 ? argv[1] : "all";
  unsigned cases = 0;
  for (int q = -180; q <= 189; ++q)
    if (!std::strcmp(mode, "legacy") || !std::strcmp(mode, "all"))
    { legacy(q); ++cases; }
  for (int levels = 1; levels <= 3; ++levels)
    for (int q = -180; q <= 195; ++q)
      if (!std::strcmp(mode, "all") || std::atoi(mode) == levels)
      { qsteps(q, levels); ++cases; }
  if (!std::strcmp(mode, "all"))
  {
    for (int q : {190, 191, 196, 255, INT32_MAX}) { legacy(q, false); ++cases; }
    for (int levels = 1; levels <= 3; ++levels)
    {
      qsteps(INT32_MIN, levels); ++cases;
      for (int q : {196, 255, INT32_MAX}) { qsteps(q, levels, false); ++cases; }
    }
  }
  require(cases > 0, "unknown mode");
  std::printf("CRX quantizer PASS mode=%s cases=%u\n", mode, cases);
}

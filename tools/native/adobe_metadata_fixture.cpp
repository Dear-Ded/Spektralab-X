// Host-only ABI fixture. This is NOT the engine's spk_output_transform.
// It supplies deterministic matrix metadata to exercise production Android
// row conversion and TIFF writing where a host engine build is unavailable.
// Co-created with GPT (OpenAI), 2026-11-11. See NOTICE.
#include "spektrafilm/spk_engine.h"
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
struct spk_engine { std::string directory; };
extern "C" {
spk_engine* spk_engine_create(const char* resources, void*) { return new spk_engine{resources}; }
void spk_engine_destroy(spk_engine* engine) { delete engine; }
const char* spk_last_error(spk_engine*) { return "metadata fixture error"; }
void spk_string_free(char* text) { std::free(text); }
spk_status spk_output_transform(spk_engine* engine,const char*,const char* target,const char* spec,
                              char** output,const float** cmax,uint32_t* count) {
    const std::string name = target;
    // Old targets keep their existing spec. Adobe explicitly requests clipping.
    if(name == "Adobe RGB (1998)" && (!spec || std::string(spec) != "{\"algorithm\":\"off\"}")) return SPK_ERR_INVALID_ARG;
    const bool cam16 = name != "Adobe RGB (1998)" && spec && std::string(spec) != "{\"algorithm\":\"off\"}";
    std::ifstream file(engine->directory + "/" + name + (cam16?"_cam16":"") + ".json");
    if(!file) return SPK_ERR_INVALID_ARG;
    const std::string data{std::istreambuf_iterator<char>(file),std::istreambuf_iterator<char>()};
    *output=static_cast<char*>(std::malloc(data.size()+1));
    static const float table[4]={32,32,32,32};
    std::memcpy(*output,data.c_str(),data.size()+1); *cmax=cam16?table:nullptr; *count=cam16?4:0;
    return SPK_OK;
}
}

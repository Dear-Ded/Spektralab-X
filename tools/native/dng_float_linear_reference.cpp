// Co-created with GPT (OpenAI), 2026-11-09. Isolated float LinearRaw experiment.
// Does not call dcraw_process, change production defaults, or implement full DNG.
#include "io/raw_camera.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Tag { unsigned type = 0, count = 0; std::vector<unsigned char> data; };
unsigned word(const unsigned char* p) { return unsigned(p[0]) | (unsigned(p[1]) << 8); }
unsigned dword(const unsigned char* p) { return word(p) | (word(p+2) << 16); }
struct Metadata {
    unsigned width = 0, height = 0, orientation = 1;
    std::array<double,3> black{}, white{}, neutral{};
};
void require(bool valid, const char* message) { if (!valid) throw std::runtime_error(message); }
Metadata metadata(const char* path) {
    std::ifstream file(path,std::ios::binary);
    require(bool(file),"cannot open TIFF metadata");
    file.seekg(0,std::ios::end); const auto bytes=file.tellg();
    auto read=[&](std::uint64_t offset,unsigned length) {
        require(offset+length<=std::uint64_t(bytes),"TIFF payload outside file");
        std::vector<unsigned char> data(length);
        file.seekg(offset); file.read(reinterpret_cast<char*>(data.data()),length);
        require(bool(file),"TIFF metadata read failed"); return data;
    };
    auto header=read(0,8);
    require(header[0]=='I' && header[1]=='I' && word(header.data()+2)==42,"experiment requires little-endian classic TIFF");
    const unsigned offset=dword(header.data()+4);
    auto count_bytes=read(offset,2); const unsigned count=word(count_bytes.data());
    require(count<=128,"experiment TIFF tag limit");
    auto entries=read(offset+2,count*12+4);
    require(dword(entries.data()+count*12)==0,"experiment requires one IFD");
    std::map<unsigned,Tag> tags;
    for(unsigned i=0;i<count;++i) {
        const auto* p=entries.data()+i*12;
        const unsigned id=word(p),type=word(p+2),n=dword(p+4);
        unsigned size=type==1||type==2||type==7?1:type==3?2:type==4?4:type==5||type==10?8:0;
        require(size && n && std::uint64_t(n)*size<=4096,"unsupported tag encoding or metadata size");
        require(!tags.count(id),"duplicate TIFF tag");
        const unsigned length=n*size;
        tags[id]={type,n,length<=4 ? std::vector<unsigned char>(p+8,p+8+length):read(dword(p+8),length)};
    }
    auto values=[&](unsigned id) {
        require(tags.count(id),"required TIFF tag missing");
        const auto& t=tags.at(id); std::vector<double> result;
        for(unsigned i=0;i<t.count;++i) {
            const auto* p=t.data.data()+i*(t.type==3?2:t.type==4?4:t.type==5?8:1);
            require(t.type==1||t.type==3||t.type==4||t.type==5,"unsupported numeric metadata");
            require(t.type!=5||dword(p+4)>0,"invalid rational denominator");
            result.push_back(t.type==1?p[0]:t.type==3?word(p):t.type==4?dword(p):double(dword(p))/dword(p+4));
        }
        return result;
    };
    auto one=[&](unsigned id,double expected) { auto v=values(id);require(v.size()==1&&v[0]==expected,"unsupported TIFF layout"); };
    one(254,0);one(259,1);one(262,34892);one(277,3);one(284,1);
    require(values(258)==std::vector<double>({32,32,32}) && values(339)==std::vector<double>({3,3,3}),"requires float32 RGB samples");
    require(tags.count(50706)&&tags.count(50707)&&tags.at(50706).data.size()==4&&tags.at(50707).data.size()==4,"DNG versions required");
    require(tags.at(50706).data>=std::vector<unsigned char>({1,4,0,0})&&tags.at(50707).data>=std::vector<unsigned char>({1,4,0,0}),"float requires DNG/backward 1.4");
    // A strict supported-tag list keeps crop, opcodes, calibration and profile
    // operations from being silently skipped by this intentionally small path.
    const std::vector<unsigned> allowed={254,256,257,258,259,262,273,274,277,278,279,284,339,
        271,272,50706,50707,50708,50713,50714,50717,50721,50728,50778,50829,50964};
    for(const auto& item:tags)require(std::find(allowed.begin(),allowed.end(),item.first)!=allowed.end(),"unsupported crop/opcode/profile or other tag");
    Metadata m;
    auto w=values(256),h=values(257);
    require(w.size()==1&&h.size()==1&&w[0]>0&&h[0]>0&&w[0]<=65536&&h[0]<=65536,"invalid dimensions");
    m.width=unsigned(w[0]);m.height=unsigned(h[0]);
    require(std::uint64_t(m.width)*m.height<=16777216,"experiment pixel budget");
    require(values(50829)==std::vector<double>({0,0,double(m.height),double(m.width)}),"experiment requires full ActiveArea");
    require(values(50713)==std::vector<double>({1,1}),"experiment requires constant black");
    require(tags.count(50964)&&tags.at(50964).type==10&&tags.at(50964).count==9,"experiment requires ForwardMatrix1");
    require(tags.at(50714).type==5&&(tags.at(50717).type==3||tags.at(50717).type==4),"requires rational black and integer white");
    require(tags.count(50728)&&tags.at(50728).type==5&&tags.at(50728).count==3,"requires original rational AsShotNeutral");
    auto black=values(50714),white=values(50717),neutral=values(50728),orientation=values(274);
    require((black.size()==1||black.size()==3)&&white.size()==3,"requires constant per-plane black/white");
    require(orientation.size()==1&&orientation[0]>=1&&orientation[0]<=8,"invalid orientation");
    m.orientation=unsigned(orientation[0]);
    for(int c=0;c<3;++c) {
        m.black[c]=black[black.size()==1?0:c];m.white[c]=white[c];
        require(std::isfinite(m.black[c])&&std::isfinite(m.white[c])&&m.white[c]>m.black[c],"invalid black/white range");
        m.neutral[c]=neutral[c];
        require(std::isfinite(m.neutral[c])&&m.neutral[c]>0,"invalid AsShotNeutral");
    }
    const double max_neutral=*std::max_element(m.neutral.begin(),m.neutral.end());
    for(double v:m.neutral)require(v/max_neutral>=.001,"AsShotNeutral outside prepared FM domain");
    return m;
}
void emit_string(const std::string& text) {
    std::cout << '"';
    for(unsigned char c:text) { if(c=='"'||c=='\\')std::cout << '\\';std::cout << (c<32?'?':char(c)); }
    std::cout << '"';
}
template<class T>void emit_array(const T& values) {
    std::cout << '[';bool comma=false;for(auto v:values){if(comma)std::cout<<',';std::cout<<v;comma=true;}std::cout<<']';
}
}
int main(int argc,char** argv) {
    if(argc!=3 && !(argc==4 && std::string(argv[3])=="--legacy-open-wb"))return 2;
    try {
        const Metadata m=metadata(argv[1]);
        const bool legacy_open_wb=argc==4;
        auto raw=std::make_unique<spk::io::detail::CameraLibRaw>();
        raw->imgdata.params.use_camera_matrix=1;raw->imgdata.params.output_color=4;
        raw->imgdata.rawparams.options&=~LIBRAW_RAWOPTIONS_CONVERTFLOAT_TO_INT;
        require(raw->open_file(argv[1])==LIBRAW_SUCCESS,"LibRaw open failed");
        require(!raw->imgdata.idata.filters&&raw->imgdata.idata.colors==3&&!raw->imgdata.color.as_shot_wb_applied,"unsupported camera layout or preapplied WB");
        std::array<double,3> open_wb{};
        for(int c=0;c<3;++c)open_wb[c]=raw->imgdata.color.cam_mul[c];
        // LibRaw open.cpp compensates integer LinearRaw plane ranges in cam_mul.
        // This path normalizes each float plane itself, so prepare from the
        // original normalized-camera neutral instead of applying that twice.
        if(!legacy_open_wb) {
            for(int c=0;c<3;++c)raw->imgdata.color.cam_mul[c]=float(1/m.neutral[c]);
            raw->imgdata.color.cam_mul[3]=raw->imgdata.color.cam_mul[1];
        }
        require(raw->interpolate_dng_matrix(nullptr),"no supported prepared FM matrix");
        std::array<double,3> wb{};double minimum=std::numeric_limits<double>::max();
        for(int c=0;c<3;++c){wb[c]=raw->imgdata.color.cam_mul[c];require(wb[c]>0&&std::isfinite(wb[c]),"invalid source WB");minimum=std::min(minimum,wb[c]);}
        for(int c=0;c<3;++c){wb[c]/=minimum;raw->imgdata.color.pre_mul[c]=float(wb[c]);}
        raw->imgdata.color.pre_mul[3]=raw->imgdata.color.pre_mul[1];
        float matrix[3][4];for(auto& row:matrix)for(float& v:row)v=std::numeric_limits<float>::quiet_NaN();
        raw->apply_dng_forward_matrix(matrix);
        for(auto& row:matrix)for(float v:row)require(std::isfinite(v),"prepared FM transform unavailable");
        require(raw->unpack()==LIBRAW_SUCCESS&&raw->error_count()==0,"float unpack failed");
        const auto& id=raw->imgdata;
        require(id.rawdata.float3_image&&id.sizes.raw_width==m.width&&id.sizes.raw_height==m.height&&id.sizes.top_margin==0&&id.sizes.left_margin==0,"unsupported float3 geometry");
        const unsigned ow=m.orientation>=5?m.height:m.width,oh=m.orientation>=5?m.width:m.height;
        std::vector<float> output(std::size_t(ow)*oh*3);
        for(unsigned y=0;y<m.height;++y)for(unsigned x=0;x<m.width;++x) {
            unsigned dx=x,dy=y;
            switch(m.orientation){case 2:dx=m.width-1-x;break;case 3:dx=m.width-1-x;dy=m.height-1-y;break;
                case 4:dy=m.height-1-y;break;case 5:dx=y;dy=x;break;case 6:dx=m.height-1-y;dy=x;break;
                case 7:dx=m.height-1-y;dy=m.width-1-x;break;case 8:dx=y;dy=m.width-1-x;break;}
            std::array<double,3> balanced{};
            for(int c=0;c<3;++c) {
                const double sample=id.rawdata.float3_image[std::size_t(y)*m.width+x][c];
                require(std::isfinite(sample),"nonfinite sample unsupported");
                balanced[c]=(sample-m.black[c])/(m.white[c]-m.black[c])*wb[c];
            }
            for(int r=0;r<3;++r) {
                double value=0;for(int c=0;c<3;++c)value+=matrix[r][c]*balanced[c];
                require(std::isfinite(value),"nonfinite output unsupported");
                output[(std::size_t(dy)*ow+dx)*3+r]=float(value);
            }
        }
        std::ofstream file(argv[2],std::ios::binary);
        file.write(reinterpret_cast<const char*>(output.data()),output.size()*sizeof(float));
        require(bool(file),"output write failed");
        std::array<float,12> flat{};for(int r=0;r<3;++r)for(int c=0;c<4;++c)flat[r*4+c]=matrix[r][c];
        std::cout<<std::setprecision(17)<<"{\"ok\":true,\"legacy_open_wb\":"<<(legacy_open_wb?"true":"false")<<",\"width\":"<<ow<<",\"height\":"<<oh
            <<",\"input_float_bytes\":"<<std::uint64_t(m.width)*m.height*12
            <<",\"output_float_bytes\":"<<output.size()*sizeof(float)
            <<",\"raw_plane_plus_output_bytes_per_pixel\":24,\"source_wb\":";emit_array(wb);
        std::cout<<",\"source_neutral\":";emit_array(m.neutral);
        std::cout<<",\"libraw_open_wb\":";emit_array(open_wb);
        std::cout<<",\"wb_source\":\""<<(legacy_open_wb?"legacy_open_cam_mul":"original_rational_AsShotNeutral")<<'"';
        std::cout<<",\"post_wb_matrix\":";emit_array(flat);std::cout<<"}\n";
    }catch(const std::exception& e){std::cout<<"{\"ok\":false,\"error\":";emit_string(e.what());std::cout<<"}\n";}
}

// Private synthetic CAM16 boundary reference; co-created with GPT (OpenAI).
#include "output_transform.hpp"
#include "core/blob.hpp"
#include "core/cam16.hpp"
#include "core/colour.hpp"
#include "core/json.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <stdexcept>
namespace production {
#include "upstream_output_math.inc"
}
using RGB=std::array<double,3>;
constexpr double pi=3.14159265358979323846;
double maxdiff(const double* a,const double* b) {
    double d=0;for(int i=0;i<3;++i)d=std::max(d,std::abs(a[i]-b[i]));return d;
}
double table(const spk::Cam16Setup& st,double jp,double hue) {
    // Public table coordinates, independent double scalar interpolation.
    const double l=std::clamp(jp,st.l_grid.front(),st.l_grid.back());
    const auto hi=std::upper_bound(st.l_grid.begin(),st.l_grid.end(),l);
    const size_t row=std::min(st.l_grid.size()-2,size_t(hi-st.l_grid.begin()-1));
    const double lf=(l-st.l_grid[row])/(st.l_grid[row+1]-st.l_grid[row]);
    const double pos=(hue+pi)*st.h_grid.size()/(2*pi), hf=pos-std::floor(pos);
    const size_t col=size_t((int(std::floor(pos))%int(st.h_grid.size())+int(st.h_grid.size()))%int(st.h_grid.size()));
    const size_t next=(col+1)%st.h_grid.size(), n=st.h_grid.size();
    const double a=st.c_max_table[row*n+col]*(1-hf)+st.c_max_table[row*n+next]*hf;
    const double b=st.c_max_table[(row+1)*n+col]*(1-hf)+st.c_max_table[(row+1)*n+next]*hf;
    return a*(1-lf)+b*lf;
}
double direct_cmax(const spk::Cam16Setup& st,double jp,double hue) {
    double lo=0,hi=150;
    for(int i=0;i<36;++i) {
        const double mid=(lo+hi)/2,jab[]={jp,mid*std::cos(hue),mid*std::sin(hue)};
        double xyz[3],rgb[3];spk::cam16ucs_to_xyz(st,jab,xyz);st.m_to_rgb.apply(xyz,rgb);
        bool inside=true;for(double v:rgb)inside=inside&&v>=-1e-6&&v<=1+1e-6;
        if(inside)lo=mid;else hi=mid;
    }return lo;
}
RGB reference(const spk::Cam16Setup& st,const RGB& rgb,double threshold,double& d,double& cm,double& jp,double limit=1) {
    double xyz[3],jab[3];st.m_to_xyz.apply(rgb.data(),xyz);spk::xyz_to_cam16ucs(st,xyz,jab);
    jp=jab[0];cm=std::max(1e-9,table(st,jp,std::atan2(jab[2],jab[1])));
    const double m=std::hypot(jab[1],jab[2]);d=m/cm;
    const double compressed=spk::reinhard_knee(d,threshold,limit,6);
    const double ratio=m?compressed*cm/m:1;
    jab[1]*=ratio;jab[2]*=ratio;
    spk::cam16ucs_to_xyz(st,jab,xyz);RGB out;st.m_to_rgb.apply(xyz,out.data());return out;
}
double encode(double x) {return x<=.0031308?12.92*x:1.055*std::pow(x,1/2.4)-.055;}
double proencode(double x) {return x<1./512?16*x:std::pow(x,1/1.8);}
int main(int argc,char** argv) {
 try {
    if(argc!=3)throw std::runtime_error("RESOURCES OUTPUT required");
    std::cout<<std::setprecision(14);
    const std::string resources=argv[1],outdir=argv[2];std::string err;
    spk::Blob blob;spk::Colour colour;
    if(!blob.open(resources+"/spektrafilm_constants.bin",err)||!colour.init(blob,err))throw std::runtime_error(err);
    std::unique_ptr<spk_engine,decltype(&spk_engine_destroy)> engine(spk_engine_create(argv[1],nullptr),spk_engine_destroy);
    if(!engine)throw std::runtime_error("engine");
    for(const char* target:{"sRGB","Display P3"}) {
        const std::string tag=std::string(target)=="sRGB"?"srgb":"p3";
        char* text=nullptr;const float* cmax=nullptr;uint32_t count=0;
        if(spk_output_transform(engine.get(),"ProPhoto RGB",target,"{\"lightness_compression_active\":false}",&text,&cmax,&count)!=SPK_OK)throw std::runtime_error(spk_last_error(engine.get()));
        std::ofstream(outdir+"/"+tag+"-transform.json")<<text;
        std::ofstream(outdir+"/"+tag+"-cmax.f32",std::ios::binary).write(reinterpret_cast<const char*>(cmax),count*4);
        spk::Json reply;if(!spk::Json::parse(text,reply,err))throw std::runtime_error(err);spk_string_free(text);
        const auto& gc=reply.at("gamut_compress");std::array<float,9> mx,mr;std::array<float,22> k;
        double matrix[9];for(int i=0;i<9;++i){mx[i]=gc.at("m_to_xyz").items()[i].as_double();mr[i]=gc.at("m_to_rgb").items()[i].as_double();matrix[i]=reply.at("matrix").items()[i].as_double();}
        for(int i=0;i<22;++i)k[i]=gc.at("k").items()[i].as_double();
        if(k[12]!=0||k[13]!=1||k[14]!=6||gc.at("lightness_compression_active").as_bool())throw std::runtime_error("unexpected export contract");
        spk::Cam16Setup st;if(!spk::cam16_setup_for(colour,target,st,err))throw std::runtime_error(err);
        double table_error=0;for(size_t i=0;i<st.c_max_table.size();++i)table_error=std::max(table_error,std::abs(double(cmax[i])-st.c_max_table[i]));
        double k_error=0;const double expected[]={st.F_L,st.N_bb,st.N_cb,st.n,st.z,st.A_w,st.c,st.N_c,st.l_grid.front(),st.l_grid.back(),st.h_grid.front(),st.h_grid[1]-st.h_grid[0],0,1,6,0,1,1,st.white_Jp,st.D_rgb[0],st.D_rgb[1],st.D_rgb[2]};
        for(int i=0;i<22;++i)k_error=std::max(k_error,std::abs(gc.at("k").items()[i].as_double()-expected[i]));
        double mat_error=0;double xx[9],rr[9];st.m_to_xyz.to_row_major(xx);st.m_to_rgb.to_row_major(rr);
        for(int i=0;i<9;++i)mat_error=std::max({mat_error,std::abs(gc.at("m_to_xyz").items()[i].as_double()-xx[i]),std::abs(gc.at("m_to_rgb").items()[i].as_double()-rr[i])});
        spk::android::OutputTransform actual;if(!actual.fetch(engine.get(),target,true,err))throw std::runtime_error(err);
        const spk::Mat3 inv=spk::Mat3::from_row_major(matrix).inverse();
        double worst=0,encoded_worst=0,late_worst=0,late_matrix_error=0,matrix_roundtrip_error=0,late_float_error=0;size_t n=0,trusted=0;
        auto sample=[&](const RGB& rgb,bool verbose) {
            double d,cm,jp;const RGB ref=reference(st,rgb,0,d,cm,jp);
            float f[]={float(rgb[0]),float(rgb[1]),float(rgb[2])};
            production::cam16ucs_into(f,mx,mr,k,cmax,64,720,false);
            const double fa[]={f[0],f[1],f[2]};const double delta=maxdiff(ref.data(),fa);worst=std::max(worst,delta);
            double pro[3];inv.apply(rgb.data(),pro);float encoded[3];for(int j=0;j<3;++j)encoded[j]=proencode(pro[j]);actual.apply(encoded);
            double mac_d,mac_cm,mac_jp;
            const RGB mac=reference(st,rgb,.7,mac_d,mac_cm,mac_jp,.995);
            double encoded_ref[3];for(int j=0;j<3;++j)encoded_ref[j]=encode(mac[j]);
            encoded_worst=std::max(encoded_worst,maxdiff(encoded_ref,std::array<double,3>{encoded[0],encoded[1],encoded[2]}.data()));
            double dl,cl,jl;const RGB late=reference(st,rgb,.8,dl,cl,jl);
            if(d<=.8){
                ++trusted;late_worst=std::max(late_worst,maxdiff(rgb.data(),late.data()));
                double xyz[3],roundtrip[3];st.m_to_xyz.apply(rgb.data(),xyz);st.m_to_rgb.apply(xyz,roundtrip);
                matrix_roundtrip_error=std::max(matrix_roundtrip_error,maxdiff(rgb.data(),roundtrip));
                late_matrix_error=std::max(late_matrix_error,maxdiff(late.data(),roundtrip));
                auto late_k=k;late_k[12]=.8f;float fc[]={float(rgb[0]),float(rgb[1]),float(rgb[2])};
                production::cam16ucs_into(fc,mx,mr,late_k,cmax,64,720,false);
                const double fcd[]={fc[0],fc[1],fc[2]};late_float_error=std::max(late_float_error,maxdiff(fcd,roundtrip));
                if(spk::reinhard_knee(d,.8,1,6)!=d)throw std::runtime_error("late knee trust zone");
            }
            if(verbose){const double hue=std::atan2(0.,1.);(void)hue;
                std::cout<<"sample "<<tag<<" input="<<rgb[0]<<","<<rgb[1]<<","<<rgb[2]<<" Jp="<<jp<<" Cmax="<<cm<<" d="<<d<<" ratio="<<(d?spk::reinhard_knee(d,0,1,6)/d:1)<<" actual="<<f[0]<<","<<f[1]<<","<<f[2]<<" double_error="<<delta<<" late="<<late[0]<<","<<late[1]<<","<<late[2]<<"\n";
            }++n;
        };
        for(const RGB rgb:std::array<RGB,10>{{{0,0,0},{.18,.18,.18},{1,1,1},{.01,.02,.1},{.03,.06,.2},{0,0,1},{.04,.08,.12},{-.02,.02,.8},{.05,.1,2},{2,2,2}}})sample(rgb,true);
        for(int r=0;r<=8;++r)for(int g=0;g<=8;++g)for(int b=0;b<=8;++b)sample({r/8.,g/8.,b/8.},false);
        double bisect_max=0;for(double j:{10.,30.,50.,70.,90.})for(double h:{-3.,-2.,-1.,0.,1.,2.,3.})bisect_max=std::max(bisect_max,std::abs(table(st,j,h)-direct_cmax(st,j,h)));
        std::cout<<"summary "<<tag<<" n="<<n<<" table_narrow_max="<<table_error<<" k_error="<<k_error<<" matrix_error="<<mat_error<<" float_vs_double_max_linear="<<worst<<" actual_apply_vs_double_max_encoded="<<encoded_worst<<" late_trusted="<<trusted<<" late_trusted_roundtrip_max="<<late_worst<<" plain_matrix_roundtrip_max="<<matrix_roundtrip_error<<" late_vs_plain_matrix_max="<<late_matrix_error<<" late_float_vs_plain_matrix_max="<<late_float_error<<" interpolation_vs_direct_cmax_max="<<bisect_max<<"\n";
        if(worst>1e-4||encoded_worst>3e-4||late_matrix_error>1e-10||late_float_error>1e-4||k_error>1e-10||mat_error>1e-10||table_error>4e-6)throw std::runtime_error("reference tolerance");
    }
    for(double d:{0.,.2,.5,.8,.9,1.,1.5,3.})std::cout<<"knee d="<<d<<" current="<<spk::reinhard_knee(d,0,1,6)<<" late="<<spk::reinhard_knee(d,.8,1,6)<<"\n";
    std::cout<<"PASS core-double reference\n";return 0;
 }catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
}

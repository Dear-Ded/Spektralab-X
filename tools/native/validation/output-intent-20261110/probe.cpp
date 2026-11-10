// Synthetic output-intent candidates only. Co-created with GPT (OpenAI).
#include "core/blob.hpp"
#include "core/colour.hpp"
#include "core/cam16.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <stdexcept>
using RGB=std::array<double,3>;
bool inside(const RGB& x,double eps=0){return std::all_of(x.begin(),x.end(),[=](double v){return v>=-eps&&v<=1+eps;});}
double error(const RGB& a,const RGB& b){double e=0;for(int i=0;i<3;++i)e=std::max(e,std::abs(a[i]-b[i]));return e;}
RGB xyzrgb(const spk::Cam16Setup& s,const double jab[3]){double xyz[3];RGB rgb;spk::cam16ucs_to_xyz(s,jab,xyz);s.m_to_rgb.apply(xyz,rgb.data());return rgb;}
double interp(const spk::Cam16Setup& s,double j,double h){
 const double l=std::clamp(j,1.,110.),p=(l-1)/109*63;const int i=std::clamp(int(std::floor(p)),0,62);const double f=p-i;
 const double q=(h-s.h_grid[0])/(s.h_grid[1]-s.h_grid[0]),hf=q-std::floor(q);
 const int a=(int(std::floor(q))%720+720)%720,b=(a+1)%720;
 return (1-f)*((1-hf)*s.c_max_table[i*720+a]+hf*s.c_max_table[i*720+b])+f*((1-hf)*s.c_max_table[(i+1)*720+a]+hf*s.c_max_table[(i+1)*720+b]);
}
RGB knee(const spk::Cam16Setup& s,const RGB& rgb,double t,double& d){
 double xyz[3],jab[3];s.m_to_xyz.apply(rgb.data(),xyz);spk::xyz_to_cam16ucs(s,xyz,jab);
 const double m=std::hypot(jab[1],jab[2]),cm=std::max(1e-9,interp(s,jab[0],std::atan2(jab[2],jab[1])));d=m/cm;
 const double scale=m?spk::reinhard_knee(d,t,1,6)/d:1;jab[1]*=scale;jab[2]*=scale;return xyzrgb(s,jab);
}
// Exploratory hard radial boundary: no claim of a production gamut mapper.
// Exact RGB membership bypass protects every already representable sample.
bool boundary(const spk::Cam16Setup& s,const RGB& rgb,RGB& result){
 if(inside(rgb)){result=rgb;return true;}
 double xyz[3],jab[3];s.m_to_xyz.apply(rgb.data(),xyz);spk::xyz_to_cam16ucs(s,xyz,jab);
 const double neutral[]={jab[0],0,0};if(!inside(xyzrgb(s,neutral),1e-10))return false;
 double lo=0,hi=1;
 for(int i=0;i<48;++i){const double p=(lo+hi)/2,c[]={jab[0],jab[1]*p,jab[2]*p};if(inside(xyzrgb(s,c),1e-10))lo=p;else hi=p;}
 const double c[]={jab[0],jab[1]*lo,jab[2]*lo};result=xyzrgb(s,c);return true;
}
double encode(double x){return x<=.0031308?12.92*x:1.055*std::pow(x,1/2.4)-.055;}
double decode(double x){return x<=.04045?x/12.92:std::pow((x+.055)/1.055,2.4);}
int main(int argc,char** argv){try{
 if(argc!=2)throw std::runtime_error("baked constants path required");
 std::cout<<std::setprecision(14);
 spk::Blob blob;spk::Colour colour;std::string err;if(!blob.open(argv[1],err)||!colour.init(blob,err))throw std::runtime_error(err);
 for(const char* name:{"sRGB","Display P3"}){
  spk::Cam16Setup s;if(!spk::cam16_setup_for(colour,name,s,err))throw std::runtime_error(err);
  size_t n=0,trust=0,boundary_ok=0,unsupported=0,late_out=0;double trust_change=0,preserve_change=0,default_change=0;
  for(int r=0;r<=8;++r)for(int g=0;g<=8;++g)for(int b=0;b<=8;++b){
   const RGB x{r/8.,g/8.,b/8.};double d;const RGB old=knee(s,x,0,d),late=knee(s,x,.8,d);RGB hard;
   default_change=std::max(default_change,error(x,old));if(d<=.8){++trust;double xyz[3];RGB rt;s.m_to_xyz.apply(x.data(),xyz);s.m_to_rgb.apply(xyz,rt.data());trust_change=std::max(trust_change,error(rt,late));}
   if(!inside(late,1e-6))++late_out;
   if(!boundary(s,x,hard))throw std::runtime_error("in-gamut bypass");
   preserve_change=std::max(preserve_change,error(x,hard));++n;
  }
  for(const RGB x:std::array<RGB,5>{{{-.02,.02,.8},{.05,.1,2},{2,2,2},{-.1,.2,.3},{.01,.02,.1}}}){
   double d;const RGB old=knee(s,x,0,d),late=knee(s,x,.8,d);RGB hard;const bool ok=boundary(s,x,hard);if(ok){++boundary_ok;if(!inside(hard,2e-9))throw std::runtime_error("boundary outside");}else ++unsupported;
   std::cout<<"sample "<<name<<" input="<<x[0]<<","<<x[1]<<","<<x[2]<<" d="<<d<<" default="<<old[0]<<","<<old[1]<<","<<old[2]<<" late="<<late[0]<<","<<late[1]<<","<<late[2]<<" boundary_supported="<<ok;if(ok)std::cout<<" boundary="<<hard[0]<<","<<hard[1]<<","<<hard[2];std::cout<<"\n";
  }
  std::cout<<"summary "<<name<<" cube="<<n<<" default_max_change="<<default_change<<" late_trust="<<trust<<" late_trust_vs_matrix_error="<<trust_change<<" late_outside_1e-6="<<late_out<<" preserve_max_change="<<preserve_change<<" mixed_boundary_supported="<<boundary_ok<<" mixed_boundary_no_neutral="<<unsupported<<"\n";
  if(trust_change>1e-10||preserve_change!=0)throw std::runtime_error("trust/preserve invariant");
 }
 // Synthetic same-space file-depth proof oracle: includes actual file clamp.
 for(int bits:{8,16}){const double q=(1u<<bits)-1;double maxe=0;for(int i=0;i<=1000;++i){const double x=i/1000.;const double code=std::round(std::clamp(encode(x),0.,1.)*q)/q;maxe=std::max(maxe,std::abs(x-decode(code)));}std::cout<<"proof_quantization bits="<<bits<<" max_linear_error="<<maxe<<"\n";}
 std::cout<<"boundary_jump conditional_late_at_one="<<1-spk::reinhard_knee(1,.8,1,6)<<" hard_radial_derivative_inside=1 outside=0\nPASS output-intent synthetic\n";
 return 0;
 }catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}}

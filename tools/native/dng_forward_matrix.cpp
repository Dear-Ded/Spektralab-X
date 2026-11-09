#include "io/dng_forward_matrix.hpp"
#include <cassert>
#include <cstdio>
#include <limits>
using namespace spk::io::detail::dng_forward;

static void near(double a,double b,double tolerance=1e-8) { assert(std::fabs(a-b)<=tolerance); }
static void same(const Matrix& a,const Matrix& b) { for(int i=0;i<9;++i)near(a[i],b[i]); }
static void gray(const Prepared& p,const float wb[4]) {
    Matrix out{};assert(post_wb_to_prophoto(p,wb,out));
    for(double level:{0.,.18,1.,4.,-0.1}) {
        auto v=multiply(out,Vector{level,level,level});
        for(double x:v) { assert(std::isfinite(x));near(x,level,2e-6); }
    }
}

static void golden(const Profile& profile,const Vector& neutral,const float wb[4],
                   const Matrix& expected,const Vector& expected_rgb,double tolerance) {
    Prepared prepared;Matrix actual{};
    assert(prepare(profile,neutral,prepared)&&prepared.has_forward);
    assert(post_wb_to_prophoto(prepared,wb,actual));
    for(int i=0;i<9;++i)near(actual[i],expected[i],tolerance);
    const auto rgb=multiply(actual,Vector{-.125,.6,1.75});
    for(int i=0;i<3;++i)near(rgb[i],expected_rgb[i],tolerance);
}

static void independent_goldens() {
    // Independent NumPy solve/inv reference, not this implementation's multiply:
    // inv(ProPhoto) * FM * diag(1/(inv(AB*CC)*N)) * inv(AB*CC)
    // * diag(normalize(1/WB)). N is max-normalized, WB is float32.
    // The fixed HDR/negative RGB catches color errors that gray-axis tests miss.
    Profile single;
    single.analog={1.7,.8,1.2};
    single.slots[0].kelvin=6500;
    single.slots[0].color=identity;
    single.slots[0].calibration={1.1,.1,0,.03,.9,.04,0,.05,1.05};
    single.slots[0].forward={.7,.2,.06422,.2,.75,.05,.03,.1,.69521};
    const float single_wb[]={2,1,4,1};
    golden(single,Vector{.5,1.,.25},single_wb,
        Matrix{1.6786586003852269,-.74032489959748,.06166629921225307,
               -.15554300568153218,1.0806516995831321,.07489130609840043,
               .07054057305695553,-.33152109799035495,1.2609805249333994},
        Vector{-.5461112411851985,.7988936811322715,1.9989856882071164},1e-10);

    Profile dual;
    dual.analog={1.2,.8,1.1};
    dual.slots[0].kelvin=2850;dual.slots[1].kelvin=6500;
    dual.slots[0].color={.85,.06,.02,.03,1.1,.04,.01,.02,.9};
    dual.slots[1].color={1.1,.02,.04,.01,.95,.03,.02,.01,1.2};
    dual.slots[0].calibration={1.05,.02,0,.01,.95,.03,0,.02,1.1};
    dual.slots[1].calibration={.98,-.01,.02,.03,1.03,0,.01,0,.94};
    dual.slots[0].forward=single.slots[0].forward;
    dual.slots[1].forward={.85,.08,.03422,.3,.65,.05,.01,.03,.78521};
    // NumPy computes camera = w*(AB*CC1*CM1)+(1-w)*(AB*CC2*CM2),
    // not AB*blend(CC)*blend(CM), which introduces cross-calibration terms.
    // Constructed from xy=(.38,.38), CCT=4042.3987408863804 K,
    // inverse-CCT first weight=.47470550318057464. The 1e-7 allowance is
    // for NeutralToXY's finite iterative convergence, not float output error.
    const Vector neutral{1.276258802646551,.8652816271370801,.7903604573542071};
    const float dual_wb[]={.7835401296615601f,1.1556930541992188f,1.2652454376220703f,1.1556930541992188f};
    golden(dual,neutral,dual_wb,
        Matrix{.9936254622909637,-.0015134939299105352,.007888031803620629,
               -.06382956368489544,1.007080490647049,.05674909590414974,
               .015530665782767216,.06558796275103423,.9188813958012598},
        Vector{-.11130722348798067,.7115379076811034,1.6454538870799793},1e-7);
    Prepared middle;
    assert(prepare(dual,neutral,middle));
    const Matrix middle_camera{1.188461448973149,.05491260130510671,.05206752458313405,
        .03236141358473324,.8087621914107332,.03825020172422161,
        .023276395826324896,.02852285624035295,1.1693885750385882};
    for(int i=0;i<9;++i)near(middle.camera_from_xyz[i],middle_camera[i],1e-7);

    // Both endpoint clamps retain their own non-diagonal CC and CM.
    // Independent xy=(.47,.41) and (.30,.31) give 2551 K and 7520 K.
    const Vector warm{1.3382136585365854,.8864331707317075,.38201658536585387};
    const float warm_wb[]={.7472648024559021f,1.1281166076660156f,2.617687463760376f,1.1281166076660156f};
    golden(dual,warm,warm_wb,
        Matrix{.9076745746367013,.051952852822717055,.04037256922018352,
               -.0906043360832772,1.0409254396972734,.049678876376778834,
               .03652295177257511,.062008348970069196,.9014686430131864},
        Vector{-.011635614000636271,.7228188394881366,1.610209765683546},1e-10);
    Prepared warm_prepared;
    assert(prepare(dual,warm,warm_prepared));
    same(warm_prepared.camera_from_xyz,Matrix{1.07172,.102,.02616,.02984,.83696,.05216,.01276,.0484,1.08988});

    const Vector cool{1.359538064516129,.849109677419355,1.6038425806451608};
    const float cool_wb[]={.735543966293335f,1.1777042150497437f,.6235026121139526f,1.1777042150497437f};
    golden(dual,cool,cool_wb,
        Matrix{1.0888109865663311,-.05208793102238099,-.036723035380802155,
               -.042668676621067415,.968031933541843,.0746367348659461,
               .0035134831767802003,.03759108971168322,.958895426886442},
        Vector{-.23161944385062377,.716767030718145,1.700182465481186},1e-10);
    Prepared cool_prepared;
    assert(prepare(dual,cool,cool_prepared));
    same(cool_prepared.camera_from_xyz,Matrix{1.29396,.01236,.07548,.03464,.78328,.02568,.03278,.01056,1.24124});
}

static void selected_illuminant() {
    // Real ColorMatrix tags from Xiaomi 13 Ultra IMG_20230420_152059.dng.
    // NeutralToXY has multiple stable roots for the 4000 K / -150 tint
    // control. Its known illuminant must survive the decode boundary.
    Profile p;
    p.slots[0].kelvin=6500;p.slots[1].kelvin=2850;
    p.slots[0].color={.6015625,-.0859375,-.125,-.59375,1.59375,-.0234375,-.1640625,.375,.3515625};
    p.slots[1].color={.8359375,-.1015625,-.5390625,-1.078125,2.390625,-.59375,-.203125,.3515625,.5234375};
    p.slots[0].forward={.65625,-.0078125,.3203125,.25,.6015625,.1484375,.03125,-.4921875,1.28125};
    p.slots[1].forward={.703125,-.203125,.4609375,.3359375,.3203125,.34375,.140625,-.6328125,1.3125};
    auto camera=[&](double w){return blend(p.slots[0].color,p.slots[1].color,w);};
    const double x=.3518587614896191,y=.2713459597827732;
    const Matrix chosen=camera(first_weight(4000,6500,2850));
    const Vector neutral=multiply(chosen,Vector{x/y,1.,(1-x-y)/y});
    const float wb[]={float(1/neutral[0]),float(1/neutral[1]),float(1/neutral[2]),float(1/neutral[1])};
    Prepared automatic;
    assert(prepare(p,neutral,automatic));
    assert(automatic.kelvin>10000);
    Prepared out;
    assert(prepare(p,neutral,out,4000)&&out.has_forward);
    near(out.kelvin,4000);
    same(out.camera_from_xyz,chosen);
    Matrix inv{};assert(inverse(out.camera_from_xyz,inv));
    const auto xyz=multiply(inv,out.neutral);
    const double sum=xyz[0]+xyz[1]+xyz[2];
    near(xyz[0]/sum,x);near(xyz[1]/sum,y);
    Matrix output{};assert(post_wb_to_prophoto(out,wb,output));
    for(double value:multiply(output,Vector{-.125,.6,1.75}))assert(std::isfinite(value));
    const Prepared before=out;
    for(double invalid:{-1.,std::numeric_limits<double>::infinity(),std::numeric_limits<double>::quiet_NaN()}) {
        assert(!prepare(p,neutral,out,invalid));
        same(out.camera_from_xyz,before.camera_from_xyz);
        same(out.camera_to_pcs,before.camera_to_pcs);
        near(out.kelvin,before.kelvin);
    }
    p.slots[0].forward={};p.slots[1].forward={};
    assert(prepare(p,neutral,out,4000)&&!out.has_forward);
    same(out.camera_from_xyz,chosen);
    near(out.kelvin,4000);
}

int main() {
    independent_goldens();
    selected_illuminant();
    Profile p;
    p.slots[0].kelvin=6500;
    p.slots[0].color=identity;
    p.slots[0].forward=prophoto_to_xyz;
    Prepared out;
    assert(prepare(p,Vector{.5,1.,.25},out)&&out.has_forward);
    const float wb[]={2,1,4,1};gray(out,wb);
    Matrix m{};assert(post_wb_to_prophoto(out,wb,m));same(m,identity);
    const float wrong_wb[]={1,1,1,1};Matrix untouched=identity;
    assert(!post_wb_to_prophoto(out,wrong_wb,untouched));same(untouched,identity);

    // Non-diagonal camera calibration and unequal analog gains must cancel
    // in the white, not be treated as another channel-wise WB multiplier.
    p.analog={1.7,.8,1.2};
    p.slots[0].calibration={1.1,.1,0,.03,.9,.04,0,.05,1.05};
    assert(prepare(p,Vector{.5,1.,.25},out));gray(out,wb);
    Vector neutral=multiply(out.camera_to_pcs,out.neutral);
    for(int i=0;i<3;++i)near(neutral[i],white[i]);

    // Dual illuminants: weighting is by reciprocal temperature and does not
    // depend on slot ordering. The midpoint differs from an arithmetic CCT.
    near(first_weight(2850,6500,2850),0);
    near(first_weight(6500,6500,2850),1);
    const double middle=2/(1/2850.+1/6500.);
    near(first_weight(middle,6500,2850),.5);
    p.slots[1]=p.slots[0];p.slots[1].kelvin=2850;
    p.slots[1].color={1.1,.02,0,0,1.05,.01,.01,0,.9};
    p.slots[1].forward={.7,.2,.06422,.2,.75,.05,.03,.1,.69521};
    Prepared dual,reversed;
    assert(prepare(p,Vector{.5,1.,.25},dual));gray(dual,wb);
    std::swap(p.slots[0],p.slots[1]);
    assert(prepare(p,Vector{.5,1.,.25},reversed));
    same(dual.camera_from_xyz,reversed.camera_from_xyz);
    same(dual.camera_to_pcs,reversed.camera_to_pcs);

    // A single FM is valid for both calibration illuminants.
    p.slots[1].forward={};assert(prepare(p,Vector{.5,1.,.25},out)&&out.has_forward);gray(out,wb);
    p.slots[0].forward={};assert(prepare(p,Vector{.5,1.,.25},out)&&!out.has_forward);
    untouched=identity;assert(!post_wb_to_prophoto(out,wb,untouched));same(untouched,identity);
    p.slots[0].forward={1,1,1,1,1,1,1,1,1};
    assert(prepare(p,Vector{.5,1.,.25},out)&&!out.has_forward);
    p.slots[0].forward[0]=std::numeric_limits<double>::quiet_NaN();
    assert(prepare(p,Vector{.5,1.,.25},out)&&!out.has_forward);
    const Prepared before=out;
    p.analog[0]=0;assert(!prepare(p,Vector{.5,1.,.25},out));same(out.camera_from_xyz,before.camera_from_xyz);
    p.analog[0]=1;assert(!prepare(p,Vector{0,1.,.25},out));same(out.camera_from_xyz,before.camera_from_xyz);
    p.slots[0].color={};p.slots[1].color={};
    assert(!prepare(p,Vector{.5,1.,.25},out));same(out.camera_from_xyz,before.camera_from_xyz);
    std::puts("DNG ForwardMatrix math: single/dual, AB+CC, explicit WB, gray/HDR, fallback passed");
}

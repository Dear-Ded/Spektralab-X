#!/usr/bin/env python3
"""Local host conversion/TIFF fixture; does not claim engine integration.

Requires Zig C++, an upstream source tree (read only), and zlib DLL/header.
Compiles actual Android conversion/grade/TIFF sources and upstream output
arithmetic. The engine ABI is replaced with explicit matrix metadata.
Co-created with GPT (OpenAI), 2026-11-11. See NOTICE.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from adobe_tiff_cmm import inverse, ADOBE, ROMM

def multiply(a,b):
    return [[sum(a[i][k]*b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['zig','up','build','zlib-include','zlib-dll','icc','lcms']:
        p.add_argument('--'+key,required=True)
    args=p.parse_args()
    repo=Path(__file__).resolve().parents[2]; app=repo/'app/src/main/cpp'
    up=Path(args.up); build=Path(args.build).resolve(); build.mkdir(parents=True,exist_ok=True)
    source=(up/'engine/host/output.cpp').read_text()
    parts=[]
    for start,end in [('static inline float spow','bool OutputTransform::fetch'),('static inline void cam16ucs_into','template <typename F>')]:
        a,z=source.index(start),source.index(end); assert a<z; parts.append(source[a:z])
    (build/'upstream_output_math.inc').write_text('\n'.join(parts))
    # Matrix fixtures use D50 PCS consistently, no second white adaptation.
    targets={'Adobe RGB (1998)':(ADOBE,2),'ProPhoto RGB':(ROMM,1),
             'sRGB':([[.4360747,.3850649,.1430804],[.2225045,.7168786,.0606169],[.0139322,.0971045,.7141733]],0),
             'Display P3':([[.515102,.291965,.157153],[.241182,.692236,.0665819],[-.0010494,.0418818,.784378]],0)}
    for name,(matrix,mode) in targets.items():
        m=multiply(inverse(matrix),ROMM)
        data={'source_cctf_mode':1,'target_cctf_mode':mode,'matrix':sum(m,[]),
              'gamut_compress':{'algorithm':'off','lightness_compression_active':False}}
        (build/(name+'.json')).write_text(json.dumps(data))
        # Synthetic finite CAM16 metadata exercises the existing compression
        # branch for old export targets; this is not a C_max colour reference.
        if name!='Adobe RGB (1998)':
            data['gamut_compress']={'algorithm':'cam16ucs','lightness_compression_active':False,
                'm_to_xyz':sum(matrix,[]),'m_to_rgb':sum(inverse(matrix),[]),
                'k':[.38848,1.0003,1.0003,.2,1.92721,29.98,.69,1,0,100,-3.14159265,3.14159265,
                     .7,.995,6,.7,1,2,100,1.0191,.9872,.994], 'cmax_rows':2,'cmax_cols':2}
            (build/(name+'_cam16.json')).write_text(json.dumps(data))
    baseline=subprocess.check_output(['git','show','HEAD:app/src/main/cpp/output_transform.cpp'],cwd=repo)
    (build/'baseline_output_transform.cpp').write_bytes(baseline)
    env=os.environ.copy(); env['PATH']=str(Path(args.zlib_dll).parent)+os.pathsep+env['PATH']
    common=[args.zig,'c++','-std=c++20','-O2','-ffp-contract=off','-I'+str(app),'-I'+str(build),
            '-I'+str(up/'engine/include'),'-I'+str(up/'engine/src'),'-I'+args.zlib_include]
    support=[str(app/'raw_development.cpp'),str(app/'layer2.cpp'),str(repo/'tools/native/adobe_metadata_fixture.cpp'),str(up/'engine/src/core/json.cpp')]
    log=[]
    for name,driver,transform in [('adobe','adobe_tiff.cpp',app/'output_transform.cpp'),
                                  ('legacy_before','adobe_legacy.cpp',build/'baseline_output_transform.cpp'),
                                  ('legacy_after','adobe_legacy.cpp',app/'output_transform.cpp')]:
        cmd=common+[str(repo/'tools/native'/driver),str(transform)]+support
        if name=='adobe': cmd += [str(app/'tiff16.cpp'),args.zlib_dll]
        exe=build/(name+'.exe')
        subprocess.run(cmd+['-o',str(exe)],check=True,env=env)
        run=[str(exe),str(build)]
        if name=='adobe': run += [str(Path(args.icc).resolve()),str(build)]
        else: run += [str(build/(name+'.bin'))]
        result=subprocess.run(run,check=True,env=env,capture_output=True,text=True)
        log.append(result.stdout); print(result.stdout,end='')
    before=(build/'legacy_before.bin').read_bytes(); after=(build/'legacy_after.bin').read_bytes()
    assert before==after,'old target sample bytes changed'
    summary=json.dumps({'metadata_fixture':True,'synthetic_cam16_legacy_exports':True,
                        'legacy_bytes':len(after),'legacy_sha256':hashlib.sha256(after).hexdigest(),
                        'profile_sha256':hashlib.sha256(Path(args.icc).read_bytes()).hexdigest(),
                        'production_output_sha256':hashlib.sha256((app/'output_transform.cpp').read_bytes()).hexdigest(),
                        'upstream_math_source_sha256':hashlib.sha256((up/'engine/host/output.cpp').read_bytes()).hexdigest()},indent=2)
    log.append(summary+'\n'); print(summary)
    result=subprocess.run([sys.executable,str(repo/'tools/native/adobe_tiff_cmm.py'),'--icc',args.icc,'--directory',str(build),'--lcms',args.lcms],check=True,env=env,capture_output=True,text=True)
    log.append(result.stdout); print(result.stdout,end='')
    (build/'result.log').write_text(''.join(log))

if __name__=='__main__': main()

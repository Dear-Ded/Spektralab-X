#!/usr/bin/env python3
"""Read actual TIFF strips/tag 34675 and independently check Adobe RGB via LCMS.

Uses the published Adobe RGB (1998) D50 matrix and exact gamma 563/256.
Little CMS is only a host validation oracle, never an Android dependency.
Co-created with GPT (OpenAI), 2026-11-11. See NOTICE.
"""
import argparse
import csv
import ctypes as C
import ctypes.util
import json
import math
import os
from pathlib import Path
import struct
import zlib

ADOBE = [[.60974, .20528, .14919], [.31111, .62567, .06322], [.01947, .06087, .74457]]
ROMM = [[.7976749, .1351917, .0313534], [.2880402, .7118741, .0000857], [0, 0, .82521]]


def mv(m, v):
    return [sum(a*b for a,b in zip(row,v)) for row in m]


def inverse(a):
    # Gauss-Jordan in double precision; independent of engine matrix arithmetic.
    b = [row[:] + [float(i == j) for j in range(3)] for i,row in enumerate(a)]
    for i in range(3):
        p = max(range(i,3), key=lambda r: abs(b[r][i]))
        b[i],b[p] = b[p],b[i]
        scale = b[i][i]
        b[i] = [v/scale for v in b[i]]
        for j in range(3):
            if j != i:
                scale = b[j][i]
                b[j] = [v-scale*w for v,w in zip(b[j],b[i])]
    return [row[3:] for row in b]


def read_tiff(path):
    data = path.read_bytes()
    assert data[:4] == b'II*\0'
    directory = struct.unpack_from('<I', data, 4)[0]
    tags = {}
    for i in range(struct.unpack_from('<H', data, directory)[0]):
        at = directory+2+i*12
        tag,kind,n,value = struct.unpack_from('<HHII', data, at)
        length = n*{3:2,4:4,5:8,7:1}[kind]
        start = at+8 if length <= 4 else value
        assert start+length <= len(data)
        raw = data[start:start+length]
        tags[tag] = raw if kind not in (3,4) else struct.unpack('<'+('H' if kind==3 else 'I')*n, raw)
    w,h = tags[256][0],tags[257][0]
    assert tags[258] == (16,16,16) and tags[259] == (8,) and tags[317] == (2,)
    pixels = []
    for offset,length in zip(tags[273],tags[279]):
        raw = zlib.decompress(data[offset:offset+length])
        assert len(raw) % (w*6) == 0
        for y in range(len(raw)//(w*6)):
            previous = [0,0,0]
            for x in range(w):
                delta = struct.unpack_from('<HHH', raw, (y*w+x)*6)
                previous = [(a+b)&65535 for a,b in zip(previous,delta)]
                pixels.append(previous)
    assert len(pixels) == w*h
    return tags[34675],pixels


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--icc', required=True)
    ap.add_argument('--directory', required=True)
    ap.add_argument('--lcms')
    ap.add_argument('--profile-only', action='store_true')
    args = ap.parse_args()
    library = args.lcms or ctypes.util.find_library('lcms2')
    assert library, 'Little CMS required'
    cookie = os.add_dll_directory(str(Path(library).parent)) if os.name == 'nt' and Path(library).is_absolute() else None
    lib = C.CDLL(library)
    def api(name,result,*params):
        f=getattr(lib,name); f.restype=result; f.argtypes=params; return f
    ptr,uint,dbl=C.c_void_p,C.c_uint32,C.c_double
    open_profile=api('cmsOpenProfileFromMem',ptr,ptr,uint)
    close_profile=api('cmsCloseProfile',C.c_int,ptr)
    xyz=api('cmsCreateXYZProfile',ptr)()
    profile_bytes=Path(args.icc).read_bytes()
    tags={}
    for i in range(struct.unpack_from('>I',profile_bytes,128)[0]):
        name,offset,length=struct.unpack_from('>4sII',profile_bytes,132+i*12)
        assert offset % 4 == 0 and offset+length <= len(profile_bytes)
        tags[name]=profile_bytes[offset:offset+length]
    for name in (b'rTRC',b'gTRC',b'bTRC'):
        assert tags[name][:4] == b'para'
        assert struct.unpack_from('>H',tags[name],8)[0] == 0
        assert struct.unpack_from('>i',tags[name],12)[0] == 563*256
    blob=C.create_string_buffer(profile_bytes)
    profile=open_profile(blob,len(profile_bytes))
    assert profile and xyz
    rgb_double=(1<<22)|(4<<16)|(3<<3)
    xyz_double=(1<<22)|(9<<16)|(3<<3)
    transform=api('cmsCreateTransform',ptr,ptr,uint,ptr,uint,uint,uint)(profile,rgb_double,xyz,xyz_double,1,0x100)
    assert transform
    run=api('cmsDoTransform',None,ptr,ptr,ptr,uint)
    def cmm(rgb):
        src,dst=(dbl*3)(*rgb),(dbl*3)()
        run(transform,src,dst,1)
        return list(dst)
    max_profile=0.
    for level in [0,1/65535,.001,.01,.18,.5,1]:
        for rgb in ([level]*3,[level,0,0],[0,level,0],[0,0,level]):
            expected=mv(ADOBE,[v**(563/256) for v in rgb])
            max_profile=max(max_profile,*(abs(a-b) for a,b in zip(cmm(rgb),expected)))
    assert max_profile < 2e-5, max_profile
    files=0; samples=0; max_code=0.; max_xyz=0.; clipped=0; wrong_gamma_rejections=0
    cat02_rejections=0; near_zero_intervals=0
    if not args.profile_only:
        metadata=json.loads((Path(args.directory)/'engine_metadata.json').read_text())
        assert metadata['target_cctf_mode'] == 2
        engine_matrix=[metadata['matrix'][i:i+3] for i in (0,3,6)]
        pcs_matrix=[[sum(a*b for a,b in zip(row,col)) for col in zip(*ROMM)] for row in inverse(ADOBE)]
        for path in sorted(Path(args.directory).glob('*.csv')):
            if not path.name.startswith(('float_','u16_')): continue
            icc,pixels=read_tiff(path.with_suffix('.tif'))
            assert icc == profile_bytes, 'actual TIFF ICC differs from production profile'
            rows=list(csv.DictReader(path.open()))
            assert len(rows) == len(pixels)
            for row,pixel in zip(rows,pixels):
                encoded=[int(row[k])/65535 for k in ('r','g','b')]
                linear=[v/16 if v<1/32 else v**1.8 for v in encoded]
                target=mv(pcs_matrix,linear)
                exact_codes=[min(1,max(0,v))**(256/563)*65535 for v in target]
                wrong_codes=[min(1,max(0,v))**(1/2.2)*65535 for v in target]
                wrong_gamma_rejections += int(any(abs(a-b)>2 for a,b in zip(pixel,wrong_codes)))
                cat02_codes=[min(1,max(0,v))**(256/563)*65535 for v in mv(engine_matrix,linear)]
                cat02_rejections += int(any(abs(a-b)>2 for a,b in zip(pixel,cat02_codes)))
                for k,(observed,expected) in enumerate(zip(pixel,exact_codes)):
                    error=abs(observed-expected)
                    max_code=max(max_code,error)
                    if error>2:
                        # Only cancellation extremely near zero receives a
                        # float arithmetic error interval before gamma.
                        assert abs(target[k]) < 1e-5, (pixel,exact_codes,target)
                        bound=8*2**-23*sum(abs(a*b) for a,b in zip(pcs_matrix[k],linear))
                        lo=min(1,max(0,target[k]-bound))**(256/563)*65535
                        hi=min(1,max(0,target[k]+bound))**(256/563)*65535
                        assert lo-1 <= observed <= hi+1, (observed,lo,hi)
                        near_zero_intervals+=1
                clipped+=int(any(v<0 or v>1 for v in target))
                bounded=[min(1,max(0,v)) for v in target]
                actual=cmm([v/65535 for v in pixel])
                expected_xyz=mv(ADOBE,bounded)
                max_xyz=max(max_xyz,*(abs(a-b) for a,b in zip(actual,expected_xyz)))
                samples+=1
            files+=1
        assert files == 4 and clipped > 0 and wrong_gamma_rejections>0, (files,clipped,wrong_gamma_rejections)
        # Includes ICC s15Fixed16 colorants, float conversion and RGB16 rounding.
        # The former 256-code allowance cannot hide a chromatic-adaptation error.
        assert max_xyz < 4e-5, max_xyz
    summary=dict(lcms_version=api('cmsGetEncodedCMMversion',C.c_int)(),files=files,samples=samples,
                 clipped_samples=clipped,max_profile_xyz_error=max_profile,max_pixel_xyz_error=max_xyz,
                 max_code_difference_published_matrix=max_code,near_zero_intervals=near_zero_intervals,
                 wrong_gamma_2_2_rejections=wrong_gamma_rejections,engine_cat02_rejections=cat02_rejections)
    if not args.profile_only:
        (Path(args.directory)/'cmm_result.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary,indent=2))
    api('cmsDeleteTransform',None,ptr)(transform)
    close_profile(profile); close_profile(xyz)
    if cookie: cookie.close()


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Co-created with GPT (OpenAI), 2026-11-09. Experimental float3/FM reference.

Unclipped normalized values are intentional diagnostic output, not complete DNG
rendering. Adobe DNG 1.7.1.0 pp. 20,29,99,124 establish float32 encoding,
WhiteLevel, normalization and DNGBackwardVersion requirements:
https://helpx.adobe.com/content/dam/help/en/photoshop/pdf/DNG_Spec_1_7_1_0.pdf
The C++ side uses the actual public CameraLibRaw prepared FM transform. This
Python side independently derives raw camera-to-ProPhoto with Gaussian inverse.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess

WIDTH, HEIGHT = 64, 48
PROPHOTO_XYZ = [.7976749,.1351917,.0313534,.2880402,.7118741,.0000857,0,0,.8252100]
WHITE = [.96422,1,.82521]
FM = [.70,.20,.06422,.20,.75,.05,.05,.10,.67521]
NEUTRAL = [.5,1,.75]


def f32(value):
    return struct.unpack('<f',struct.pack('<f',value))[0]


def next_float(value):
    return struct.unpack('<f',struct.pack('<I',struct.unpack('<I',struct.pack('<f',value))[0]+1))[0]


def inverse(matrix):
    rows = [matrix[3*r:3*r+3]+[int(r==c) for c in range(3)] for r in range(3)]
    for col in range(3):
        pivot = max(range(col,3),key=lambda r:abs(rows[r][col]))
        assert abs(rows[pivot][col])>1e-12
        rows[col],rows[pivot] = rows[pivot],rows[col]
        divisor = rows[col][col]
        rows[col] = [value/divisor for value in rows[col]]
        for row in range(3):
            if row != col:
                factor = rows[row][col]
                rows[row] = [a-factor*b for a,b in zip(rows[row],rows[col])]
    return [value for row in rows for value in row[3:]]


def multiply(a,b):
    return [sum(a[r*3+k]*b[k*3+c] for k in range(3)) for r in range(3) for c in range(3)]


def transform(neutral):
    forward = [round(value*1000000)/1000000 for value in FM]
    for row in range(3):
        scale = WHITE[row]/sum(forward[row*3:row*3+3])
        for col in range(3): forward[row*3+col] *= scale
    maximum = max(neutral)
    return multiply(multiply(inverse(PROPHOTO_XYZ),forward),
                    [maximum/neutral[r] if r==c else 0 for r in range(3) for c in range(3)])


def payload(kind,values):
    if kind in (1,2,7): return bytes(values)
    if kind in (3,4): return struct.pack('<'+('H' if kind==3 else 'I')*len(values),*values)
    return b''.join(struct.pack('<ii' if kind==10 else '<II',round(value*1000000),1000000) for value in values)


def fixture(folder,name,maximum=2,black=(0,0,0),white=(1,1,1),orientation=1,
            neutral=NEUTRAL,invalid=None,legacy_comparison=False):
    neutral = [round(value*1000000)/1000000 for value in neutral]
    tags = {254:(4,[0]),256:(4,[WIDTH]),257:(4,[HEIGHT]),258:(3,[32,32,32]),
            259:(3,[1]),262:(3,[34892]),273:(4,[0]),274:(3,[orientation]),277:(3,[3]),
            278:(4,[HEIGHT]),279:(4,[WIDTH*HEIGHT*12]),284:(3,[1]),339:(3,[3,3,3]),
            271:(2,b'GPT\0'),272:(2,b'Isolated float LinearRaw\0'),50708:(2,b'GPT float LinearRaw\0'),
            50706:(1,[1,4,0,0]),50707:(1,[1,4,0,0]),50713:(3,[1,1]),50714:(5,black),
            50717:(4,white),50721:(10,[1,0,0,0,1,0,0,0,1]),50728:(5,neutral),50778:(3,[23]),
            50829:(4,[0,0,HEIGHT,WIDTH]),50964:(10,FM)}
    if invalid=='crop': tags[50719] = (5,[0,0])
    if invalid=='opcode': tags[51009] = (7,[0,0,0,0])
    if invalid=='profile': tags[50932] = (2,b'profile\0')
    if invalid=='no-fm': del tags[50964]
    if invalid=='repeat': tags[50713] = (3,[2,1])
    if invalid=='white': tags[50717] = (4,[0,1,1])
    if invalid=='missing-neutral': del tags[50728]
    if invalid=='zero-neutral': tags[50728] = (5,[0,1,.75])
    stored = []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            for c in range(3):
                middle = f32(black[c]+.18)
                bands = [black[c]-.125,black[c],middle,next_float(middle),black[c]+.5,
                         white[c],white[c]+.0001,maximum]
                value = bands[x//8]
                # A nonuniform colour area exercises orientation and matrix axes;
                # keep adjacent bands unperturbed so one-ULP inputs remain distinct.
                if 32<=x<40: value += y*.0005+c*.01
                stored.append(f32(value))
    if invalid=='nonfinite': stored[0] = float('nan')
    pixels = struct.pack('<'+'f'*len(stored),*stored)
    strip = 8+2+12*len(tags)+4
    tags[273] = (4,[strip])
    directory,extra = bytearray(),bytearray(pixels)
    for number,(kind,values) in sorted(tags.items()):
        data = payload(kind,values)
        count = len(data)//{1:1,2:1,3:2,4:4,5:8,7:1,10:8}[kind]
        if len(data)<=4: value = data.ljust(4,b'\0')
        else:
            value = struct.pack('<I',strip+len(extra))
            extra.extend(data)
            if len(extra)%2: extra.append(0)
        directory.extend(struct.pack('<HHI4s',number,kind,count,value))
    data = struct.pack('<2sHIH',b'II',42,8,len(tags))+directory+struct.pack('<I',0)+extra
    (folder/(name+'.dng')).write_bytes(data)
    return dict(name=name,maximum=maximum,black=black,white=white,orientation=orientation,
                neutral=neutral,invalid=invalid,legacy_comparison=legacy_comparison,
                sha256=hashlib.sha256(data).hexdigest()),stored


def oriented_reference(case,stored):
    orientation = case['orientation']
    ow,oh = (HEIGHT,WIDTH) if orientation>=5 else (WIDTH,HEIGHT)
    result,matrix = [0.]*(ow*oh*3),transform(case['neutral'])
    for y in range(HEIGHT):
        for x in range(WIDTH):
            destinations = [(x,y),(WIDTH-1-x,y),(WIDTH-1-x,HEIGHT-1-y),(x,HEIGHT-1-y),
                            (y,x),(HEIGHT-1-y,x),(HEIGHT-1-y,WIDTH-1-x),(y,WIDTH-1-x)]
            dx,dy = destinations[orientation-1]
            samples = [(stored[(y*WIDTH+x)*3+c]-case['black'][c])/
                       (case['white'][c]-case['black'][c]) for c in range(3)]
            for row in range(3): result[(dy*ow+dx)*3+row] = f32(sum(matrix[row*3+c]*samples[c] for c in range(3)))
    return result


if __name__=='__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder',type=Path)
    parser.add_argument('--probe',type=Path,required=True)
    args = parser.parse_args()
    args.folder.mkdir(parents=True,exist_ok=True)
    cases = [fixture(args.folder,'max'+str(maximum),maximum=maximum) for maximum in (2,4)]
    cases += [fixture(args.folder,'fractional',black=(.25,.25,.25),white=(4,4,4)),
              fixture(args.folder,'per-plane-black',black=(.25,.375,.125),white=(4,4,4)),
              fixture(args.folder,'per-plane-white',black=(.25,.375,.125),white=(4,3,2),legacy_comparison=True),
              fixture(args.folder,'per-plane-white-orientation6',black=(.25,.375,.125),white=(4,3,2),orientation=6),
              fixture(args.folder,'per-plane-white-permuted',black=(.125,.25,.375),white=(2,4,3),legacy_comparison=True),
              fixture(args.folder,'nonunit-green-neutral',neutral=(.25,.625,1)),
              fixture(args.folder,'scaled-neutral-white',black=(.25,.375,.125),white=(4,3,2),
                      neutral=(.25,.5,.375)),
              fixture(args.folder,'rational-neutral-white',black=(.25,.375,.125),white=(4,3,2),
                      neutral=(1/3,.875,1),legacy_comparison=True)]
    cases += [fixture(args.folder,'orientation-'+str(n),orientation=n) for n in range(2,9)]
    cases += [fixture(args.folder,'unsupported-'+name,invalid=name)
              for name in ('crop','opcode','profile','no-fm','repeat','white','nonfinite','missing-neutral','zero-neutral')]
    records,outputs,expected_failures = [],{},[]
    for case,stored in cases:
        path = args.folder/(case['name']+'.f32')
        if case['legacy_comparison']:
            # Retain the independently derived mismatch rather than changing
            # AsShotNeutral to follow LibRaw's open.cpp WB rewrite.
            diagnostic = json.loads(subprocess.check_output([str(args.probe),str(args.folder/(case['name']+'.dng')),
                                      str(path),'--legacy-open-wb'],text=True))
            assert diagnostic['ok'] and diagnostic['legacy_open_wb'],diagnostic
            values = list(struct.unpack('<'+'f'*(path.stat().st_size//4),path.read_bytes()))
            error = max(abs(a-b) for a,b in zip(values,oriented_reference(case,stored)))
            assert error>0.1,error
            expected_failures.append(dict(case=case['name'],maximum_absolute_error=error,
                diagnostic=diagnostic,dependency='LibRaw open.cpp folds per-plane white/black delta into cam_mul'))
            path.unlink()
        row = json.loads(subprocess.check_output([str(args.probe),str(args.folder/(case['name']+'.dng')),str(path)],text=True))
        assert row['ok'] == (case['invalid'] is None),(case,row)
        if row['ok']:
            result = list(struct.unpack('<'+'f'*(path.stat().st_size//4),path.read_bytes()))
            expected = oriented_reference(case,stored)
            error = max(abs(a-b) for a,b in zip(result,expected))
            assert len(result)==len(expected) and error<2e-6,(case,error)
            assert row['input_float_bytes']==row['output_float_bytes']==WIDTH*HEIGHT*12
            assert row['wb_source']=='original_rational_AsShotNeutral' and not row['legacy_open_wb'],row
            assert row['source_neutral']==case['neutral'],(case,row)
            expected_wb = [max(case['neutral'])/v for v in case['neutral']]
            assert max(abs(a-b) for a,b in zip(row['source_wb'],expected_wb))<2e-6,(case,row)
            row['max_absolute_error'] = error
            outputs[case['name']] = result
        row['case'] = case
        records.append(row)
    unchanged = [c for y in range(HEIGHT) for x in range(56) for c in range((y*WIDTH+x)*3,(y*WIDTH+x)*3+3)]
    assert all(outputs['max2'][i]==outputs['max4'][i] for i in unchanged)
    assert outputs['scaled-neutral-white']==outputs['per-plane-white']
    gaps = []
    for name in ('max2','fractional','per-plane-black'):
        out = outputs[name]
        gap = max(abs(out[(24*WIDTH+28)*3+c]-out[(24*WIDTH+20)*3+c]) for c in range(3))
        assert gap>0,(name,gap)
        gaps.append(dict(case=name,adjacent_output_gap=gap))
    summary = dict(cases=len(cases),accepted=sum(r['ok'] for r in records),
        rejected=sum(not r['ok'] for r in records),passed=True,
        maximum_absolute_error=max(r.get('max_absolute_error',0) for r in records),
        identical_area_component_count=len(unchanged),max2_max4_identical_area_byte_equal=True,
        scaled_AsShotNeutral_output_byte_equal=True,
        adjacent=gaps,expected_failure_count=len(expected_failures),raw_plane_plus_output_bytes_per_pixel=24)
    (args.folder/'results.json').write_text(json.dumps(dict(summary=summary,results=records,
        expected_failures=expected_failures),indent=2)+'\n',encoding='utf-8')
    print(json.dumps(summary))

#!/usr/bin/env python3
"""Co-created with GPT (OpenAI), 2026-11-09. Float DNG quantization evidence.

Primary source checked directly, Adobe DNG 1.7.1.0 (September 2023):
https://helpx.adobe.com/content/dam/help/en/photoshop/pdf/DNG_Spec_1_7_1_0.pdf
Pages 16/20: DNG 1.4 permits SampleFormat 3, BitsPerSample 16/24/32.
Page 29: WhiteLevel SHORT/LONG, float default 1.0. Page 99: normalize by
WhiteLevel minus maximum black; upper clipping recommended by that model,
negative clipping allowed, early negative preservation recommended. Page 124:
float main IFD requires DNGBackwardVersion >= 1.4. This is a storage/unpack
precision experiment, not a claim that unclipped HDR is the SDK rendering.
LibRaw fixed baseline: b93f6e45c194f5df9b02a43b1af9a54b4f41f33f,
src/decoders/fp_dng.cpp convertFloatToInt and uncompressed_fp_dng_load_raw.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess

WIDTH, HEIGHT = 64, 48
FM = [.7976749, .1351917, .0313534, .2880402, .7118741, .0000857, 0, 0, .8252100]


def f32(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]


def tag(tags, number, kind, values):
    if kind in (1, 2): payload = bytes(values)
    elif kind == 3: payload = struct.pack('<'+'H'*len(values), *values)
    elif kind == 4: payload = struct.pack('<'+'I'*len(values), *values)
    else:
        payload = b''.join(struct.pack('<ii' if kind == 10 else '<II',
                                      round(value*1000000), 1000000) for value in values)
    tags[number] = (kind, len(payload)//{1:1, 2:1, 3:2, 4:4, 5:8, 10:8}[kind], payload)


def fixture(folder, linear, maximum, black=0, white=1):
    name = ('linear' if linear else 'bayer')+'-max'+str(maximum)+'-black'+str(black)
    adjacent = struct.unpack('<f', struct.pack('<I', struct.unpack('<I', struct.pack('<f', .18))[0]+1))[0]
    values = [f32(value+black) for value in [-.125, 0, .18, adjacent, .5, 1, 1.0001, maximum]]
    spp, tags = (3 if linear else 1), {}
    image = {254:(4,[0]),256:(4,[WIDTH]),257:(4,[HEIGHT]),258:(3,[32]*spp),
             259:(3,[1]),262:(3,[34892 if linear else 32803]),273:(4,[0]),
             277:(3,[spp]),278:(4,[HEIGHT]),279:(4,[WIDTH*HEIGHT*spp*4]),
             284:(3,[1]),274:(3,[1]),339:(3,[3]*spp),50829:(4,[0,0,HEIGHT,WIDTH]),
             50706:(1,[1,4,0,0]),50707:(1,[1,4,0,0]),50713:(3,[1,1]),
             50714:(5,[black]*spp),50717:(4,[white]*spp),
             50721:(10,[1,0,0,0,1,0,0,0,1]),50728:(5,[1,1,1]),50778:(3,[23]),
             50964:(10,FM),271:(2,b'GPT\0'),272:(2,b'Float precision contract\0'),
             50708:(2,b'GPT Float precision contract\0')}
    if not linear:
        image.update({33421:(3,[2,2]),33422:(1,[0,1,1,2]),50710:(1,[0,1,2]),50711:(3,[1])})
    for number, (kind, vals) in image.items(): tag(tags, number, kind, vals)
    pixels = b''.join(struct.pack('<f', values[x//8]) for y in range(HEIGHT)
                      for x in range(WIDTH) for channel in range(spp))
    strip = 8+2+12*len(tags)+4
    tag(tags,273,4,[strip])
    result = bytearray(struct.pack('<2sHIH',b'II',42,8,len(tags)))
    extra = bytearray(pixels)
    for number, (kind, count, payload) in sorted(tags.items()):
        if len(payload) <= 4: pointer = payload.ljust(4,b'\0')
        else:
            pointer = struct.pack('<I',strip+len(extra))
            extra.extend(payload)
            if len(extra)%2: extra.append(0)
        result.extend(struct.pack('<HHI4s',number,kind,count,pointer))
    result.extend(struct.pack('<I',0))
    result.extend(extra)
    path = folder/(name+'.dng')
    path.write_bytes(result)
    return dict(name=name, linear=linear, black=black, white=white, values=values,
                sha256=hashlib.sha256(result).hexdigest())


def check(case, folder, probe):
    row = json.loads(subprocess.check_output([str(probe),str(folder/(case['name']+'.dng'))],text=True))
    original, converted = row['preserved_unpack'], row['default_unpack']
    assert original['open'] == converted['open'] == original['unpack'] == converted['unpack'] == 0, row
    assert original['have_float'] and not converted['have_float'], row
    assert original['values'] == case['values'], (case,row)
    assert original['preserved_process_status'] == -4, row
    tmax = max(case['white'], max(case['values']), 1)
    multiplier = f32(16383/tmax) if tmax < 4096 or tmax > 32767 else 1
    expected = [int(f32(max(value,0)*multiplier)) for value in case['values']]
    assert converted['values'] == expected, (case,expected,row)
    assert converted['fnorm'] == multiplier, (case,row)
    assert row['compatible']['ok'] and row['compatible']['finite'], row
    # Production explicitly rejects floating sources even after integer unpack.
    assert not row['headroom']['ok'] and row['headroom']['unchanged'], row
    assert 'only conventional three-colour Bayer RAW' in row['headroom']['error'], row
    normalized = [(value-case['black'])/(case['white']-case['black']) for value in case['values']]
    restored = [value/multiplier for value in expected]
    row.update(case=case, reference=dict(multiplier=multiplier, quantized=expected,
        restored_storage_values=restored, max_storage_error=max(abs(a-b) for a,b in zip(restored,case['values'])),
        max_nonnegative_storage_error=max(abs(a-b) for a,b in zip(restored,case['values']) if b >= 0),
        fixed_metadata_linear=normalized, clipped_linear=[min(1,max(0,v)) for v in normalized],
        adjacent_input_gap=case['values'][3]-case['values'][2],
        adjacent_quantized_gap=expected[3]-expected[2]))
    return row


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder',type=Path)
    parser.add_argument('--probe',type=Path,required=True)
    args = parser.parse_args()
    args.folder.mkdir(parents=True,exist_ok=True)
    cases = [fixture(args.folder,linear,maximum) for linear in (False,True) for maximum in (2,4)]
    cases += [fixture(args.folder,linear,2,black=.25,white=4) for linear in (False,True)]
    rows = [check(case,args.folder,args.probe) for case in cases]
    comparisons = []
    for linear in (False,True):
        a,b = [row for row in rows if row['case']['linear']==linear and row['case']['black']==0]
        assert a['case']['values'][:-1] == b['case']['values'][:-1]
        for policy in ('compatible','headroom'):
            if not a[policy]['ok']: continue
            comparisons.append(dict(linear=linear,policy=policy,
                identical_input_roi=a['case']['values'][2], a_roi=a[policy]['roi'],b_roi=b[policy]['roi'],
                b_over_a=[b[policy]['roi'][c]/a[policy]['roi'][c] for c in range(3)]))
    summary = dict(cases=len(cases),passed=True,comparisons=comparisons,
                   preserved_process_statuses=[row['preserved_unpack']['preserved_process_status'] for row in rows])
    (args.folder/'results.json').write_text(json.dumps(dict(summary=summary,results=rows),indent=2)+'\n',encoding='utf-8')
    print(json.dumps(summary))

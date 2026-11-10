#!/usr/bin/env python3
"""Co-created with GPT. Bounded main-profile DNG metadata integration fixtures."""
import argparse
import json
from pathlib import Path
import struct
import subprocess

FM=[.7976749,.1351917,.0313534,.2880402,.7118741,.0000857,0,0,.8252100]


def tag(tags, number, kind, values):
    if kind in (1,2): payload=bytes(values)
    elif kind==3: payload=struct.pack('<'+'H'*len(values),*values)
    elif kind==4: payload=struct.pack('<'+'I'*len(values),*values)
    elif kind in (5,10):
        payload=b''.join(struct.pack('<II' if kind==5 else '<ii',round(v*1000000),1000000) for v in values)
    else: raise ValueError(kind)
    tags[number]=[kind,len(payload)//{1:1,2:1,3:2,4:4,5:8,10:8}[kind],payload]


def write_tiff(path, directories, pixels):
    starts=[]
    offset=8
    for tags in directories:
        starts.append(offset)
        offset+=2+12*len(tags)+4
    if len(directories)>1: tag(directories[0],330,4,[starts[1]])
    strips=[]
    for tags,payload in zip(directories,pixels):
        strips.append(offset)
        tag(tags,273,4,[offset])
        offset+=len(payload)+(len(payload)%2)
    data=bytearray(offset)
    struct.pack_into('<2sHI',data,0,b'II',42,8)
    for index,tags in enumerate(directories):
        struct.pack_into('<H',data,starts[index],len(tags))
        position=starts[index]+2
        for number,(kind,count,payload) in sorted(tags.items()):
            if len(payload)<=4: value=payload.ljust(4,b'\0')
            else:
                value=struct.pack('<I',len(data))
                data.extend(payload)
                if len(data)%2:data.append(0)
            struct.pack_into('<HHI4s',data,position,number,kind,count,value)
            position+=12
        data[strips[index]:strips[index]+len(pixels[index])]=pixels[index]
    path.write_bytes(data)


def add(tags, number, kind, values):
    if kind == 11:
        tags[number] = [kind, len(values), struct.pack('<'+'f'*len(values), *values)]
    else:
        tag(tags, number, kind, values)


def generate(folder):
    folder.mkdir(parents=True, exist_ok=True)
    cases = []
    specifications = [('missing', {}), ('all', {}), ('black-auto', {'black': 0}),
        ('baseline-negative', {'baseline': -.75}), ('offset-negative', {'offset': -.25}),
        ('tone-endpoint-compat', {'curve': [0, 0, .25, .15, .75, .9, 1, .997156024]}),
        ('tone-maximum', {'curve': [v for i in range(256) for v in (i/255, i/255)]}),
        ('tone-over-budget', {'curve': [v for i in range(257) for v in (i/256, i/256)], 'invalid': 'tone'}),
        ('tone-odd', {'curve': [0, 0, 1, 1, .5], 'invalid': 'tone'}),
        ('tone-too-short', {'curve': [0, 0], 'invalid': 'tone'}),
        ('tone-huge-count', {'huge': True, 'invalid': 'tone'}),
        ('tone-type', {'curve_type': 10, 'invalid': 'tone'}),
        ('tone-nan', {'curve': [0, 0, .5, float('nan'), 1, 1], 'invalid': 'tone'}),
        ('tone-inf', {'curve': [0, 0, .5, float('inf'), 1, 1], 'invalid': 'tone'}),
        ('tone-range', {'curve': [0, 0, .5, 1.1, 1, 1], 'invalid': 'tone'}),
        ('tone-order', {'curve': [0, 0, .5, .5, .5, .7, 1, 1], 'invalid': 'tone'}),
        ('black-value', {'black': 2, 'invalid': 'black'}),
        ('black-type', {'black_type': 3, 'invalid': 'black'}),
        ('offset-type', {'offset_type': 5, 'invalid': 'offset'}),
        ('offset-denominator', {'offset_zero': True, 'invalid': 'offset'}),
        ('baseline-denominator', {'baseline_zero': True, 'invalid': 'baseline'}),
        ('extra-profile', {'extra': True}),
        ('subifd-fallback', {'sub': 'fallback'}),
        ('subifd-override', {'sub': 'override'}),
        ('subifd-mixed-profile', {'sub': 'mixed'}),
        ('subifd-invalid-override', {'sub': 'invalid', 'invalid': 'tone'})]
    for name, options in specifications:
        image, profile = {}, {}
        w, h = 64, 48
        for key, kind, values in [(254,4,[0]),(256,4,[w]),(257,4,[h]),(258,3,[16]),
            (259,3,[1]),(262,3,[32803]),(273,4,[0]),(277,3,[1]),(278,4,[h]),
            (279,4,[w*h*2]),(284,3,[1]),(274,3,[1]),(33421,3,[2,2]),
            (33422,1,[0,1,1,2]),(50710,1,[0,1,2]),(50711,3,[1]),
            (50713,3,[1,1]),(50714,5,[64]),(50717,4,[4095])]: add(image,key,kind,values)
        for key,kind,values in [(271,2,b'GPT\0'),(272,2,b'Render Metadata\0'),
            (50708,2,b'GPT Render Metadata\0'),(50706,1,[1,4,0,0]),(50707,1,[1,1,0,0]),
            (50721,10,[1,0,0,0,1,0,0,0,1]),(50778,3,[21]),(50964,10,FM),
            (50728,5,[1,1,1])]: add(profile,key,kind,values)
        curve = options.get('curve', [0,0,.25,.12,.75,.9,1,1])
        if name != 'missing':
            add(profile,50730,10,[options.get('baseline', .5)])
            add(profile,51109,options.get('offset_type',10),[options.get('offset', .25)])
            add(profile,51110,options.get('black_type',4),[options.get('black',1)])
            add(profile,50940,options.get('curve_type',11),curve)
        if options.get('baseline_zero'): profile[50730]=[10,1,struct.pack('<ii',1,0)]
        if options.get('huge'): profile[50940][1]=0x7fffffff
        if options.get('offset_zero'): profile[51109]=[10,1,struct.pack('<ii',1,0)]
        if options.get('extra'): add(profile,50933,4,[0x7fffffff])
        pixels = struct.pack('<H',1024)*(w*h)
        mode = options.get('sub')
        expected_curve = [] if name == 'missing' or options.get('invalid') == 'tone' else curve
        if mode:
            root = dict(profile)
            for key,kind,values in [(254,4,[1]),(256,4,[1]),(257,4,[1]),(258,3,[8,8,8]),
                (259,3,[1]),(262,3,[2]),(273,4,[0]),(277,3,[3]),(278,4,[1]),(279,4,[3]),(330,4,[0])]: add(root,key,kind,values)
            if mode == 'override':
                for key in (50721,50778,50964,50940,51109,51110):
                    image[key] = root[key]
                expected_curve = [0,0,.5,.7,1,1]
                add(image,50940,11,expected_curve)
            elif mode == 'mixed': add(image,50940,11,curve)
            elif mode == 'invalid': add(image,50940,11,[0,0,.5,.5,.5,.7,1,1])
            write_tiff(folder/(name+'.dng'),[root,image],[b'\0\0\0',pixels])
        else:
            write_tiff(folder/(name+'.dng'),[{**image,**profile}],[pixels])
        cases.append(dict(name=name, missing=name=='missing', invalid=options.get('invalid'),
            extra=bool(options.get('extra')), mismatch=mode in ('mixed','invalid'),
            baseline=options.get('baseline',.5), offset=options.get('offset',.25),
            black=options.get('black',1), curve=expected_curve))
    return cases


def run(probe, file):
    return json.loads(subprocess.check_output([str(probe),str(file)], text=True))


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('folder',type=Path)
    parser.add_argument('--probe',required=True,type=Path)
    parser.add_argument('--baseline-probe',type=Path)
    args=parser.parse_args()
    records=[]
    for case in generate(args.folder):
        result=run(args.probe,args.folder/(case['name']+'.dng'))
        assert result['is_dng'], case
        for key in ('baseline','offset','black','tone'):
            assert result[key+'_present'] == (not case['missing']), (case,result)
            assert result[key+'_invalid'] == (case['invalid']==key), (case,result)
        for key in ('baseline','offset','black'):
            if not case['missing'] and case['invalid'] != key:
                assert abs(result[key]-case[key]) < 1e-7,(case,result)
        assert result['extra_profiles']==case['extra'],(case,result)
        assert result['profile_mismatch']==case['mismatch'],(case,result)
        flattened=[v for pair in result['tone'] for v in pair]
        assert len(flattened)==len(case['curve']),(case,result)
        assert all(abs(a-b)<1e-7 for a,b in zip(flattened,case['curve'])),(case,result)
        if args.baseline_probe:
            before=run(args.baseline_probe,args.folder/(case['name']+'.dng'))
            assert before['pixel_fnv64']==result['pixel_fnv64'],(case,before,result)
        records.append(dict(name=case['name'],**result))
    (args.folder/'results.json').write_text(json.dumps(records,indent=2)+'\n')
    print(f'DNG render metadata PASS cases={len(records)} bounded=256pairs selected_ifd=profile_safe pixel_baseline={bool(args.baseline_probe)}')


if __name__=='__main__': main()

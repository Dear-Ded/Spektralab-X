#!/usr/bin/env python3
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11. Public synthetic fixtures only.
"""Deflate float DNG support and unchanged adjacent integer decoder controls."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import zlib
import dng_float_precision_fixtures as storage


def rewrite(path, updates, pixels):
    data = bytearray(path.read_bytes())
    offset = struct.unpack_from('<I', data, 4)[0]
    count = struct.unpack_from('<H', data, offset)[0]
    entries = {struct.unpack_from('<H', data, offset+2+i*12)[0]:
               bytes(data[offset+2+i*12:offset+14+i*12]) for i in range(count)}
    def entry(number, kind, value):
        payload = struct.pack('<H' if kind == 3 else '<I', value).ljust(4, b'\0')
        entries[number] = struct.pack('<HHI4s', number, kind, 1, payload)
    pixel_offset = len(data)
    data.extend(pixels)
    updates = {**updates, 273: (4, pixel_offset), 279: (4, len(pixels))}
    for number, (kind, value) in updates.items(): entry(number, kind, value)
    if len(data) % 2: data.append(0)
    directory = len(data)
    struct.pack_into('<I', data, 4, directory)
    data.extend(struct.pack('<H', len(entries)))
    data.extend(b''.join(entries[n] for n in sorted(entries)))
    data.extend(struct.pack('<I', 0))
    path.write_bytes(data)


def predicted_float(pixels, spp):
    row_bytes = storage.WIDTH*spp*4
    result = bytearray()
    for y in range(storage.HEIGHT):
        row = pixels[y*row_bytes:(y+1)*row_bytes]
        # TIFF floating predictor: most-significant byte planes first, then
        # horizontal byte differences with one component stride across planes.
        shuffled = b''.join(row[n::4] for n in (3, 2, 1, 0))
        delta = bytearray(shuffled)
        for i in range(spp, len(delta)): delta[i] = (shuffled[i]-shuffled[i-spp]) & 255
        result.extend(delta)
    return zlib.compress(result)


def lossless_jpeg():
    def segment(marker, data): return b'\xff'+bytes([marker])+struct.pack('>H',len(data)+2)+data
    # A constant 12-bit CFA at the JPEG initial predictor value 2048. Every
    # lossless difference is zero, encoded by the one-bit DC category-0 code.
    dht = bytes([0, 1]+[0]*15+[0])
    sof = struct.pack('>BHHB',12,storage.HEIGHT,storage.WIDTH,1)+bytes([1,0x11,0])
    sos = bytes([1,1,0,1,0,0])
    bits = b'\0'*(storage.WIDTH*storage.HEIGHT//8)
    return b'\xff\xd8'+segment(0xc4,dht)+segment(0xc3,sof)+segment(0xda,sos)+bits+b'\xff\xd9'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder',type=Path)
    parser.add_argument('--baseline',type=Path,required=True)
    parser.add_argument('--candidate',type=Path,required=True)
    args=parser.parse_args()
    args.folder.mkdir(parents=True,exist_ok=True)
    cases=[]
    for linear in (False,True):
        case=storage.fixture(args.folder,linear,2)
        plain=args.folder/(case['name']+'.dng')
        original=plain.read_bytes()
        count=struct.unpack_from('<H',original,8)[0]
        at=next(10+i*12 for i in range(count) if struct.unpack_from('<H',original,10+i*12)[0]==273)
        start=struct.unpack_from('<I',original,at+8)[0]
        spp=3 if linear else 1
        floats=original[start:start+storage.WIDTH*storage.HEIGHT*spp*4]
        compressed=args.folder/('deflate-'+case['name']+'.dng')
        compressed.write_bytes(original)
        rewrite(compressed,{259:(3,8),317:(3,3)},predicted_float(floats,spp))
        expected=floats
        cases.extend([(plain,False,expected),(compressed,True,expected)])
    original=cases[0][0].read_bytes()
    integer=args.folder/'integer-uncompressed.dng'
    integer.write_bytes(original)
    expected=struct.pack('<H',2048)*(storage.WIDTH*storage.HEIGHT)
    rewrite(integer,{258:(3,16),339:(3,1),50717:(4,4095)},expected)
    jpeg=args.folder/'integer-lossless-jpeg.dng'
    jpeg.write_bytes(original)
    rewrite(jpeg,{258:(3,12),339:(3,1),259:(3,7),50717:(4,4095)},lossless_jpeg())
    cases.extend([(integer,False,expected),(jpeg,False,expected)])
    records=[]
    for path,deflated,expected in cases:
        record={'case':path.stem,'deflated':deflated,'fixture_sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
        for name,probe in [('baseline',args.baseline),('candidate',args.candidate)]:
            out=args.folder/(path.stem+'-'+name+'.raw')
            row=json.loads(subprocess.check_output([str(probe),str(path),str(out)],text=True))
            if not deflated or name=='candidate':
                assert row['open']==row['unpack']==row['process']==row['memory']==0,(path,name,row)
                assert out.read_bytes()==expected,(path,name,'unpacked samples differ')
                row['unpacked_sha256']=hashlib.sha256(out.read_bytes()).hexdigest()
                row['processed_sha256']=hashlib.sha256(Path(str(out)+'.processed').read_bytes()).hexdigest()
            else:
                assert row['open']==0 and row['unpack']!=0 and row['process']!=0,(path,name,row)
            assert bool(row['capabilities'] & 64)==(name=='candidate'),(path,name,row)
            record[name]=row
        if not deflated:
            assert record['baseline']['processed_sha256']==record['candidate']['processed_sha256'],record
        records.append(record)
    for row in records:
        if row['deflated']:
            plain=next(r for r in records if r['case']==row['case'].removeprefix('deflate-'))
            assert row['candidate']['processed_sha256']==plain['candidate']['processed_sha256'],row
    result={'cases':len(records),'passed':True,'records':records}
    (args.folder/'results.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'cases':len(records),'passed':True,'deflated_cases':sum(r['deflated'] for r in records),
                      'unchanged_controls':sum(not r['deflated'] for r in records)}))


if __name__=='__main__': main()

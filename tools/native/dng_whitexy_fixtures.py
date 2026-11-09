#!/usr/bin/env python3
"""Small uncompressed DNG fixtures and an independent SetWhiteXY reference.

Uses only the standard library. Reference: Adobe DNG SDK dng_color_spec.cpp
SetWhiteXY/FindXYZtoCamera and dng_temperature.cpp, revision
de700ad461e35af50b28b861943a0b0753b10929 in android.googlesource.com/platform/external/dng_sdk.
Synthetic tests do not constitute a phone-camera colour accuracy measurement.
"""
import argparse
import filecmp
import json
import math
from pathlib import Path
import struct
import subprocess

CM = [[.70, .10, .06, .15, .80, .02, .03, .10, .80],
      [.65, .13, .08, .12, .85, .04, .02, .08, .88]]
CC = [[1.08, .02, 0, 0, .94, .01, .01, 0, 1.03],
      [.97, .01, .01, .02, 1.04, 0, 0, .02, .98]]
IDENTITY = [1, 0, 0, 0, 1, 0, 0, 0, 1]
ANALOG = [1.08, .97, 1.03]
FM = [.7976749, .1351917, .0313534, .2880402, .7118741, .0000857, 0, 0, .8252100]
PROPHOTO_XYZ = [.7976749, .1351917, .0313534, .2880402, .7118741, .0000857, 0, 0, .8252100]
PCS_WHITE = [.96422, 1, .82521]
BRADFORD = [.8951, .2664, -.1614, -.7502, 1.7135, .0367, .0389, -.0685, 1.0296]
LINES = [
    (0,.18006,.26352,-.24341),(10,.18066,.26589,-.25479),
    (20,.18133,.26846,-.26876),(30,.18208,.27119,-.28539),
    (40,.18293,.27407,-.30470),(50,.18388,.27709,-.32675),
    (60,.18494,.28021,-.35156),(70,.18611,.28342,-.37915),
    (80,.18740,.28668,-.40955),(90,.18880,.28997,-.44278),
    (100,.19032,.29326,-.47888),(125,.19462,.30141,-.58204),
    (150,.19962,.30921,-.70471),(175,.20525,.31647,-.84901),
    (200,.21142,.32312,-1.0182),(225,.21807,.32909,-1.2168),
    (250,.22511,.33439,-1.4512),(275,.23247,.33904,-1.7298),
    (300,.24010,.34308,-2.0637),(325,.24792,.34655,-2.4681),
    (350,.25591,.34951,-2.9641),(375,.26400,.35200,-3.5814),
    (400,.27218,.35407,-4.3633),(425,.28039,.35577,-5.3762),
    (450,.28863,.35714,-6.7262),(475,.29685,.35823,-8.5955),
    (500,.30505,.35907,-11.324),(525,.31320,.35968,-15.628),
    (550,.32129,.36011,-23.325),(575,.32931,.36038,-40.770),
    (600,.33724,.36051,-116.45)]


def temperature(x, y):
    u, v = 2*x/(1.5-x+6*y), 3*y/(1.5-x+6*y)
    last = 0
    for index in range(1, len(LINES)):
        r, lu, lv, slope = LINES[index]
        distance = (v-lv-slope*(u-lu))/math.hypot(1, slope)
        if distance <= 0 or index == len(LINES)-1:
            distance = max(0, -distance)
            weight = 0 if index == 1 else distance/(last+distance)
            return 1e6/(LINES[index-1][0]*weight+r*(1-weight))
        last = distance
    raise AssertionError("temperature interval")


def multiply(a, b):
    return [sum(a[r*3+k]*b[k*3+c] for k in range(3)) for r in range(3) for c in range(3)]


def inverse(matrix):
    # Independent row reduction with partial pivoting, not a cofactor inverse.
    rows = [matrix[3*r:3*r+3]+IDENTITY[3*r:3*r+3] for r in range(3)]
    for column in range(3):
        pivot = max(range(column, 3), key=lambda r: abs(rows[r][column]))
        assert abs(rows[pivot][column]) > 1e-12, matrix
        rows[column], rows[pivot] = rows[pivot], rows[column]
        divisor = rows[column][column]
        rows[column] = [value/divisor for value in rows[column]]
        for row in range(3):
            if row != column:
                factor = rows[row][column]
                rows[row] = [a-factor*b for a, b in zip(rows[row], rows[column])]
    return [value for row in rows for value in row[3:]]


def vector(matrix, values):
    return [sum(matrix[3*r+c]*values[c] for c in range(3)) for r in range(3)]


def diagonal(values):
    return [values[r] if r == c else 0 for r in range(3) for c in range(3)]


def output_reference(xy, dual, calibrated, forward, camera, neutral):
    if forward:
        kelvin = temperature(*xy)
        weight = min(1, max(0, (1/kelvin-1/6500)/(1/2850-1/6500))) if dual else 1
        calibration = [weight*a+(1-weight)*b for a, b in zip(*CC)] if calibrated else IDENTITY
        individual_to_reference = inverse(multiply(diagonal(ANALOG), calibration))
        reference_neutral = vector(individual_to_reference, neutral)
        # The file contains six-place SRATIONAL values, not the source constants.
        forward_matrix = [round(value*1000000)/1000000 for value in FM]
        for row in range(3):
            total = sum(forward_matrix[3*row:3*row+3])
            for column in range(3):
                forward_matrix[3*row+column] *= PCS_WHITE[row]/total
        camera_to_pcs = multiply(multiply(forward_matrix,
            diagonal([1/value for value in reference_neutral])), individual_to_reference)
    else:
        x, y = xy
        source_xyz = [x/y, 1, (1-x-y)/y]
        pcs_lms, source_lms = vector(BRADFORD, PCS_WHITE), vector(BRADFORD, source_xyz)
        cone_scale = [min(10, max(.1, max(0, source)/max(0, pcs) if pcs > 0 else 10))
                      for pcs, source in zip(pcs_lms, source_lms)]
        adaptation = multiply(multiply(inverse(BRADFORD), diagonal(cone_scale)), BRADFORD)
        pcs_to_camera = multiply(camera, adaptation)
        saturation = max(vector(pcs_to_camera, PCS_WHITE))
        camera_to_pcs = inverse([value/saturation for value in pcs_to_camera])
    return multiply(multiply(inverse(PROPHOTO_XYZ), camera_to_pcs), diagonal(neutral))


def reference(xy, dual=True, calibrated=True):
    kelvin = temperature(*xy)
    weight = min(1, max(0, (1/kelvin-1/6500)/(1/2850-1/6500))) if dual else 1
    ends = []
    for index in range(2):
        endpoint = multiply(CC[index] if calibrated else IDENTITY, CM[index])
        ends.append([endpoint[i]*ANALOG[i//3] for i in range(9)])
    matrix = [weight*a+(1-weight)*b for a, b in zip(*ends)]
    x, y = xy
    xyz = [x/y, 1, (1-x-y)/y]
    neutral = [sum(matrix[r*3+c]*xyz[c] for c in range(3)) for r in range(3)]
    maximum = max(neutral)
    return kelvin, matrix, [min(1, max(.001, n/maximum)) for n in neutral]


def numbers(kind, values):
    if kind == 1 or kind == 2 or kind == 7:
        return bytes(values)
    if kind == 3:
        return struct.pack('<'+'H'*len(values), *values)
    if kind == 4:
        return struct.pack('<'+'I'*len(values), *values)
    if kind in (5, 10):
        fmt = '<II' if kind == 5 else '<ii'
        return b''.join(struct.pack(fmt, round(v*1000000), 1000000) for v in values)
    raise ValueError(kind)


def tag(tags, number, kind, values):
    payload = numbers(kind, values)
    size = {1:1, 2:1, 3:2, 4:4, 5:8, 7:1, 10:8}[kind]
    tags[number] = [kind, len(payload)//size, payload]


def write_tiff(path, directories, pixels):
    # Reserve every IFD before placing variable data and strips.
    starts, offset = [], 8
    for tags in directories:
        starts.append(offset)
        offset += 2+12*len(tags)+4
    if len(directories) > 1:
        tag(directories[0], 330, 4, [starts[1]])
    strips = []
    for index, tags in enumerate(directories):
        strips.append(offset)
        offset += len(pixels[index])
        if offset % 2: offset += 1
        tag(tags, 273, 4, [strips[-1]])
    result = bytearray(offset)
    result[:8] = struct.pack('<2sHI', b'II', 42, starts[0])
    for index, tags in enumerate(directories):
        struct.pack_into('<H', result, starts[index], len(tags))
        position = starts[index]+2
        for number, (kind, count, payload) in sorted(tags.items()):
            if len(payload) <= 4:
                value = payload.ljust(4, b'\0')
            else:
                data_offset = len(result)
                result.extend(payload)
                if len(result) % 2: result.append(0)
                value = struct.pack('<I', data_offset)
            struct.pack_into('<HHI4s', result, position, number, kind, count, value)
            position += 12
        result[strips[index]:strips[index]+len(pixels[index])] = pixels[index]
    path.write_bytes(result)


def fixture(folder, name, xy=(.3127, .3290), dual=True, forward=True,
            signature='missing', linear=False, subifd=False, source='xy', invalid=None):
    valid = invalid is None
    calibrated = signature not in ('different', 'camera_only', 'profile_only')
    k, matrix, neutral = reference(xy, dual, calibrated) if valid else reference((.3127,.3290),dual)
    image, metadata = {}, {}
    width, height = 64, 48
    spp = 3 if linear else 1
    image_tags = {254:(4,[0]),256:(4,[width]),257:(4,[height]),
                  258:(3,[16]*spp),259:(3,[1]),262:(3,[34892 if linear else 32803]),
                  273:(4,[0]),277:(3,[spp]),278:(4,[height]),279:(4,[width*height*spp*2]),
                  284:(3,[1]),274:(3,[1]),50829:(4,[0,0,height,width])}
    if not linear:
        image_tags.update({33421:(3,[2,2]),33422:(1,[0,1,1,2]),50710:(1,[0,1,2]),50711:(3,[1])})
    for key, (kind, values) in image_tags.items(): tag(image,key,kind,values)
    tag(image,50713,3,[1,1])
    tag(image,50714,5,[512]*spp)
    tag(image,50717,4,[65535]*spp)
    for key, value in [(271,b'GPT\0'),(272,b'DNG WhiteXY Reference\0'),(50708,b'GPT DNG WhiteXY Reference\0')]:
        tag(metadata,key,2,value)
    tag(metadata,50706,1,[1,4,0,0])
    tag(metadata,50707,1,[1,1,0,0])
    tag(metadata,50721,10,[0]*9 if invalid == 'matrix' else CM[0])
    tag(metadata,50723,10,CC[0])
    tag(metadata,50727,5,ANALOG)
    tag(metadata,50778,3,[17])
    if dual:
        tag(metadata,50722,10,[0]*9 if invalid == 'matrix' else CM[1])
        tag(metadata,50724,10,CC[1])
        tag(metadata,50779,3,[21])
    if forward:
        tag(metadata,50964,10,FM)
        if dual: tag(metadata,50965,10,FM)
    if source == 'xy': tag(metadata,50729,5,[.8,.3] if invalid == 'xy' else xy)
    if source == 'neutral': tag(metadata,50728,5,neutral)
    if signature != 'missing':
        a, b = b'gpt-calibration\0', b'gpt-calibration\0'
        kind = 1 if signature == 'byte' else 2
        if signature == 'different': b = b'GPT-calibration\0'
        if signature == 'empty': a = b = b'\0'
        if signature == 'maximum': a = b = b'q'*255+b'\0'
        if signature == 'long': a = b = b'q'*256+b'\0'
        if signature == 'unterminated': a = b = b'gpt-calibration'
        if signature != 'profile_only': tag(metadata,50931,kind,a)
        if signature != 'camera_only': tag(metadata,50932,kind,b)
    pixels = bytearray()
    for row in range(height):
        for col in range(width):
            channels = range(3) if linear else [[0,1],[1,2]][row%2][col%2:col%2+1]
            for channel in channels:
                pixels.extend(struct.pack('<H', 512+round((65535-512)*.125*neutral[channel])))
    if subifd:
        root = {**metadata}
        for key, kind, values in [(254,4,[1]),(256,4,[1]),(257,4,[1]),(258,3,[8,8,8]),
                                  (259,3,[1]),(262,3,[2]),(273,4,[0]),(277,3,[3]),
                                  (278,4,[1]),(279,4,[3]),(330,4,[0])]: tag(root,key,kind,values)
        # IFD0 fallback and a selected RAW IFD override must both work.
        if subifd == 'override':
            tag(root,50729,5,[.3457,.3585])
            tag(image,50729,5,xy)
        write_tiff(folder/(name+'.dng'), [root,image], [bytes([0,0,0]),pixels])
    else:
        write_tiff(folder/(name+'.dng'), [{**image,**metadata}], [pixels])
    output_matrix = output_reference(xy, dual, calibrated, forward, matrix, neutral) if valid else None
    return dict(name=name, xy=list(xy), valid=valid, kelvin=k, matrix=matrix, neutral=neutral,
                output_matrix=output_matrix,
                from_xy=source=='xy', source=source, linear=linear, grey=valid and source!='missing')


def generate(folder):
    folder.mkdir(parents=True,exist_ok=True)
    cases = []
    for name, xy in [('d50',(.3457,.3585)),('d65',(.3127,.3290)),
                     ('warm',(.44757,.40745)),('tint',(.32,.37))]:
        for forward in (False,True):
            cases.append(fixture(folder,name+('-fm' if forward else '-cm'),xy,forward=forward))
    for signature in ('match','byte','empty','maximum','different','camera_only','profile_only'):
        cases.append(fixture(folder,'signature-'+signature,signature=signature))
    for linear in (False,True):
        cases.append(fixture(folder,'single-'+str(int(linear)),dual=False,linear=linear))
    for subifd in ('fallback','override'):
        cases.append(fixture(folder,'subifd-'+subifd,subifd=subifd))
    for invalid in ('xy','matrix'):
        cases.append(fixture(folder,'invalid-'+invalid,invalid=invalid))
    for signature in ('long','unterminated'):
        case = fixture(folder,'invalid-signature-'+signature,signature=signature)
        case['valid'] = False
        cases.append(case)
    cases.append(fixture(folder,'neutral-control',source='neutral'))
    cases.append(fixture(folder,'missing-source',source='missing'))
    (folder/'manifest.json').write_text(json.dumps(cases,indent=2)+'\n',encoding='utf-8')
    return cases


def check(cases, folder, probe, baseline=False):
    results = []
    matrix_checks, maximum_matrix_error = 0, 0
    for case in cases:
        for policy in ('compatible','headroom'):
            if case['linear'] and policy == 'headroom': continue
            for mode in ('default','custom'):
                output = folder/(case['name']+'-'+policy+'-'+mode+'.f32')
                command = [str(probe),str(folder/(case['name']+'.dng')),policy,mode,str(output)]
                row = json.loads(subprocess.check_output(command,text=True))
                expected = case['valid'] and (case['source']!='missing' or mode=='custom')
                if mode=='custom' and not case['valid']:
                    # A valid explicit WB can be used even when source XY is bad;
                    # this fixture is not required to be accepted by host WB UI.
                    if not row['ok']: assert row['unchanged'], (case['name'],row)
                    elif not baseline:
                        assert not row['from_xy'] and not row['source_valid'], (case['name'],row)
                elif baseline and case['from_xy']:
                    pass
                else:
                    assert bool(row['ok']) == expected, (case['name'],policy,mode,row)
                if not row['ok']:
                    assert row['unchanged'], (case['name'],row)
                elif case['valid']:
                    assert row['finite'] and row['width']==64 and row['height']==48, (case['name'],row)
                    if case['from_xy'] and not baseline:
                        assert row['from_xy'] and row['source_valid'], (case['name'],row)
                        assert max(abs(a-b) for a,b in zip(row['xy'],case['xy'])) < 1e-8
                        if mode=='default':
                            assert abs(row['kelvin']-case['kelvin']) < 1e-6, (case['name'],row)
                            assert max(abs(a-b) for a,b in zip(row['camera_xyz'],case['matrix'])) < 1e-6
                            actual = row['camera_to_output']
                            assert len(actual) == 12, (case['name'], actual)
                            actual = [actual[4*r+c] for r in range(3) for c in range(3)]
                            matrix_error = max(abs(a-b) for a,b in zip(actual,case['output_matrix']))
                            assert matrix_error < 1e-5, (case['name'],policy,matrix_error,actual,case['output_matrix'])
                            row['output_matrix_max_error'] = matrix_error
                            matrix_checks += 1
                            maximum_matrix_error = max(maximum_matrix_error, matrix_error)
                        expected_wb = [1/n for n in case['neutral']]
                        assert max(abs(a-b) for a,b in zip(row['source_wb'],expected_wb)) < 1e-5
                        if mode=='default' and case['grey']:
                            assert row['maximum_pixel_spread'] < .0003, (case['name'],row)
                    if case['source']=='missing': assert not row['source_valid'], row
                if row['ok'] and mode=='custom':
                    for channel in range(3):
                        applied = row['applied_wb'][channel]/row['applied_wb'][1]
                        selected = row['selected_wb'][channel]/row['selected_wb'][1]
                        assert abs(applied/selected-1) < 1e-5, (case['name'],row)
                row.update(case=case['name'],policy=policy,mode=mode)
                results.append(row)
    (folder/'results.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(cases=len(cases),decodes=len(results),passed=True,baseline=baseline,
                          matrix_checks=matrix_checks,maximum_matrix_error=maximum_matrix_error)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('folder',type=Path)
    parser.add_argument('--probe',type=Path)
    parser.add_argument('--baseline',action='store_true')
    parser.add_argument('--compare-baseline',type=Path)
    args = parser.parse_args()
    specs = generate(args.folder)
    if args.probe: check(specs,args.folder,args.probe,args.baseline)
    if args.compare_baseline:
        count = 0
        for name in ('neutral-control','missing-source'):
            for policy in ('compatible','headroom'):
                for mode in ('default','custom'):
                    if name=='missing-source' and mode=='default': continue
                    file = name+'-'+policy+'-'+mode+'.f32'
                    assert filecmp.cmp(args.folder/file,args.compare_baseline/file,shallow=False), file
                    count += 1
        (args.folder/'control-comparison.json').write_text(
            json.dumps(dict(byte_equal_pairs=count,passed=True))+'\n',encoding='utf-8')
        print(json.dumps(dict(byte_equal_control_pairs=count,passed=True)))

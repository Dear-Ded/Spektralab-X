#!/usr/bin/env bash
# Render the README sample set: neutral + six film looks per scene, 1600 px.
# Usage: R=<engine resources> T=<spk_render binary> samples.sh   (run inside a dir holding raw/)
set -u
mkdir -p out
R="${R:?engine resources dir}"
T="${T:?spk_render binary}"
declare -A SCN=(
  [skyline]="raw/Google_-_Pixel_2_XL_-_16bit_(4:3).dng"
  [lake]="raw/Leica_-_Q_(Typ_116)_-_14bit_(3:2).DNG"
  [dunes]="raw/Ricoh_-_GR_III_-_14bit_(3:2).DNG"
  [temple]="raw/Fujifilm_-_X100F_-_14bit_14bit_compressed_(3:2).RAF"
  [fruit]="raw/Canon_-_EOS_R5_-_RAW,_1.6_crop,_dual-pixel_(3:2).CR3"
  [flowers]="raw/Nikon_-_Z_fc_-_14bit_14bit_compressed_(Lossless)_(3:2).nef"
)
declare -A LOOK=(
  [portra400]='{"film_stock":"kodak_portra_400","print_stock":"kodak_portra_endura","scan_film":false}'
  [ektar100]='{"film_stock":"kodak_ektar_100","print_stock":"kodak_portra_endura","scan_film":false}'
  [gold200]='{"film_stock":"kodak_gold_200","print_stock":"kodak_portra_endura","scan_film":false}'
  [cinestill800t]='{"film_stock":"cinestill_800t","print_stock":"kodak_2383","scan_film":false}'
  [velvia100]='{"film_stock":"fujifilm_velvia_100","print_stock":"kodak_portra_endura","scan_film":true}'
  [trix400]='{"film_stock":"kodak_tri_x_400","print_stock":"ilford_multigrade_iv_rc","scan_film":false}'
)
: > out/index.txt
for s in "${!SCN[@]}"; do
  f="${SCN[$s]}"
  timeout 600 "$T" "$R" "$f" "out/${s}_none.png" 1600 none >/dev/null 2>"out/${s}_none.err" \
    && echo "$s none ok" >> out/index.txt || echo "$s none FAIL" >> out/index.txt
  for l in "${!LOOK[@]}"; do
    if timeout 600 "$T" "$R" "$f" "out/${s}_${l}.png" 1600 "${LOOK[$l]}" >/dev/null 2>"out/${s}_${l}.err"; then
      echo "$s $l ok" >> out/index.txt
    else
      echo "$s $l FAIL $(tail -c 200 out/${s}_${l}.err | tr '\n' ' ')" >> out/index.txt
    fi
  done
done
echo SAMPLES_DONE >> out/index.txt

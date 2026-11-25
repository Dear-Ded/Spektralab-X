#!/usr/bin/env bash
# Render the project site's photos: the scenes and looks it shows, at EDGE px on the
# long edge (default 3200), with the paper the app uses by default (Kodak Supra Endura).
# Usage: R=<engine resources> T=<spk_render binary> [EDGE=3200] hires.sh   (run inside a dir holding raw/)
# Writes out/<scene>_<look>.png and out/index.txt. See docs/SAMPLES.md.
set -u
mkdir -p out
R="${R:?engine resources dir}"
T="${T:?spk_render binary}"
EDGE="${EDGE:-3200}"
declare -A SCN=(
  [dunes]="raw/Ricoh_-_GR_III_-_14bit_(3:2).DNG"
  [lake]="raw/Leica_-_Q_(Typ_116)_-_14bit_(3:2).DNG"
  [fruit]="raw/Canon_-_EOS_R5_-_RAW,_1.6_crop,_dual-pixel_(3:2).CR3"
  [skyline]="raw/Google_-_Pixel_2_XL_-_16bit_(4:3).dng"
  [temple]="raw/Fujifilm_-_X100F_-_14bit_14bit_compressed_(3:2).RAF"
)
declare -A LOOK=(
  [portra400]='{"film_stock":"kodak_portra_400","print_stock":"kodak_supra_endura","scan_film":false}'
  [ektar100]='{"film_stock":"kodak_ektar_100","print_stock":"kodak_supra_endura","scan_film":false}'
  [gold200]='{"film_stock":"kodak_gold_200","print_stock":"kodak_supra_endura","scan_film":false}'
  [cinestill800t]='{"film_stock":"cinestill_800t","print_stock":"kodak_2383","scan_film":false}'
  [velvia100]='{"film_stock":"fujifilm_velvia_100","print_stock":"kodak_supra_endura","scan_film":true}'
  [trix400]='{"film_stock":"kodak_tri_x_400","print_stock":"ilford_multigrade_iv_rc","scan_film":false}'
)
# scene -> looks the site uses ("none" is the neutral decode)
declare -A WANT=(
  [dunes]="none portra400 ektar100 gold200 cinestill800t velvia100 trix400"
  [lake]="none ektar100"
  [fruit]="none velvia100"
  [skyline]="none ektar100"
  [temple]="none portra400 cinestill800t"
)
: > out/index.txt
for s in dunes lake fruit skyline temple; do
  f="${SCN[$s]}"
  for l in ${WANT[$s]}; do
    p=none; [[ $l != none ]] && p="${LOOK[$l]}"
    t0=$(date +%s)
    if timeout 1800 "$T" "$R" "$f" "out/${s}_${l}.png" "$EDGE" "$p" 2>"out/${s}_${l}.err"; then
      echo "$s $l ok $(( $(date +%s) - t0 ))s" | tee -a out/index.txt
    else
      echo "$s $l FAIL" | tee -a out/index.txt; tail -3 "out/${s}_${l}.err"
    fi
  done
done

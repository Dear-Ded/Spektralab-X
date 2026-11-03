#!/usr/bin/env bash
# Run spk_parity over a scene x stock matrix and grade every result.
# Usage: R=<engine resources> T=<spk_parity> run.sh   (inside a dir holding raw/)
# Grain and glare are off: both are stochastic, and a pixel comparison of two
# renders that differ only in noise measures the noise (upstream AGENTS trap 1).
set -u
R="${R:?engine resources dir}"
T="${T:?spk_parity binary}"
G="${G:-$(dirname "$0")/grade.py}"
mkdir -p par
declare -A SCN=(
  [lake]="raw/Leica_-_Q_(Typ_116)_-_14bit_(3:2).DNG"
  [fruit]="raw/Canon_-_EOS_R5_-_RAW,_1.6_crop,_dual-pixel_(3:2).CR3"
  [dunes]="raw/Ricoh_-_GR_III_-_14bit_(3:2).DNG"
  [skyline]="raw/Google_-_Pixel_2_XL_-_16bit_(4:3).dng"
  [chart]="raw/Nikon_-_Z_7_-_12bit_12bit_compressed_(3:2).NEF"
)
OFF='"grain_active":false,"glare_active":false'
declare -A LOOK=(
  [portra]="{\"film_stock\":\"kodak_portra_400\",\"print_stock\":\"kodak_supra_endura\",\"scan_film\":false,$OFF}"
  [velvia]="{\"film_stock\":\"fujifilm_velvia_100\",\"print_stock\":\"kodak_supra_endura\",\"scan_film\":true,$OFF}"
  [cine2383]="{\"film_stock\":\"kodak_vision3_250d\",\"print_stock\":\"kodak_2383\",\"scan_film\":false,$OFF}"
  [trix]="{\"film_stock\":\"kodak_tri_x_400\",\"print_stock\":\"ilford_multigrade_iv_rc\",\"scan_film\":false,$OFF}"
)
dirs=()
for s in "${!SCN[@]}"; do
  for l in "${!LOOK[@]}"; do
    d="par/${s}_${l}"; mkdir -p "$d"
    if timeout 600 "$T" "$R" "${SCN[$s]}" "$d" 1024 "${LOOK[$l]}" >"$d/log" 2>&1; then dirs+=("$d")
    else echo "FAIL $d $(tail -c 200 "$d/log" | tr '\n' ' ')"; fi
  done
done
python3 "$G" "${dirs[@]}"
echo PARITY_DONE

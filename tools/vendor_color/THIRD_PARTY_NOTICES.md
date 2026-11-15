# Profile format references

The NP3 offset/control interpretation in `inspect_profiles.py` was informed by
ssssota/nikon-flexible-color-picture-control at
ed0397fbf1e4d4f2bc11ab3070522c613667d359. Its independent TypeScript decoder is
used as an external reference, not bundled. Retained MIT notice:

MIT License

Copyright (c) 2025 TOMIKAWA Sotaro

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

Other original references are RawTherapee's GPL-3.0 DCP implementation and
dcamprof's DNG reference implementation; pinned URLs and hashes are recorded
by `fetch_sources.py`. Lensfun data use CC BY-SA 3.0 separately from its code
license. Profile copyright/embedding metadata must be reviewed per file.
Canon's public download page grants no redistribution rights inferred by this
tool. No third-party profile payloads are committed or added to the APK.

Inspection, control decoding, and table sampling are separate from reproducing
a manufacturer's camera renderer. These tools carry the repository's GPL-3.0
license, with the retained notice above for the NP3 reference.

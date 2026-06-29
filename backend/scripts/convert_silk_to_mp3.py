#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
将 QQ 语音 silk 文件转换为 MP3。
QQ 语音文件通常以 .amr 为后缀，但实际内容为 SILK V3 编码。
"""
import sys
import os
import io
import subprocess
import argparse

try:
    import pysilk
except ImportError:
    print("ERROR: pysilk module not found", file=sys.stderr)
    sys.exit(1)


def decode_silk_to_pcm(input_path: str) -> bytes:
    with open(input_path, 'rb') as f:
        data = f.read()

    if not data:
        raise ValueError("Empty input file")

    # NapCat/QQ 语音文件 sometimes 在 #!SILK_V3 前有一个字节的长度前缀
    if len(data) > 10 and data[1:10] == b'#!SILK_V3':
        data = data[1:]
    elif not data.startswith(b'#!SILK_V3'):
        raise ValueError("Not a valid SILK file")

    input_stream = io.BytesIO(data)
    output_stream = io.BytesIO()
    pysilk.decode(input_stream, output_stream, 24000)
    return output_stream.getvalue()


def pcm_to_mp3(pcm_data: bytes, output_path: str, ffmpeg_path: str) -> None:
    cmd = [
        ffmpeg_path,
        '-y',
        '-f', 's16le',
        '-ar', '24000',
        '-ac', '1',
        '-i', '-',
        '-acodec', 'libmp3lame',
        '-q:a', '2',
        output_path
    ]
    proc = subprocess.run(cmd, input=pcm_data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        err = proc.stderr.decode('utf-8', errors='ignore')[-500:]
        raise RuntimeError(f"ffmpeg failed: {err}")


def main():
    parser = argparse.ArgumentParser(description='Convert QQ silk voice to mp3')
    parser.add_argument('input', help='Input silk/amr file path')
    parser.add_argument('output', help='Output mp3 file path')
    parser.add_argument('--ffmpeg', default='ffmpeg', help='Path to ffmpeg executable')
    args = parser.parse_args()

    if not os.path.exists(args.input):
        print(f"ERROR: input file not found: {args.input}", file=sys.stderr)
        sys.exit(2)

    pcm = decode_silk_to_pcm(args.input)
    pcm_to_mp3(pcm, args.output, args.ffmpeg)
    print(args.output)


if __name__ == '__main__':
    main()

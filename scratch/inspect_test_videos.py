import os
import subprocess
import json
import wave
import struct
import math

VIDEO_DIR = "test-data/videos"
AUDIO_DIR = "test-data/audio"
RESULTS_DIR = "test-data/results/latest"

os.makedirs(AUDIO_DIR, exist_ok=True)
os.makedirs(RESULTS_DIR, exist_ok=True)

def extract_and_analyze():
    videos = []
    for root, dirs, files in os.walk(VIDEO_DIR):
        for f in files:
            if f.lower().endswith((".mp4", ".mov", ".mkv", ".avi")):
                videos.append(os.path.join(root, f))

    print(f"Found {len(videos)} test videos to inspect:")
    for v in videos:
        print(f" - {v}")

    reports = []

    for video_path in sorted(videos):
        rel_path = os.path.relpath(video_path, VIDEO_DIR)
        category = os.path.dirname(rel_path)
        filename = os.path.basename(video_path)
        base_name = os.path.splitext(filename)[0]

        # Target WAV path
        wav_path = os.path.join(AUDIO_DIR, f"{category}_{base_name}.wav")

        # 1. FFprobe metadata
        probe_cmd = [
            "ffprobe", "-v", "quiet", "-print_format", "json",
            "-show_format", "-show_streams", video_path
        ]
        try:
            probe_out = subprocess.check_output(probe_cmd, text=True)
            probe_data = json.loads(probe_out)
        except Exception as e:
            print(f"Error probing {video_path}: {e}")
            probe_data = {}

        # 2. Extract 16kHz Mono 16-bit PCM WAV using FFmpeg
        extract_cmd = [
            "ffmpeg", "-y", "-i", video_path,
            "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1",
            wav_path
        ]
        subprocess.run(extract_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        # 3. Analyze Audio
        audio_stats = analyze_wav(wav_path)

        # Extract stream info
        v_stream = next((s for s in probe_data.get("streams", []) if s.get("codec_type") == "video"), {})
        a_stream = next((s for s in probe_data.get("streams", []) if s.get("codec_type") == "audio"), {})

        report = {
            "file": rel_path,
            "category": category,
            "filename": filename,
            "wav_path": wav_path,
            "video_codec": v_stream.get("codec_name", "unknown"),
            "video_resolution": f"{v_stream.get('width', 0)}x{v_stream.get('height', 0)}",
            "video_fps": eval(v_stream.get("r_frame_rate", "0/1")) if "/" in v_stream.get("r_frame_rate", "") else 0,
            "audio_codec": a_stream.get("codec_name", "unknown"),
            "audio_sample_rate": a_stream.get("sample_rate", "unknown"),
            "audio_channels": a_stream.get("channels", 1),
            "duration_sec": audio_stats["duration_sec"],
            "peak_amplitude": audio_stats["peak"],
            "rms_energy": audio_stats["rms"],
            "noise_floor_rms": audio_stats["noise_floor_rms"],
            "snr_db": audio_stats["snr_db"],
            "voiced_ratio_percent": audio_stats["voiced_ratio_percent"],
            "speech_segments_count": audio_stats["speech_segments_count"],
            "speech_duration_sec": audio_stats["speech_duration_sec"],
            "speech_intervals": audio_stats["speech_intervals"]
        }
        reports.append(report)
        print(f"\n========================================")
        print(f"ANALYZED: {rel_path}")
        print(f"Duration: {report['duration_sec']:.2f}s | SampleRate: {report['audio_sample_rate']}Hz | Channels: {report['audio_channels']}")
        print(f"Video Codec: {report['video_codec']} ({report['video_resolution']} @ {report['video_fps']:.1f}fps)")
        print(f"Audio Codec: {report['audio_codec']} -> 16kHz mono WAV ({wav_path})")
        print(f"Acoustic Quality: Peak={report['peak_amplitude']}, RMS={report['rms_energy']:.1f}, NoiseFloor={report['noise_floor_rms']:.1f}, SNR={report['snr_db']:.1f}dB")
        print(f"Speech Coverage: {report['speech_duration_sec']:.2f}s / {report['duration_sec']:.2f}s ({report['voiced_ratio_percent']:.1f}%) across {report['speech_segments_count']} segments")
        for i, seg in enumerate(report["speech_intervals"]):
            print(f"  • Segment {i+1}: {seg['start_sec']:.2f}s -> {seg['end_sec']:.2f}s (dur: {seg['duration_sec']:.2f}s)")

    # Save summary json
    with open(os.path.join(RESULTS_DIR, "video_acoustic_inspection.json"), "w", encoding="utf-8") as f:
        json.dump(reports, f, indent=2)

def analyze_wav(wav_path):
    with wave.open(wav_path, "rb") as wf:
        n_channels = wf.getnchannels()
        sampwidth = wf.getsampwidth()
        framerate = wf.getframerate()
        n_frames = wf.getnframes()
        pcm_bytes = wf.readframes(n_frames)

    samples = struct.unpack(f"<{n_frames * n_channels}h", pcm_bytes)
    duration_sec = n_frames / framerate

    if not samples:
        return {
            "duration_sec": 0, "peak": 0, "rms": 0, "noise_floor_rms": 0,
            "snr_db": 0, "voiced_ratio_percent": 0, "speech_segments_count": 0,
            "speech_duration_sec": 0, "speech_intervals": []
        }

    peak = max(abs(s) for s in samples)
    sum_sq = sum(s * s for s in samples)
    rms = math.sqrt(sum_sq / len(samples))

    # Frame-level RMS energy (30ms frames = 480 samples @ 16kHz)
    frame_size = int(framerate * 0.030)
    num_frames = len(samples) // frame_size
    frame_energies = []
    for f in range(num_frames):
        chunk = samples[f * frame_size:(f + 1) * frame_size]
        f_rms = math.sqrt(sum(s * s for s in chunk) / frame_size)
        frame_energies.append(f_rms)

    sorted_energies = sorted(frame_energies)
    noise_floor_rms = sorted_energies[int(len(sorted_energies) * 0.15)] if sorted_energies else 0
    snr_db = 20 * math.log10(max(1.0, rms) / max(1.0, noise_floor_rms)) if noise_floor_rms > 0 else 30.0

    # VAD segmentation
    speech_threshold = max(noise_floor_rms * 1.35, 55.0)
    is_voiced = [e >= speech_threshold for e in frame_energies]

    # Group voiced frames into intervals with 600ms silence tolerance
    intervals = []
    in_speech = False
    start_frame = 0
    silence_count = 0
    max_silence_frames = int(0.600 / 0.030) # 20 frames

    for f, voiced in enumerate(is_voiced):
        if voiced:
            if not in_speech:
                in_speech = True
                start_frame = f
            silence_count = 0
        else:
            if in_speech:
                silence_count += 1
                if silence_count >= max_silence_frames:
                    end_frame = f - silence_count
                    dur = (end_frame - start_frame) * 0.030
                    if dur >= 0.25:
                        intervals.append({
                            "start_sec": start_frame * 0.030,
                            "end_sec": end_frame * 0.030,
                            "duration_sec": dur
                        })
                    in_speech = False
                    silence_count = 0

    if in_speech:
        end_frame = len(is_voiced) - 1
        dur = (end_frame - start_frame) * 0.030
        if dur >= 0.25:
            intervals.append({
                "start_sec": start_frame * 0.030,
                "end_sec": end_frame * 0.030,
                "duration_sec": dur
            })

    total_speech_sec = sum(it["duration_sec"] for it in intervals)
    voiced_ratio = (total_speech_sec / duration_sec * 100) if duration_sec > 0 else 0

    return {
        "duration_sec": duration_sec,
        "peak": peak,
        "rms": rms,
        "noise_floor_rms": noise_floor_rms,
        "snr_db": snr_db,
        "voiced_ratio_percent": voiced_ratio,
        "speech_segments_count": len(intervals),
        "speech_duration_sec": total_speech_sec,
        "speech_intervals": intervals
    }

if __name__ == "__main__":
    extract_and_analyze()

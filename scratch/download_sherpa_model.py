import urllib.request
import os
import tarfile
import zipfile

MODELS_DIR = "test-data/models"

def download_and_extract(url, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    tar_name = os.path.join(out_dir, os.path.basename(url))
    if not os.path.exists(tar_name):
        print(f"Downloading {url}...")
        urllib.request.urlretrieve(url, tar_name)
        print(f"Downloaded {tar_name}")
    
    if tar_name.endswith((".tar.bz2", ".tar.gz", ".tgz")):
        with tarfile.open(tar_name) as tar:
            tar.extractall(out_dir)
            print(f"Extracted {tar_name}")
    elif tar_name.endswith(".zip"):
        with zipfile.ZipFile(tar_name) as z:
            z.extractall(out_dir)
            print(f"Extracted {tar_name}")

if __name__ == "__main__":
    # Test downloading sherpa-onnx whisper multilingual base
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
    download_and_extract(url, MODELS_DIR)

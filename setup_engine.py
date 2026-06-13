import zipfile
import os
import urllib.request

url = "https://github.com/CypherpunkArmory/UserLAnd/releases/download/v2.8.3/app-release.apk"
print("[*] Downloading Official UserLAnd APK (~15MB)...")
urllib.request.urlretrieve(url, "userland.apk")

dest_dir = "app/src/main/jniLibs/arm64-v8a"
os.makedirs(dest_dir, exist_ok=True)

print("[*] Extracting Patched W^X Engine Libraries...")
with zipfile.ZipFile("userland.apk", "r") as z:
    extracted = 0
    for file_info in z.infolist():
        if file_info.filename.startswith("lib/arm64-v8a/") and file_info.filename.endswith(".so"):
            filename = os.path.basename(file_info.filename)
            with open(os.path.join(dest_dir, filename), "wb") as f:
                f.write(z.read(file_info.filename))
            extracted += 1
            print(f"  -> Extracted {filename}")

if extracted > 0:
    print("[SUCCESS] Deep W^X Engine (Loader & PRoot) is ready!")
else:
    print("[ERROR] Failed to extract libraries!")

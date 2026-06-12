import re
with open("app/src/main/jniLibs/arm64-v8a/libproot.so", "rb") as f:
    data = f.read()
# ELF (Executable) ম্যাজিক বাইট খোঁজা
elfs = [m.start() for m in re.finditer(b'\x7fELF', data)]
if len(elfs) >= 2:
    # দ্বিতীয় ELF টি হলো আমাদের কাঙ্ক্ষিত Loader
    loader_data = data[elfs[1]:]
    with open("app/src/main/jniLibs/arm64-v8a/libloader.so", "wb") as f:
        f.write(loader_data)
    print("SUCCESS: Deep UserLAnd Loader Extracted!")
else:
    print("ERROR: Loader not found!")

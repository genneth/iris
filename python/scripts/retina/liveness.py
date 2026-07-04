"""Are RGB frames fresh, or is linuxpy handing back duplicate buffers?

Streams /dev/video0 in manual exposure and prints, per frame: the V4L2 sequence
number, mean luma (high precision), and a hash of the raw bytes. Distinct hashes
+ incrementing sequence => fresh frames. Repeats => stale/duplicate buffers.
"""

import hashlib

import numpy as np
from linuxpy.video.device import BufferType, Device, set_control

set = set_control
cam = Device("/dev/video0")
cam.open()
cam.set_format(BufferType.VIDEO_CAPTURE, 320, 180, pixel_format="YUYV")
set(cam, 0x009A0901, 1)  # AE manual
set(cam, 0x009A0902, 1500)  # exposure
set(cam, 0x00980913, 128)  # gain

print(f"{'i':>3} {'seq':>5} {'luma':>10} {'hash8':>10}")
try:
    for i, frame in enumerate(cam):
        b = bytes(frame)
        a = np.frombuffer(b, dtype=np.uint8)
        h = hashlib.sha1(b).hexdigest()[:8]
        print(f"{i:3d} {frame.frame_nb:5d} {a[0::2].mean():10.5f} {h:>10}")
        if i >= 14:
            break
finally:
    set(cam, 0x009A0901, 3)
    set(cam, 0x00980913, 64)
    cam.close()

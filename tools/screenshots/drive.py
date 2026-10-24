#!/usr/bin/env python3
"""Feeds the emulator a car driving north on Jl. MH Thamrin, Jakarta, at about 50 km/h.

Sends one NMEA RMC sentence per second (position, speed and heading) through `adb emu`
(or `geo fix` with speed and heading when run as `drive.py fix`),
so Orca Home's map follows the car, turns the arrow and shows the speed.
"""
import subprocess
import sys
import time

LAT0, LON0 = -6.1990, 106.82296   # Bundaran HI
STEP = 0.00012                    # degrees per second north, ~13.3 m/s = 48 km/h
KNOTS = 13.3 * 1.943844


def nmea(lat, lon, knots, course):
    def dm(v, deg_digits):
        a = abs(v)
        d = int(a)
        return f"{d:0{deg_digits}d}{(a - d) * 60:07.4f}"
    t = time.gmtime()
    body = (f"GPRMC,{time.strftime('%H%M%S', t)}.00,A,{dm(lat, 2)},{'S' if lat < 0 else 'N'},"
            f"{dm(lon, 3)},{'W' if lon < 0 else 'E'},{knots:.1f},{course:.1f},"
            f"{time.strftime('%d%m%y', t)},,,A")
    cs = 0
    for ch in body:
        cs ^= ord(ch)
    return f"${body}*{cs:02X}"


i = 0
while True:
    lat = LAT0 + STEP * (i % 120)   # loop back every two minutes so the car stays on Thamrin
    if len(sys.argv) > 1 and sys.argv[1] == "fix":
        # Fallback: `geo fix <lon> <lat> <altitude> <satellites> <knots> <heading>`.
        # This emulator reports the velocity field as metres per second.
        cmd = ["geo", "fix", f"{LON0:.6f}", f"{lat:.6f}", "10", "8", "13.3", "0"]
    else:
        cmd = ["geo", "nmea", nmea(lat, LON0, KNOTS, 0.0)]
    subprocess.run(["adb", "emu"] + cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    i += 1
    time.sleep(1)

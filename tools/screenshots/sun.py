#!/usr/bin/env python3
"""Prints "day" or "night" right now at lat lon (NOAA sunrise equation), to check
Orca Home's automatic theme in the emulator test."""
import math, sys, time

def event(lat, lon, t_utc, rising):
    tm = time.gmtime(t_utc)
    n = tm.tm_yday
    lng_hour = lon / 15
    t = n + ((6 if rising else 18) - lng_hour) / 24
    m = 0.9856 * t - 3.289
    rad = math.radians
    l = (m + 1.916 * math.sin(rad(m)) + 0.020 * math.sin(rad(2 * m)) + 282.634) % 360
    ra = math.degrees(math.atan(0.91764 * math.tan(rad(l)))) % 360
    ra = (ra + (math.floor(l / 90) * 90 - math.floor(ra / 90) * 90)) / 15
    sin_dec = 0.39782 * math.sin(rad(l))
    cos_dec = math.cos(math.asin(sin_dec))
    cos_h = (math.cos(rad(90.833)) - sin_dec * math.sin(rad(lat))) / (cos_dec * math.cos(rad(lat)))
    h = (360 - math.degrees(math.acos(cos_h)) if rising else math.degrees(math.acos(cos_h))) / 15
    ut = (h + ra - 0.06571 * t - 6.622 - lng_hour) % 24
    midnight = t_utc - (t_utc % 86400)
    guess = midnight + ((6 if rising else 18) - lng_hour) * 3600
    at = midnight + ut * 3600
    while at - guess > 43200: at -= 86400
    while guess - at > 43200: at += 86400
    return at

lat, lon = float(sys.argv[1]), float(sys.argv[2])
now = time.time()
events = sorted((event(lat, lon, now + d * 86400, r), r) for d in (-1, 0, 1) for r in (True, False))
last = [e for e in events if e[0] <= now][-1]
print("day" if last[1] else "night")


# StraGla

StraGla is a free and open-source Android application for **streaming and saving** your device screen and audio. 
It is derived from the excelent original [ScreenStream](https://play.google.com/store/apps/details?id=info.dvkr.screenstream) application, but with major re-focus. 

What StraGla does:
- stream to rtsp/rtsps endpoint (such as mediamtx server)
- save local file (mp4) with the same encoding as the stream
- tolerate network outage - saving is not affected, streaming resumed when the network is back

## rtsp / rtsps streaming endpoints
You must have an rtsp/rtsps server to stream to. 
Default addresses will look like this:
- rtsp://server1:8554/stream_name1
  - server1 - either dns name, or IP, or LAN name that maps to IP
  - 8554 - default rtsp port, but depends on your server configuration
  - stream_name1 - usually anything you want; some scenarios (youtube live) requires "secret key"
- rtsps://server2:8322/stream_name2
  - this is encrypted version of rtsp, so it requires TLS/SSL certificate on the server side; 
  - server2 - normally this is DNS (public signed TLS).   
## streaming resolution, framrate, bitrate 
Network speed is critically important. Usually you will compromise for size and quality.

Approximate streaming quality guide, for h.264 codec, no audio:

| Mbps | 1080p30 | 1080p16 | 720p30 | 720p16 | 540p30 | 540p16 |
|------|---------|---------|--------|--------|--------|--------|
| 0.4 | 🟥 Unusable | 🟥 Unusable | 🟥 Unusable | 🟨 Passable | 🟨 Passable | 🟩 Good |
| 0.8 | 🟥 Unusable | 🟥 Unusable | 🟨 Passable | 🟩 Good | 🟩 Good | 🟦 Perfect |
| 1.2 | 🟥 Unusable | 🟨 Passable | 🟨 Passable | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect |
| 1.6 | 🟥 Unusable | 🟩 Good | 🟩 Good | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect |
| 2.0 | 🟨 Passable | 🟩 Good | 🟩 Good | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect |
| 3.0 | 🟩 Good | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect |
| 4.0 | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect |
| 5.0 | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect | 🟦 Perfect |

## Codec
ScreenStream uses Android's [MediaProjection](https://developer.android.com/reference/android/media/projection/MediaProjection) API and requires Android 6.0 or higher.
video and audio codecs are dependent on the Android version and device capabilities.

## Developer

This is a side project for developer (Aivars19), no support is planned. Use "as is". 

## Privacy and Terms

There are no ads, no analytics, no tracking, no data collection of any kind.

There are no restrictions on the use of the app. 

## License

```
The MIT License (MIT)

Copyright (c) 2016 Dmytro Kryvoruchko (for original ScreenStream) 

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
```

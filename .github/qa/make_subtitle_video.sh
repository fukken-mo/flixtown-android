#!/usr/bin/env bash
# Builds a subtitle test video: 5 minutes of plain dark grey picture (so the picture's width can be
# measured: no stretching) with silent audio and two subtitle tracks whose cues run back to back
# (4 s each), so they span the player controls' 3 s auto-hide.
#   track 1 "Plain"  (SRT, English, green):  ordinary cues, one line and two lines in turn
#   track 2 "Bottom" (ASS, French, yellow):  cues placed by the file at the very bottom edge,
#                                            {\an2\pos(640,720)}, like many episode subtitle files
# Output: $1 (default media/subs.mkv); $2 picture size (default 1280x720; 960x720 for 4:3)
set -eu
OUTFILE=${1:-media/subs.mkv}; SIZE=${2:-1280x720}; DIR=$(dirname "$OUTFILE"); mkdir -p "$DIR"
python3 - "$DIR" "${SIZE%x*}" <<'PY'
import sys
d=sys.argv[1]; W=int(sys.argv[2])
def ts(s,sep): return "%02d:%02d:%02d%s%03d"%(s//3600,s//60%60,s%60,sep,0)
srt=[]; ass=["[Script Info]","ScriptType: v4.00+","PlayResX: %d"%W,"PlayResY: 720","",
  "[V4+ Styles]","Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
  "Style: Default,Arial,48,&H0000FFFF,&H0000FFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,0,0,0,1","",
  "[Events]","Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"]
for i in range(75):
    a,b=i*4,i*4+4
    lines=['PLAIN LINE %d'%(i+1)]+([] if i%2==0 else ['SECOND ROW %d'%(i+1)])   # odd cues: two lines
    srt+=[str(i+1),"%s --> %s"%(ts(a,","),ts(b,","))]+['<font color="#00FF00">%s</font>'%t for t in lines]+[""]
    ass.append("Dialogue: 0,%d:%02d:%02d.00,%d:%02d:%02d.00,Default,,0,0,0,,{\\an2\\pos(%d,720)}BOTTOM LINE %d"%(a//3600,a//60%60,a%60,b//3600,b//60%60,b%60,W//2,i+1))
open(d+"/plain.srt","w").write("\n".join(srt)+"\n")
open(d+"/bottom.ass","w").write("\n".join(ass)+"\n")
PY
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "color=c=0x404040:s=$SIZE:r=25:d=300" -f lavfi -i "anullsrc=r=48000:cl=stereo" \
  -i "$DIR/plain.srt" -i "$DIR/bottom.ass" \
  -map 0:v -map 1:a -map 2 -map 3 -t 300 \
  -c:v libx264 -preset ultrafast -tune stillimage -pix_fmt yuv420p -g 50 -c:a aac -b:a 48k \
  -c:s:0 srt -c:s:1 ass \
  -metadata:s:s:0 language=eng -metadata:s:s:0 title=Plain -metadata:s:s:1 language=fra -metadata:s:s:1 title=Bottom \
  -disposition:s:0 0 -disposition:s:1 0 "$OUTFILE"
ls -l "$OUTFILE"

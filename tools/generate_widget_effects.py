"""生成由桌面宿主播放的小部件动画资源，统一控制节奏、颜色和降级版本。"""
from pathlib import Path
import math


RES = Path(__file__).resolve().parents[1] / "app/src/main/res"
ANDROID = 'xmlns:android="http://schemas.android.com/apk/res/android"'
AAPT = 'xmlns:aapt="http://schemas.android.com/aapt"'


def write(kind, name, body):
    (RES / kind).mkdir(exist_ok=True)
    (RES / kind / f"{name}.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n' + body + '\n', encoding="utf-8")


def animation(name, frames, duration=40, once=False):
    body = f'<animation-list {ANDROID} {AAPT} android:oneshot="{str(once).lower()}">\n'
    body += '\n'.join(f'    <item android:duration="{duration}">\n{frame}\n    </item>' for frame in frames)
    body += '\n</animation-list>'
    if name in ("widget_scan", "widget_scan_finish"):
        # Android 12 以前的 RemoteViews 不支持启用轮廓裁剪，使用保守的矢量内裁剪。
        write("drawable-v31", name, body)
        body = body.replace('android:viewportHeight="100">', '''android:viewportHeight="100">
            <clip-path android:pathData="M28,0 H72 A28,28 0,0 1,100,28 V72 A28,28 0,0 1,72,100 H28 A28,28 0,0 1,0,72 V28 A28,28 0,0 1,28,0 Z" />''')
    write("drawable", name, body)


def vector(paths):
    return ('        <vector android:width="100dp" android:height="100dp" '
            'android:viewportWidth="100" android:viewportHeight="100">\n' + paths + '\n        </vector>')


def scan():
    frames = []
    slope = math.tan(math.radians(20))
    for i in range(40):
        x = -46 + 192 * i / 39
        fade = min(1, i / 4, (39 - i) / 4)
        alpha = round(61 * fade)
        half = 15 / math.cos(math.radians(20))
        top, bottom = x - 50 * slope, x + 50 * slope
        start = (x - 15 * math.cos(math.radians(20)), 50 + 15 * math.sin(math.radians(20)))
        end = (x + 15 * math.cos(math.radians(20)), 50 - 15 * math.sin(math.radians(20)))
        path = f'M{top-half:.3f},0 L{top+half:.3f},0 L{bottom+half:.3f},100 L{bottom-half:.3f},100 Z'
        paths = f'''            <path android:pathData="{path}">
                <aapt:attr name="android:fillColor">
                    <gradient android:type="linear" android:startX="{start[0]:.3f}" android:startY="{start[1]:.3f}"
                        android:endX="{end[0]:.3f}" android:endY="{end[1]:.3f}">
                        <item android:offset="0" android:color="#003578DB" />
                        <item android:offset="0.45" android:color="#{alpha:02X}3578DB" />
                        <item android:offset="0.65" android:color="#{alpha:02X}22D3EE" />
                        <item android:offset="1" android:color="#0022D3EE" />
                    </gradient>
                </aapt:attr>
            </path>'''
        for offset, strength in [(2, .7), (-6, .35)]:
            paths += (f'\n            <path android:pathData="M{top+offset:.3f},0 L{bottom+offset:.3f},100" '
                      f'android:strokeWidth="0.45" android:strokeColor="#{round(alpha*strength):02X}22D3EE" '
                      'android:fillColor="#00000000" />')
        frames.append(vector(paths))
    animation("widget_scan", frames)


def finish():
    frames = []
    for i in range(6):
        alpha = round(20 * (1 - i / 5) ** 2)
        frames.append(vector(f'''            <path android:pathData="M0,0 H100 V100 H0 Z">
                <aapt:attr name="android:fillColor">
                    <gradient android:type="radial" android:centerX="50" android:centerY="50" android:gradientRadius="75"
                        android:startColor="#{alpha:02X}3578DB" android:endColor="#003578DB" />
                </aapt:attr>
            </path>'''))
    animation("widget_scan_finish", frames, once=True)


def particles(single=False, delayed=False):
    frames = []
    for frame_index in range(40):
        i = (frame_index - (4 if delayed else 0)) % 40
        head = -.18 + 1.38 * i / 39
        fade = min(1, i / 4, (39 - i) / 4)
        dots = [(0, 4, 255)] if single else [(0, 4, 255), (.065, 3, 170), (.13, 2.5, 95), (.195, 2, 45)]
        layers = []
        for offset, size, alpha in reversed(dots):
            x = head - offset
            if 0 <= x <= (.6 if single else .88):
                layers.append(f'''            <item>
                <inset android:insetLeft="{100*x:.3f}%">
                    <layer-list>
                        <item android:width="{size}dp" android:height="{size}dp" android:gravity="left|center_vertical">
                            <shape android:shape="oval"><solid android:color="#{round(alpha*fade):02X}FFFFFF" /></shape>
                        </item>
                    </layer-list>
                </inset>
            </item>''')
        if not layers:
            layers.append('            <item><color android:color="#00000000" /></item>')
        frames.append('        <layer-list>\n' + '\n'.join(layers) + '\n        </layer-list>')
    name = "widget_particles" + ("_single" if single else "") + ("_delayed" if delayed else "")
    animation(name, frames)
    write("layout", name, f'''<ProgressBar {ANDROID}
    android:id="@+id/widget_particles"
    style="?android:attr/progressBarStyleHorizontal"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:minWidth="0dp" android:minHeight="0dp" android:padding="0dp"
    android:background="@drawable/widget_progress_fill" android:backgroundTint="@null"
    android:clipToOutline="true" android:outlineProvider="background"
    android:indeterminate="true" android:indeterminateOnly="true"
    android:indeterminateDrawable="@drawable/{name}" android:indeterminateTint="@null"
    android:importantForAccessibility="no" />''')


def checkmark():
    frames = []
    first = math.sqrt(50)
    second = math.sqrt(242)
    for i in range(8):
        distance = (first + second) * i / 7
        if distance <= first:
            t = distance / first
            path = f'M4,12 L{4+5*t:.3f},{12+5*t:.3f}'
        else:
            t = (distance-first) / second
            path = f'M4,12 L9,17 L{9+11*t:.3f},{17-11*t:.3f}'
        frames.append(f'''        <vector android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
            <path android:fillColor="#00000000" android:strokeColor="#36864B" android:strokeWidth="2.4"
                android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{path}" />
        </vector>''')
    animation("widget_feedback_success_animated", frames, duration=30, once=True)
    write("layout", "widget_feedback_success_animated", f'''<ProgressBar {ANDROID}
    style="?android:attr/progressBarStyleSmall"
    android:layout_width="12dp" android:layout_height="12dp"
    android:minWidth="0dp" android:minHeight="0dp" android:padding="0dp"
    android:indeterminate="true" android:indeterminateOnly="true"
    android:indeterminateDrawable="@drawable/widget_feedback_success_animated" android:indeterminateTint="@null"
    android:importantForAccessibility="no" />''')


def card_layout(name):
    write("layout", name, f'''<FrameLayout {ANDROID}
    android:id="@+id/widget_scan_surface"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:background="@drawable/widget_background" android:backgroundTint="@null"
    android:clipToOutline="true" android:outlineProvider="background"
    android:clickable="false" android:focusable="false" android:importantForAccessibility="noHideDescendants">
    <ProgressBar style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent" android:layout_height="match_parent"
        android:minWidth="0dp" android:minHeight="0dp" android:padding="0dp"
        android:indeterminate="true" android:indeterminateOnly="true"
        android:indeterminateDrawable="@drawable/{name}" android:indeterminateTint="@null" />
</FrameLayout>''')


if __name__ == "__main__":
    scan()
    finish()
    checkmark()
    for single in (False, True):
        for delayed in (False, True):
            particles(single, delayed)
    for name in ("widget_scan", "widget_scan_finish"):
        card_layout(name)

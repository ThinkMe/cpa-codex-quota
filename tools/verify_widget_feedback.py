"""仅在已有测试配置的独立模拟器上验证小部件原位刷新反馈，并恢复原配置。"""
import argparse
import copy
import io
import json
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from PIL import Image, ImageChops


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="E:/Tools/AndroidSdk/platform-tools/adb.exe")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--extended-only", action="store_true", help="只检查进程恢复及 App 内刷新")
    parser.add_argument("--skip-basic", action="store_true", help="跳过基础流程，只检查尺寸和恢复")
    parser.add_argument("--periodic-only", action="store_true", help="验证复用真实周期任务及结果恢复")
    args = parser.parse_args()
    package = "com.neo.codexquota"
    output = Path(__file__).resolve().parents[1] / "build/verification/refresh-feedback"
    output.mkdir(parents=True, exist_ok=True)

    def adb(*parts, data=None):
        return subprocess.run([args.adb, "-s", args.serial, *parts], input=data,
                              check=True, capture_output=True, timeout=30).stdout

    if not args.serial.startswith("emulator-") or adb("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
        raise RuntimeError("只允许操作独立测试模拟器")
    state_path = "files/datastore/codex_quota.preferences_pb"
    feedback_path = "shared_prefs/widget_feedback.xml"
    original = adb("exec-out", "run-as", package, "cat", state_path)
    state = json.JSONDecoder().raw_decode(original[original.index(b"{"):].decode())[0]
    original_font = adb("shell", "settings", "get", "system", "font_scale").decode().strip()
    original_night = "yes" if "yes" in adb("shell", "cmd", "uimode", "night").decode() else "no"
    try:
        old_feedback = adb("exec-out", "run-as", package, "cat", feedback_path)
    except subprocess.CalledProcessError:
        old_feedback = b"<?xml version='1.0' encoding='utf-8'?><map/>"
    mode = {"delay": 10, "failure": False, "get": 0, "post": 0}
    checks = []
    status_box = None
    recording = None

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def respond(self, code, body):
            raw = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

        def do_GET(self):
            mode["get"] += 1
            time.sleep(mode["delay"])
            if mode["failure"]:
                return self.respond(503, {})
            self.respond(200, {"files": [
                {"name": a["name"], "provider": "codex", "auth_index": a["authIndex"],
                 "email": a["email"], "id_token": {"chatgpt_account_id": a["accountId"], "plan_type": "plus"}}
                for a in state["accounts"]]})

        def do_POST(self):
            mode["post"] += 1
            self.rfile.read(int(self.headers["Content-Length"]))
            now = int(time.time())
            self.respond(200, {"status_code": 200, "body": json.dumps({"plan_type": "plus", "rate_limit": {
                "primary_window": {"used_percent": 25, "limit_window_seconds": 18000, "reset_at": now + 7200},
                "secondary_window": {"used_percent": 60, "limit_window_seconds": 604800, "reset_at": now + 172800}}})})

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port = server.server_port
    adb("reverse", f"tcp:{port}", f"tcp:{port}")

    def varint(value):
        result = bytearray()
        while value > 127:
            result.append((value & 127) | 128)
            value >>= 7
        return bytes(result) + bytes([value])

    def field(number, value):
        return bytes([(number << 3) | 2]) + varint(len(value)) + value

    def tree():
        adb("shell", "uiautomator", "dump", "/sdcard/refresh-feedback.xml")
        return ET.fromstring(adb("shell", "cat", "/sdcard/refresh-feedback.xml"))

    def tap(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

    def host(width=151, height=167, font="1.0"):
        adb("logcat", "-c")
        adb("shell", "settings", "put", "system", "font_scale", font)
        adb("shell", "am", "start", "-S", "-n", "com.neo.quotavisual/.VisualActivity",
            "--ei", "width", str(width), "--ei", "height", str(height))
        time.sleep(1)
        root = tree()
        logs = adb("logcat", "-d", "-s", "QuotaVisual:I", "*:S").decode(errors="replace")
        for line in logs.splitlines():
            if "TEXT|" in line:
                assert line.split("|")[-2] == "0", f"小部件文字发生截断：{line}"
        return root

    def feedback():
        # SharedPreferences 正在提交时可能短暂读到空内容，重新读取而不误报空状态。
        for attempt in range(5):
            try:
                raw = adb("exec-out", "run-as", package, "cat", feedback_path)
                return {n.get("name"): json.loads(n.text) for n in ET.fromstring(raw).findall("string")}
            except subprocess.CalledProcessError:
                return {}
            except ET.ParseError:
                if attempt == 4:
                    raise
                time.sleep(.03)

    def wait_for(predicate, timeout=20):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            values = feedback()
            if predicate(values):
                return values
            time.sleep(.05)
        raise AssertionError(f"等待刷新状态超时：{values}")

    def phase(name):
        return wait_for(lambda values: any(v["phase"] == name for v in values.values()))

    def shot(name):
        (output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))

    def result_shot(name, channel):
        # WorkManager 状态先于宿主画面提交，确认真实画面出现结果颜色后再截图。
        end = time.monotonic() + 3
        while time.monotonic() < end:
            raw = adb("exec-out", "screencap", "-p")
            pixels = Image.open(io.BytesIO(raw)).convert("RGB").crop(status_box)
            colored = sum(1 for r, g, b in pixels.getdata() if (
                g > r * 1.3 and g > b * 1.3 and g > 80 if channel == "green"
                else r > g * 1.5 and r > b * 1.5 and r > 100))
            if colored > 8:
                (output / (name + ".png")).write_bytes(raw)
                return
            time.sleep(.05)
        raise AssertionError(f"宿主没有实际显示结果：{name}")

    def wait_idle():
        wait_for(lambda values: not values)
        time.sleep(.8)

    def record(message):
        checks.append(message)
        print(message, flush=True)

    try:
        fixture = copy.deepcopy(state)
        fixture["server"] = f"http://127.0.0.1:{port}"
        fixture["refreshIntervalMinutes"] = 15 if args.periodic_only else None
        fixture.pop("globalError", None)
        for snapshot in fixture["snapshots"]:
            snapshot.pop("error", None)
        encoded = field(1, field(1, b"state_v1") + field(2, field(5, json.dumps(fixture, ensure_ascii=False).encode())))
        adb("shell", "am", "force-stop", package)
        adb("shell", "run-as", package, "tee", state_path, data=encoded)
        adb("shell", "run-as", package, "tee", feedback_path, data=b"<map/>")
        adb("shell", "am", "start", "-n", package + "/.MainActivity", "--es", "account_id", fixture["defaultAccountId"])
        end = time.monotonic() + 12
        while True:
            save = next((n for n in tree().iter("node") if n.get("text") == "保存"), None)
            if save is not None:
                tap(save)
                break
            assert time.monotonic() < end, "账号详情尚未显示保存入口"
            time.sleep(.2)
        root = host()
        surface = next(n for n in root.iter("node") if n.get("class") == "android.appwidget.AppWidgetHostView")
        # 只检查底部状态行，避免把额度本身的绿色误判为成功反馈。
        labels = [n for n in root.iter("node") if n.get("class") == "android.widget.TextView" and n.get("text")]
        status_box = tuple(map(int, re.findall(r"\d+", labels[-1].get("bounds"))))
        idle_percentages = [n.get("text") for n in root.iter("node") if re.fullmatch(r"\d+%", n.get("text", ""))]
        idle_titles = {n.get("text"): n.get("bounds") for n in root.iter("node") if n.get("text") in ("5H限额", "周限额")}
        first_time = next(n for n in labels if "·" in n.get("text", ""))
        first_time_box = tuple(map(int, re.findall(r"\d+", first_time.get("bounds"))))
        shot("idle")
        if args.periodic_only:
            def sql(statement):
                return adb("shell", f'run-as {package} sqlite3 no_backup/androidx.work.workdb "{statement}"').decode().strip()

            periodic_id = sql("SELECT w.id FROM workspec w JOIN workname n ON n.work_spec_id=w.id "
                              "WHERE n.name='quota_periodic' AND w.state IN (0,1) LIMIT 1;")
            assert re.fullmatch(r"[a-f0-9-]{36}", periodic_id), "缺少真实周期刷新任务"
            last_busy = None
            for fail in (False, True, False):
                mode.update(failure=fail, delay=8)
                base_get, base_post = mode["get"], mode["post"]
                adb("shell", "am", "force-stop", package)
                # 仅在独立模拟器加速周期计时，不更改手机或生产环境的调度策略。
                sql(f"UPDATE workspec SET initial_delay=0,last_enqueue_time=0 WHERE id='{periodic_id}';")
                adb("shell", "am", "start", "-n", package + "/.MainActivity")
                root = host()
                job_id = sql(f"SELECT system_id FROM systemidinfo WHERE work_spec_id='{periodic_id}' LIMIT 1;")
                if job_id:
                    forced = subprocess.run([args.adb, "-s", args.serial, "shell", "cmd", "jobscheduler", "run", "-f", package, job_id], capture_output=True)
                    assert forced.returncode in (0, 23), forced.stderr.decode(errors="replace")
                end = time.monotonic() + 15
                while mode["get"] == base_get and time.monotonic() < end:
                    time.sleep(.1)
                assert mode["get"] > base_get, "周期任务未开始网络请求"
                tap(next(n for n in root.iter("node") if n.get("class") == "android.appwidget.AppWidgetHostView"))
                busy = phase("Refreshing")
                value = next(iter(busy.values()))
                assert value["workId"] == periodic_id, "点击没有复用正在执行的周期任务"
                last_busy = busy
                phase("Failure" if fail else "Success")
                end = time.monotonic() + 12
                while sql(f"SELECT state FROM workspec WHERE id='{periodic_id}';") != "0" and time.monotonic() < end:
                    time.sleep(.1)
                assert sql(f"SELECT state FROM workspec WHERE id='{periodic_id}';") == "0"
                assert sql(f"SELECT length(output) FROM workspec WHERE id='{periodic_id}';") == "8", "预期周期任务输出已清空"
                assert mode["get"] - base_get == 1 and mode["post"] - base_post == (0 if fail else 1), "发生重复网络请求"
                if fail:
                    phase("Error")
                else:
                    wait_idle()
                assert not any(n.get("text") == "刷新中" for n in tree().iter("node"))
                record(f"复用真实周期任务：{'失败' if fail else '成功'}及时结束反馈，周期重新排队且输出清空：通过")

            # 模拟数据已完成、收尾任务尚未运行就退出进程，验证独立结果和旧版本缓存均能恢复。
            for legacy in (False, True):
                restored = ET.Element("map")
                for widget_id, value in last_busy.items():
                    ET.SubElement(restored, "string", name=widget_id).text = json.dumps(value)
                adb("shell", "am", "force-stop", package)
                adb("shell", "run-as", package, "tee", feedback_path, data=ET.tostring(restored))
                if legacy:
                    adb("shell", "run-as", package, "rm", "-f", "shared_prefs/widget_refresh_results.xml")
                adb("shell", "am", "start", "-n", package + "/.MainActivity")
                phase("Success")
                wait_idle()
                record(f"进程恢复：{'升级前缓存' if legacy else '持久化本轮结果'}结束周期刷新反馈：通过")
            (output / "periodic-results.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
            return
        if not args.extended_only and not args.skip_basic:
            base_get, base_post = mode["get"], mode["post"]
            recording = subprocess.Popen([args.adb, "-s", args.serial, "shell", "screenrecord",
                "--time-limit", "18", "/sdcard/refresh-in-place.mp4"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            tap(surface)
            busy = phase("Refreshing")
            assert len(busy) == 1, "原位状态提示只能属于点击的小部件"
            request_id = next(iter(busy.values()))["requestId"]
            tap(surface)
            tap(surface)
            root = tree()
            assert any(n.get("text") == "刷新中" for n in root.iter("node"))
            assert [n.get("text") for n in root.iter("node") if re.fullmatch(r"\d+%", n.get("text", ""))] == idle_percentages, "刷新中仍需显示原有额度"
            assert {n.get("text"): n.get("bounds") for n in root.iter("node") if n.get("text") in idle_titles} == idle_titles, "刷新状态不得移动标题"
            shot("busy")
            # 采样多帧，确认扫光由宿主持续播放，且数值区域不闪烁。
            samples = []
            for i in range(6):
                raw = adb("exec-out", "screencap", "-p")
                (output / f"sweep-{i}.png").write_bytes(raw)
                samples.append(Image.open(io.BytesIO(raw)).convert("RGB"))
                time.sleep(.12)
            percentage = next(n for n in root.iter("node") if n.get("text") == idle_percentages[0])
            percent_box = tuple(map(int, re.findall(r"\d+", percentage.get("bounds"))))
            # 背景扫描可穿过数字后方，只校验不透明文字像素，避免把背景变化误报为闪字。
            number = int(idle_percentages[0][:-1])
            foreground = (16, 185, 129) if number > 70 else (224, 170, 20) if number >= 30 else (198, 87, 70)
            text_images = [im.crop(percent_box) for im in samples]
            glyph = [(x, y) for y in range(text_images[0].height) for x in range(text_images[0].width)
                     if text_images[0].getpixel((x, y)) == foreground]
            assert len(glyph) > 20
            assert all(im.getpixel(point) == foreground for im in text_images[1:] for point in glyph), "扫描光效不得遮盖文字"
            assert any(ImageChops.difference(text_images[0], im).getbbox() for im in text_images[1:]), "整卡扫描没有播放"
            scan_box = (first_time_box[0], percent_box[3], first_time_box[2], first_time_box[1])
            assert any(ImageChops.difference(samples[0].crop(scan_box), im.crop(scan_box)).getbbox()
                       for im in samples[1:]), "进度条粒子没有连续播放"
            phase("Success")
            result_shot("success", "green")
            wait_idle()
            assert mode["get"] - base_get == 1 and mode["post"] - base_post == 1
            recording.wait(timeout=22)
            adb("pull", "/sdcard/refresh-in-place.mp4", str(output / "refresh-in-place.mp4"))
            recording = None
            record("整卡扫描、粒子持续播放、文字前景稳定、成功对勾、重复点击合并：通过")

            mode.update(failure=True, delay=2)
            tap(surface)
            phase("Failure")
            result_shot("failure", "red")
            phase("Error")
            root = tree()
            assert any("刷新失败" in n.get("text", "") for n in root.iter("node"))
            assert any(n.get("text") == "75%" for n in root.iter("node"))
            shot("retry-hint")
            record("失败状态原位保留、缓存额度及重试入口持续可见：通过")

            mode.update(failure=False, delay=0)
            tap(surface)
            success = next(iter(phase("Success").values()))
            assert success["completedAt"] - success["startedAt"] >= 700
            wait_idle()
            record("快速请求至少保留 700ms 等待反馈，同样的额度也显示成功：通过")

            mode.update(failure=True, delay=1)
            tap(surface)
            first = next(iter(phase("Failure").values()))
            result_shot("failure-before-retry", "red")
            mode.update(failure=False, delay=6)
            tap(surface)
            second = next(iter(phase("Refreshing").values()))
            assert first["requestId"] != second["requestId"] != request_id
            time.sleep(2.2)
            assert next(iter(feedback().values()))["requestId"] == second["requestId"]
            assert next(iter(feedback().values()))["phase"] == "Refreshing"
            phase("Success")
            wait_idle()
            record("失败状态可立即重试，旧请求的清理任务不覆盖新请求：通过")

        if not args.extended_only:
            mode.update(failure=True, delay=2)
            tap(surface)
            phase("Error")
            for width, height, font in ((110, 110, "1.0"), (110, 110, "1.3"), (151, 167, "1.3")):
                root = host(width, height, font)
                assert any("刷新失败" in n.get("text", "") or "刷新失败" in n.get("content-desc", "") for n in root.iter("node"))
                shot(f"error-{width}-{height}-font{font}")
                tap(next(n for n in root.iter("node") if n.get("class") == "android.appwidget.AppWidgetHostView"))
                phase("Failure")
                time.sleep(.2)
                shot(f"status-{width}-{height}-font{font}")
                phase("Error")
            record("最小尺寸及 1.3 倍字体的原位状态与图标提示不截断：通过")
            adb("shell", "cmd", "uimode", "night", "yes")
            root = host()
            shot("dark-mode")
            assert any("刷新失败" in n.get("content-desc", "") for n in root.iter("node"))
            adb("shell", "cmd", "uimode", "night", "no")
            record("深色模式仍保持白底、清晰的额度和原位反馈：通过")

        root = host()
        surface = next(n for n in root.iter("node") if n.get("class") == "android.appwidget.AppWidgetHostView")
        mode.update(failure=False, delay=6)
        tap(surface)
        phase("Refreshing")
        adb("shell", "am", "force-stop", package)
        adb("shell", "am", "start", "-n", package + "/.MainActivity")
        host()
        wait_for(lambda values: any(v["phase"] == "Success" for v in values.values()), timeout=70)
        wait_idle()
        assert not any(n.get("text") == "刷新中" for n in tree().iter("node"))
        record("刷新期间进程被停止后可恢复任务，完成后不残留刷新状态：通过")

        mode.update(failure=False, delay=8)
        base_get = mode["get"]
        adb("shell", "am", "start", "-n", package + "/.MainActivity")
        time.sleep(.5)
        tap(next(n for n in tree().iter("node") if n.get("text") == "刷新额度"))
        root = host()
        assert not feedback(), "App 发起刷新时不应主动显示手动状态"
        assert not any(n.get("text") == "刷新中" for n in root.iter("node"))
        tap(next(n for n in root.iter("node") if n.get("class") == "android.appwidget.AppWidgetHostView"))
        phase("Refreshing")
        phase("Success")
        wait_idle()
        assert mode["get"] - base_get == 1, "小部件应复用 App 已在进行的刷新"
        record("App 内刷新采用轻反馈，随后点击小部件复用现有任务并原位显示结果：通过")
        (output / "results.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding="utf-8")
    finally:
        if recording is not None:
            recording.wait(timeout=22)
        adb("shell", "am", "force-stop", package)
        adb("shell", "run-as", package, "tee", state_path, data=original)
        adb("shell", "run-as", package, "tee", feedback_path, data=old_feedback)
        adb("shell", "settings", "put", "system", "font_scale", original_font)
        adb("shell", "cmd", "uimode", "night", original_night)
        adb("shell", "am", "start", "-n", package + "/.MainActivity")
        adb("reverse", "--remove", f"tcp:{port}")
        server.shutdown()


if __name__ == "__main__":
    main()

"""使用独立 Android 模拟器和本机模拟接口验证应用；不会连接真实账号。"""

import argparse
import json
import re
import sqlite3
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PACKAGE = "com.neo.codexquota"
TEST_KEY = "emulator-test-key"
WRONG_KEY = "draft-wrong-key"
REGRESSION_ACCOUNT_ID = "codex-regression-10.json::10"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--settings-only", action="store_true", help="只运行桌面设置与小部件检查")
    args = parser.parse_args()
    output = Path(__file__).resolve().parents[1] / "build" / "verification"
    output.mkdir(parents=True, exist_ok=True)
    (output / "smoke-results.json").unlink(missing_ok=True)

    def adb(*parts, binary=False):
        result = subprocess.run([args.adb, "-s", args.serial, *parts], check=True, capture_output=True)
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace").strip()

    if not args.serial.startswith("emulator-") or adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("此脚本仅允许操作独立测试模拟器")

    # 本机模拟服务通过 adb reverse 访问；测试网络无需依赖外部联网验证。
    original_portal_mode = adb("shell", "settings", "get", "global", "captive_portal_mode")
    adb("shell", "settings", "put", "global", "captive_portal_mode", "0")
    adb("shell", "svc", "data", "disable")
    adb("shell", "svc", "data", "enable")
    time.sleep(2)

    mode = {"value": "ok"}
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def respond(self, status, body):
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.headers.get("Authorization") != f"Bearer {TEST_KEY}":
                return self.respond(401, {"error": "invalid key"})
            if mode["value"] in ("slow", "slow-offline"):
                time.sleep(12)
            if mode["value"] == "slow-offline":
                return self.respond(503, {"error": "test offline"})
            if mode["value"] == "offline":
                return self.respond(503, {"error": "test offline"})
            if self.path != "/v8/management/credentials":
                return self.respond(404, {})
            self.respond(200, {"files": [
                {"name": "codex-regression-10.json" if name == "alpha" else name + ".json",
                 "provider": "codex", "auth_index": "10" if name == "alpha" else name,
                 "email": name + "@example.com", "id_token": {
                     "chatgpt_account_id": "account-" + name, "plan_type": "plus"}}
                for name in ("alpha", "beta")
            ]})

        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            requests.append(payload)
            if self.path != "/v8/management/requests/api-call":
                return self.respond(404, {})
            if mode["value"] == "partial" and payload["auth_index"] == "beta":
                return self.respond(200, {"status_code": 401, "body": "{}"})
            now = int(time.time())
            body = {"plan_type": "plus", "rate_limit": {
                "primary_window": {"used_percent": 55 if mode["value"] == "changed" else (70 if payload["auth_index"] == "beta" else 25),
                                   "limit_window_seconds": 18000, "reset_at": now + 7200},
                "secondary_window": {"used_percent": 90 if payload["auth_index"] == "beta" else 60,
                                     "limit_window_seconds": 604800, "reset_at": now + 172800},
            }}
            self.respond(200, {"status_code": 200, "body": json.dumps(body)})

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port = server.server_port
    adb("reverse", f"tcp:{port}", f"tcp:{port}")

    def tree():
        adb("shell", "uiautomator", "dump", "/sdcard/quota-verification.xml")
        raw = adb("shell", "cat", "/sdcard/quota-verification.xml")
        return ET.fromstring(raw)

    def tap(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(0.4)

    def click_text(text, scroll=False, last=False):
        for _ in range(8 if scroll else 1):
            matches = [n for n in tree().iter("node") if n.attrib.get("text") == text]
            if matches:
                tap(matches[-1] if last else matches[0])
                return
            if scroll:
                adb("shell", "input", "swipe", "540", "1450", "540", "650", "250")
        raise AssertionError(f"未找到界面文本：{text}")

    def top():
        for _ in range(4):
            adb("shell", "input", "swipe", "540", "600", "540", "1550", "150")

    def hide_keyboard():
        details = adb("shell", "dumpsys", "input_method")
        if "mInputShown=true" in details:
            adb("shell", "input", "keyevent", "4")

    def state():
        raw = adb("exec-out", "run-as", PACKAGE, "cat", "files/datastore/codex_quota.preferences_pb", binary=True)
        assert TEST_KEY.encode() not in raw, "管理密钥不能以明文落盘"
        assert WRONG_KEY.encode() not in raw, "草稿密钥不能以明文落盘"
        decoded = raw[raw.index(b"{"):].decode("utf-8", errors="ignore")
        return json.JSONDecoder().raw_decode(decoded)[0]

    def wait_state(predicate, timeout=25):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                current = state()
                if predicate(current):
                    return current
            except (subprocess.CalledProcessError, ValueError):
                pass
            time.sleep(0.3)
        raise AssertionError("等待应用状态超时")

    def screenshot(name):
        (output / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        ET.ElementTree(tree()).write(output / f"{name}.xml", encoding="utf-8")

    def connection_fields():
        top()
        for _ in range(6):
            fields = [n for n in tree().iter("node") if n.attrib.get("class") == "android.widget.EditText"]
            if len(fields) == 2:
                return fields
            adb("shell", "input", "swipe", "540", "1450", "540", "850", "200")
        raise AssertionError("未找到连接输入框")

    def replace_field(index, value):
        tap(connection_fields()[index])
        adb("shell", "input", "keyevent", "123")
        adb("shell", "input", "keyevent", *(["67"] * 100))
        if value:
            adb("shell", "input", "text", value)
        hide_keyboard()

    def assert_inputs(address):
        fields = connection_fields()
        assert fields[0].attrib.get("text") == address, "服务器输入不能丢失"
        assert fields[1].attrib.get("text"), "密钥输入不能丢失"

    def periodic_rows(name="quota_periodic"):
        snapshot = output / "workdb"
        snapshot.mkdir(exist_ok=True)
        for suffix in ("", "-wal", "-shm"):
            (snapshot / ("androidx.work.workdb" + suffix)).unlink(missing_ok=True)
        # 通过 SQLite 一致性备份读取，避免主库与 WAL 在复制过程中发生变化。
        adb("shell", f"run-as {PACKAGE} sqlite3 no_backup/androidx.work.workdb "
            "'.backup cache/work-verification.db'")
        (snapshot / "androidx.work.workdb").write_bytes(adb(
            "exec-out", "run-as", PACKAGE, "cat", "cache/work-verification.db", binary=True))
        with closing(sqlite3.connect(snapshot / "androidx.work.workdb")) as connection:
            return connection.execute("""SELECT w.id,w.interval_duration,w.initial_delay,w.last_enqueue_time
                FROM workspec w JOIN workname n ON n.work_spec_id=w.id
                WHERE n.name=? AND w.state IN (0,1,4)""", (name,)).fetchall()

    def wait_periodic(minutes):
        end = time.monotonic() + 15
        while time.monotonic() < end:
            rows = periodic_rows()
            if minutes is None and not rows:
                return rows
            if minutes is not None and len(rows) == 1 and rows[0][1] == minutes * 60_000:
                assert rows[0][2] == minutes * 60_000
                return rows
            time.sleep(.3)
        raise AssertionError(f"周期任务配置不正确：{minutes} 分钟，实际 {rows}")

    def open_interval():
        top()
        click_text("桌面设置", scroll=True)
        current = state().get("refreshIntervalMinutes")
        click_text(f"{current} 分钟" if current is not None else "关闭（仅手动刷新）", scroll=True)

    def pin_widget():
        top()
        click_text("添加到桌面")
        choices = [n for n in tree().iter("node") if n.attrib.get("text", "").upper() in ("ADD AUTOMATICALLY", "自动添加")]
        assert choices, "未找到系统的小部件添加确认按钮"
        tap(choices[0])
        adb("shell", "input", "keyevent", "3")
        time.sleep(2)

    checks = []

    def record(message):
        checks.append(message)
        print(message, flush=True)

    try:
        adb("logcat", "-c")
        adb("shell", "pm", "clear", PACKAGE)
        adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        time.sleep(1)
        address = f"http://127.0.0.1:{port}"
        if args.settings_only:
            replace_field(0, address)
            replace_field(1, TEST_KEY)
            click_text("保存并连接", scroll=True)
            wait_state(lambda s: len(s.get("snapshots", [])) == 2)
            top()
        else:
            wrong_address = address + "/wrong"
            replace_field(0, wrong_address)
            replace_field(1, WRONG_KEY)
            wait_state(lambda s: s.get("connectionDraft", {}).get("server") == wrong_address
                       and s["connectionDraft"]["encryptedKey"])
            click_text("保存并连接", scroll=True)
            top()
            click_text("管理密钥无效，请重新填写", scroll=True)
            assert_inputs(wrong_address)
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
            assert_inputs(wrong_address)
            assert not state()["server"] and not state()["encryptedKey"]
            record("错误密钥后输入保留，进程重启恢复地址与加密密钥草稿通过")

            replace_field(1, TEST_KEY)
            click_text("保存并连接", scroll=True)
            top()
            click_text("未找到 v8 管理接口，请检查地址和服务版本", scroll=True)
            adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
            adb("shell", "settings", "put", "system", "user_rotation", "1")
            time.sleep(1)
            adb("shell", "settings", "put", "system", "user_rotation", "0")
            time.sleep(1)
            assert_inputs(wrong_address)
            record("错误地址可继续修改，滚动与旋转屏幕后输入保留通过")

            # 仅修改地址，使用恢复后保留的密钥连接，证明密钥内容也正确恢复。
            replace_field(0, address)
            click_text("保存并连接", scroll=True)
            current = wait_state(lambda s: len(s.get("snapshots", [])) == 2)
            assert "connectionDraft" not in current
            assert current["defaultAccountId"] == REGRESSION_ACCOUNT_ID
            assert current["refreshIntervalMinutes"] == 30
            assert current["snapshots"][0]["windows"][0]["remainingPercent"] == 75
            assert current["encryptedKey"] and current["encryptedKey"] != TEST_KEY
            assert all(r["header"]["Authorization"] == "Bearer $TOKEN$" for r in requests)
            record("连接 v8 接口、双账号查询、额度换算和密钥加密通过")
            click_text("账号与额度", scroll=True)
            adb("shell", "input", "swipe", "540", "1450", "540", "1000", "250")
            click_text("alpha@example.com", scroll=True, last=True)
            screenshot("account-detail")
            click_text("关闭")
            top()
            screenshot("connected")

            active_key = current["encryptedKey"]
            click_text("编辑连接", scroll=True)
            replace_field(1, WRONG_KEY)
            click_text("保存并连接", scroll=True)
            top()
            click_text("管理密钥无效，请重新填写", scroll=True)
            assert_inputs(address)
            failed_edit = state()
            assert failed_edit["encryptedKey"] == active_key
            assert failed_edit["snapshots"] == current["snapshots"]
            top()
            click_text("收起", scroll=True)
            top()
            click_text("编辑连接", scroll=True)
            assert_inputs(address)
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
            assert_inputs(address)
            record("修改失败不覆盖正式密钥与缓存，收起和重启后草稿自动展开通过")

            replace_field(1, "")
            click_text("保存并连接", scroll=True)
            previous_times = {x["accountId"]: x["fetchedAt"] for x in current["snapshots"]}
            # 正式配置先提交，额度随后由异步任务刷新；必须等待刷新完成再记录离线测试基线。
            current = wait_state(lambda s: "connectionDraft" not in s
                                 and len(s.get("snapshots", [])) == len(previous_times)
                                 and all(x["fetchedAt"] > previous_times[x["accountId"]]
                                         for x in s["snapshots"]))
            top()
            record("编辑连接留空沿用正式密钥，连接成功清除草稿通过")

            before = {s["accountId"]: s["fetchedAt"] for s in current["snapshots"]}
            mode["value"] = "offline"
            click_text("刷新额度")
            failed = wait_state(lambda s: s.get("globalError") is not None)
            assert before == {s["accountId"]: s["fetchedAt"] for s in failed["snapshots"]}
            record("服务错误保留成功额度与原更新时间通过")
            screenshot("offline-cache")

            mode["value"] = "partial"
            click_text("刷新额度")
            partial = wait_state(lambda s: any(x.get("error") for x in s.get("snapshots", [])) and not s.get("globalError"))
            beta = next(s for s in partial["snapshots"] if "beta" in s["accountId"])
            alpha = next(s for s in partial["snapshots"] if s["accountId"] == REGRESSION_ACCOUNT_ID)
            assert beta["fetchedAt"] == before[beta["accountId"]] and beta["windows"]
            assert not alpha.get("error")
            record("单个账号授权失效不阻断其他账号，并保留旧数据通过")

        mode["value"] = "ok"
        click_text("刷新额度")
        wait_state(lambda s: all(not x.get("error") for x in s.get("snapshots", [])))
        pin_widget()
        wait_periodic(30)
        screenshot("widget-light")
        visible = ET.tostring(tree(), encoding="unicode")
        assert "5H限额" in visible and "周限额" in visible and "75%" in visible and "40%" in visible
        assert "alpha@example.com" not in visible and "beta@example.com" not in visible
        assert "后重置" not in visible and "android.widget.ListView" not in visible
        assert "小时后" in visible and "天后" in visible
        card = next(n for n in tree().iter("node") if n.attrib.get("content-desc") == "刷新数据")
        assert not any(n is not card and n.attrib.get("clickable") == "true" for n in card.iter("node"))
        record("单账号双横行额度、重置时间、整卡刷新与默认 30 分钟周期通过")

        adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        open_interval()
        click_text("自定义分钟数")
        fields = [n for n in tree().iter("node") if n.attrib.get("class") == "android.widget.EditText"]
        tap(fields[0])
        adb("shell", "input", "keyevent", "123", "67", "67")
        adb("shell", "input", "text", "4")
        hide_keyboard()
        save = next(n for n in tree().iter("node") if n.attrib.get("text") == "保存")
        assert state()["refreshIntervalMinutes"] == 30
        # 点击禁用的保存按钮也不能提交非法间隔。
        tap(save)
        assert "自定义刷新间隔" in ET.tostring(tree(), encoding="unicode")
        fields = [n for n in tree().iter("node") if n.attrib.get("class") == "android.widget.EditText"]
        tap(fields[0])
        adb("shell", "input", "keyevent", "123", "67", "67")
        adb("shell", "input", "text", "47")
        hide_keyboard()
        click_text("保存")
        wait_state(lambda s: s["refreshIntervalMinutes"] == 47)
        first_job = wait_periodic(47)[0]
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        assert state()["refreshIntervalMinutes"] == 47
        same_job = wait_periodic(47)[0]
        assert same_job[0] == first_job[0] and same_job[3] == first_job[3], "重新打开不能重置周期计时"
        record("自定义间隔拒绝 4 分钟、接受 47 分钟并恢复，重启不重置任务计时通过")

        open_interval()
        click_text("5 分钟")
        wait_state(lambda s: s["refreshIntervalMinutes"] == 5)
        wait_periodic(None)
        time.sleep(1)
        short = periodic_rows("quota_short_cycle")
        assert len(short) == 1 and short[0][1] == 0 and short[0][2] == 300_000
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        time.sleep(1)
        assert periodic_rows("quota_short_cycle") == short, "重启不能重置五分钟任务"

        # 仅在独立模拟器将待执行任务延迟归零，验证真实 Worker 会续接下一轮。
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", f"run-as {PACKAGE} sqlite3 no_backup/androidx.work.workdb "
            f"\"UPDATE workspec SET initial_delay=0 WHERE id='{short[0][0]}';\"")
        requests.clear()
        adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        time.sleep(2)
        job_id = adb("shell", f"run-as {PACKAGE} sqlite3 no_backup/androidx.work.workdb "
            f"\"SELECT system_id FROM systemidinfo WHERE work_spec_id='{short[0][0]}';\"")
        if job_id:
            # 延迟归零后系统可能已完成该任务，23 表示任务已不在调度队列。
            forced = subprocess.run([args.adb, "-s", args.serial, "shell", "cmd", "jobscheduler", "run", "-f", PACKAGE, job_id],
                                    capture_output=True)
            if forced.returncode not in (0, 23):
                raise RuntimeError(forced.stderr.decode("utf-8", errors="replace"))
        end = time.monotonic() + 35
        while time.monotonic() < end:
            following = periodic_rows("quota_short_cycle")
            if len(following) == 1 and following[0][0] != short[0][0] and following[0][2] == 300_000:
                break
            time.sleep(.5)
        else:
            raise AssertionError(f"五分钟任务没有续接：{following}")
        assert requests and all(r["auth_index"] == "10" for r in requests)
        record("五分钟单次任务、重启保留计时、真实 Worker 默认账号查询和续接通过")

        open_interval()
        click_text("10 分钟")
        wait_state(lambda s: s["refreshIntervalMinutes"] == 10)
        time.sleep(1)
        short10 = periodic_rows("quota_short_cycle")
        assert len(short10) == 1 and short10[0][2] == 600_000 and short10[0][0] != following[0][0]
        open_interval()
        click_text("关闭自动刷新")
        wait_state(lambda s: s["refreshIntervalMinutes"] is None)
        time.sleep(1)
        assert not periodic_rows("quota_short_cycle")
        record("短间隔改为十分钟重新计时，关闭后取消连续任务通过")

        open_interval()
        click_text("15 分钟")
        wait_state(lambda s: s["refreshIntervalMinutes"] == 15)
        assert wait_periodic(15)[0][0] != first_job[0]
        assert not periodic_rows("quota_short_cycle")
        open_interval()
        click_text("关闭自动刷新")
        wait_state(lambda s: s["refreshIntervalMinutes"] is None)
        wait_periodic(None)
        record("修改间隔替换唯一周期任务，关闭自动刷新取消任务通过")

        top()
        click_text("alpha@example.com", scroll=True)
        assert "选择默认账号" in ET.tostring(tree(), encoding="unicode")
        requests.clear()
        click_text("beta@example.com")
        wait_state(lambda s: s["defaultAccountId"] == "beta.json::beta")
        time.sleep(1)
        assert requests and all(r["auth_index"] == "beta" for r in requests)
        pin_widget()
        visible = ET.tostring(tree(), encoding="unicode")
        assert "30%" in visible and "10%" in visible and "75%" not in visible
        # 小方形可能被启动器放在不同桌面页，不要求多个实例同时出现在当前页。
        widgets = adb("shell", "dumpsys", "appwidget").split("Widgets:", 1)[1].split("Hosts:", 1)[0]
        assert widgets.count(f"{PACKAGE}/{PACKAGE}.widget.QuotaWidgetReceiver") >= 2
        assert visible.count('text="30%"') >= 1
        record("切换默认账号仅查询新账号，两个小部件同步显示且不混用旧额度通过")
        count = len(requests)
        mode["value"] = "changed"
        refresh_nodes = [n for n in tree().iter("node") if n.attrib.get("content-desc") == "刷新数据"]
        assert refresh_nodes
        tap(refresh_nodes[0])
        end = time.monotonic() + 20
        while len(requests) < count + 1 and time.monotonic() < end:
            time.sleep(0.3)
        assert len(requests) == count + 1 and requests[-1]["auth_index"] == "beta"
        wait_state(lambda s: next(x for x in s["snapshots"] if x["accountId"] == "beta.json::beta")["windows"][0]["remainingPercent"] == 45)
        time.sleep(1)
        assert "45%" in ET.tostring(tree(), encoding="unicode")
        wait_periodic(None)
        record("关闭自动刷新后桌面手动刷新仍正常，且只查询默认账号通过")
        # 慢接口下检查宿主动画、重复点击合并和失败后恢复可点击状态。
        for test_mode in ("slow", "slow-offline"):
            mode["value"] = test_mode
            before = state()
            count = len(requests)
            card = next(n for n in tree().iter("node") if n.attrib.get("content-desc") == "刷新数据")
            tap(card)
            loading = tree()
            assert any(n.attrib.get("content-desc") == "刷新数据，刷新中" for n in loading.iter("node"))
            assert any(n.attrib.get("class") == "android.widget.ProgressBar" for n in loading.iter("node"))
            screenshot("widget-" + test_mode)
            tap(card)
            time.sleep(13)
            finished = tree()
            assert not any(n.attrib.get("class") == "android.widget.ProgressBar" for n in finished.iter("node"))
            assert len(requests) == count + (0 if test_mode == "slow-offline" else 1)
            if test_mode == "slow-offline":
                assert state()["snapshots"] == before["snapshots"]
                assert any("刷新失败" in n.attrib.get("content-desc", "") for n in finished.iter("node"))
                screenshot("widget-error-cache")
        mode["value"] = "ok"
        record("刷新呼吸动画、重复点击合并、成功与失败停止动画及保留缓存通过")
        adb("shell", "cmd", "uimode", "night", "yes")
        time.sleep(2)
        screenshot("widget-dark")
        adb("shell", "cmd", "uimode", "night", "no")
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        assert len(state()["snapshots"]) == 2
        assert state()["defaultAccountId"] == "beta.json::beta" and state()["refreshIntervalMinutes"] is None
        wait_periodic(None)
        record("进程重启后配置和额度缓存恢复通过")
        time.sleep(1)
        top()
        root = tree()
        parents = {child: parent for parent in root.iter() for child in parent}
        refresh = next(n for n in root.iter("node") if n.attrib.get("text") == "刷新额度")
        assert parents[refresh].attrib.get("enabled") == "true"
        record("周期任务等待期间仍可手动刷新和编辑配置通过")
        crash = adb("logcat", "-d", "-s", "AndroidRuntime:E", "GlanceAppWidget:E")
        assert "FATAL EXCEPTION" not in crash and "E GlanceAppWidget" not in crash
        (output / "smoke-results.json").write_text(json.dumps({"checks": checks, "quota_requests": len(requests)}, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"验证完成：{len(checks)} 项检查通过", flush=True)
    finally:
        adb("shell", "settings", "put", "system", "accelerometer_rotation", "1")
        if original_portal_mode == "null":
            adb("shell", "settings", "delete", "global", "captive_portal_mode")
        else:
            adb("shell", "settings", "put", "global", "captive_portal_mode", original_portal_mode)
        server.shutdown()
        adb("reverse", "--remove", f"tcp:{port}")


if __name__ == "__main__":
    main()
